package cn.edu.lostfound.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/** Test-only owned subprocess. Credentials travel only in the child environment, never argv/log output. */
final class PackagedBenchmarkTarget implements AutoCloseable {
  private final Path directory;
  private Process process;
  private LettuceConnectionFactory factory;
  private StringRedisTemplate redis;
  private int port;
  private boolean forced;
  private final ObjectMapper mapper=new ObjectMapper();

  static PackagedBenchmarkTarget start(Map<String,Object> config,Path directory) throws Exception {
    var target=new PackagedBenchmarkTarget(directory);
    try {target.launch(config);return target;}
    catch(Exception|Error failure){try{target.close();}catch(Exception cleanup){failure.addSuppressed(cleanup);}throw failure;}
  }
  private PackagedBenchmarkTarget(Path directory){this.directory=directory;}
  static String digest(Path file) throws Exception {
    var hash=MessageDigest.getInstance("SHA-256");
    try(var in=Files.newInputStream(file)){byte[] buffer=new byte[65536];for(int n;(n=in.read(buffer))!=-1;)hash.update(buffer,0,n);}
    return HexFormat.of().formatHex(hash.digest());
  }
  static void retainSafeEnvironment(Map<String,String> environment){
    var allowed=Set.of("systemroot","windir","temp","tmp","path","userprofile","localappdata");
    environment.keySet().removeIf(key->!allowed.contains(key.toLowerCase(Locale.ROOT)));
  }
  static int listeningPort(String log){
    var match=Pattern.compile("Tomcat started on port (\\d+) \\(http\\)").matcher(log);
    if(!match.find())return 0;
    int port=Integer.parseInt(match.group(1));
    if(port<1024 || port>65535)throw new IllegalArgumentException("Unexpected packaged HTTP port");
    return port;
  }
  private void launch(Map<String,Object> config) throws Exception {
    Path source=Path.of(System.getenv("HTTP_CANDIDATE_JAR")).toRealPath();
    Path releaseRoot=Path.of(".local/releases").toRealPath();
    String expected=System.getenv("HTTP_CANDIDATE_SHA256");
    if(!source.startsWith(releaseRoot) || expected==null || !expected.matches("[a-f0-9]{64}") || !digest(source).equals(expected))
      throw new IllegalArgumentException("Verified local candidate hash required");
    Path jar=directory.resolve("candidate.jar");Files.copy(source,jar);
    if(!digest(jar).equals(expected))throw new IllegalStateException("Owned candidate copy mismatch");
    // Only packaged configuration files; do not load the operator's working-directory configuration.
    var launch=new LinkedHashMap<>(config);launch.put("spring.config.location","classpath:/application.yml");
    launch.put("spring.profiles.active","httpbenchmark");
    var builder=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java.exe").toString(),
        "-Xms64m","-Xmx384m","-Xlog:gc*,safepoint:file=candidate-gc.log:time,uptime,level,tags",
        "-jar",jar.toString());
    builder.directory(directory.toFile());retainSafeEnvironment(builder.environment());
    builder.environment().put("SPRING_APPLICATION_JSON",mapper.writeValueAsString(launch));
    Path output=directory.resolve("candidate-private.log");
    builder.redirectErrorStream(true).redirectOutput(output.toFile());process=builder.start();
    // No API traffic until the owned child's log reports its random loopback port.
    long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(60);
    while(System.nanoTime()<deadline){
      if(!process.isAlive())throw new IllegalStateException("Packaged candidate startup failed; private log retained");
      port=listeningPort(Files.exists(output)?Files.readString(output):"");if(port!=0)break;
      Thread.sleep(100);
    }
    if(port==0)throw new IllegalStateException("Packaged candidate startup timed out");
    var standalone=new RedisStandaloneConfiguration("127.0.0.1",16380);standalone.setDatabase(15);
    factory=new LettuceConnectionFactory(standalone);factory.afterPropertiesSet();factory.start();
    redis=new StringRedisTemplate(factory);
    System.out.println("PACKAGED HTTP target PID="+process.pid()+" port="+port+" sha256="+expected);
  }
  int port(){return port;}
  StringRedisTemplate redis(){return redis;}
  void assertAlive(){if(process==null || !process.isAlive())throw new IllegalStateException("Owned candidate no longer alive");}
  @Override public void close() throws Exception {
    try {if(factory!=null)factory.destroy();}
    finally {
      if(process!=null && process.isAlive()){
        process.destroy();
        if(!process.waitFor(10,TimeUnit.SECONDS)){forced=true;process.destroyForcibly();process.waitFor(10,TimeUnit.SECONDS);}
      }
      boolean alive=process!=null && process.isAlive();
      mapper.writerWithDefaultPrettyPrinter().writeValue(directory.resolve("candidate-process.json").toFile(),Map.of(
          "pid",process==null?0:process.pid(),"port",port,"remaining",alive,"forcedFallback",forced,
          "stopMethod","Owned Process.destroy after requests/sessions drained; not proof of graceful JVM shutdown",
          "jarSha256",Objects.toString(System.getenv("HTTP_CANDIDATE_SHA256"),"")));
      if(alive || forced)throw new IllegalStateException("Owned candidate cleanup did not finish normally");
    }
  }
}
