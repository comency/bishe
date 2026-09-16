package cn.edu.lostfound.config;

import cn.edu.lostfound.LostFoundApplication;
import cn.edu.lostfound.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.management.OperatingSystemMXBean;
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.assertj.core.api.Assertions.*;

/** Real Spring business services + real isolated MySQL, optionally real local AI. NO HTTP/Redis claim. */
@EnabledIfEnvironmentVariable(named="RUN_SERVICE_BENCHMARK",matches="true")
class ServiceLoadBenchmarkTest {
  private static final String MODEL="qwen3:1.7b", DIGEST="8f68893c685c3ddff2aa3fffce2aa60a30bb2da65ca488b61fff134a4d1730e7";
  private final ObjectMapper mapper=new ObjectMapper();
  private final OperatingSystemMXBean os=(OperatingSystemMXBean)ManagementFactory.getOperatingSystemMXBean();
  private final AtomicLong minimumFree=new AtomicLong(Long.MAX_VALUE);
  private final List<Map<String,Object>> phases=new ArrayList<>(), modelCalls=new ArrayList<>();
  private final ConcurrentLinkedQueue<String> failures=new ConcurrentLinkedQueue<>();

  @Test void boundedTwentyConcurrentQueriesWithOptionalModelComparison() throws Exception {
    String prefix=System.getenv("REHEARSAL_SCHEMA_PREFIX"), password=System.getenv("REHEARSAL_DB_PASSWORD");
    assertThat(prefix).matches("rehearsal_[0-9]{14}_[a-f0-9]{8}_");
    assertThat(password).matches("[a-f0-9]{48}");
    Path directory=Path.of(System.getenv("REHEARSAL_DIRECTORY")).toRealPath();
    assertThat(directory.startsWith(Path.of(".local/database-rehearsal").toRealPath())).isTrue();
    String url="jdbc:mysql://127.0.0.1:13307/"+prefix+"benchmark?useUnicode=true&characterEncoding=utf8&serverTimezone=UTC&connectTimeout=3000&socketTimeout=10000";
    var datasource=new DriverManagerDataSource(url,"rehearsal_runner",password);var jdbc=new JdbcTemplate(datasource);
    var identity=jdbc.queryForMap("SELECT @@port p,@@server_uuid u,@@datadir d");
    assertThat(((Number)identity.get("p")).intValue()).isEqualTo(13307);
    assertThat(identity.get("u")).isEqualTo(System.getenv("REHEARSAL_SERVER_UUID"));
    assertThat(Path.of(identity.get("d").toString()).toRealPath()).isEqualTo(directory.resolve("data").toRealPath());
    assertThat(jdbc.queryForList("SHOW TABLES",String.class)).isEmpty();
    boolean withModel="true".equals(System.getenv("RUN_SERVICE_MODEL_BENCHMARK"));
    if(withModel)checkProvider();
    Flyway.configure().dataSource(datasource).locations("classpath:db/migration").cleanDisabled(true).baselineOnMigrate(false).load().migrate();
    seed(jdbc,datasource);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM items",Long.class)).isEqualTo(10000L);
    Map<String,Object> config=new LinkedHashMap<>();
    config.put("spring.datasource.url",url);config.put("spring.datasource.username","rehearsal_runner");config.put("spring.datasource.password",password);
    config.put("spring.datasource.hikari.maximum-pool-size",3);config.put("spring.datasource.hikari.minimum-idle",1);
    config.put("spring.datasource.hikari.connection-timeout",10000);config.put("spring.flyway.enabled",false);
    config.put("spring.sql.init.mode","never");config.put("spring.jpa.hibernate.ddl-auto","validate");config.put("spring.jpa.generate-ddl",false);
    config.put("spring.data.redis.host","127.0.0.1");config.put("spring.data.redis.port",16380);
    config.put("app.campus.test-mode",true);config.put("app.campus.campus-id","TEST_CAMPUS");config.put("app.admin-password","");
    config.put("app.media.root",directory.resolve("benchmark-media").toString());config.put("app.media.cleanup-enabled",false);
    config.put("ai.enabled",withModel);config.put("ai.base-url","http://127.0.0.1:11434");config.put("ai.model",MODEL);
    config.put("ai.minimum-free-bytes",4L*1024*1024*1024);config.put("ai.timeout-ms",20000);config.put("ai.context-tokens",4096);config.put("ai.output-tokens",512);
    var application=new SpringApplication(LostFoundApplication.class);application.setWebApplicationType(WebApplicationType.NONE);
    application.addInitializers(context->{context.getEnvironment().setActiveProfiles("servicebenchmark");context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("owned-rehearsal",config));});
    ScheduledExecutorService sampler=Executors.newSingleThreadScheduledExecutor();
    sampler.scheduleAtFixedRate(()->minimumFree.accumulateAndGet(os.getFreeMemorySize(),Math::min),0,250,TimeUnit.MILLISECONDS);
    try(var context=application.run()) {
      var items=context.getBean(ItemService.class);var ai=context.getBean(AiService.class);
      long coldStart=System.nanoTime();assertPage(items,10,1);long firstMs=(System.nanoTime()-coldStart)/1_000_000;
      phases.add(Map.of("name","first-service-call-after-seeding","elapsedMs",firstMs,"notOsDiskCold",true));
      for(int i=0;i<20;i++)assertPage(items,10,1+i%10);
      runPhase(items,ai,"model-idle-default-page",10,false);
      if(withModel)runPhase(items,ai,"real-model-parallel-default-page",10,true);
      runPhase(items,ai,"model-idle-maximum-page",50,false);
      assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM items",Long.class)).isEqualTo(10000L);
      assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM claims",Long.class)).isZero();
      if(withModel)assertThat(provider("/api/ps").path("models").isEmpty()).isTrue();
      assertThat(failures).as("service/semantic/resource errors; inspect all retained evidence").isEmpty();
    } catch(Throwable error) {
      failures.add("Benchmark failed: "+error.getClass().getSimpleName());throw error;
    } finally {
      sampler.shutdownNow();
      if(withModel)try{unload();}catch(Exception error){failures.add("Model unload unconfirmed");}
      Map<String,Object> result=new LinkedHashMap<>();result.put("at",Instant.now().toString());result.put("schemaPrefix",prefix);
      result.put("itemCount",10000);result.put("approvedCount",8000);result.put("concurrency",20);result.put("connectionPoolMaximum",3);
      result.put("heapMaxBytes",Runtime.getRuntime().maxMemory());result.put("minimumFreeBytes",minimumFree.get());result.put("withRealModel",withModel);
      result.put("phases",phases);result.put("modelCalls",modelCalls);result.put("failures",failures);
      result.put("limitations",List.of("Service layer only: no HTTP, Redis auth/rate limits, serialization or browser","Not NFR-04 HTTP acceptance or mall parallel-load approval","Newly seeded database is not OS/disk cold","Synthetic 10000 rows, one publisher, no images/claims; other distributions need separate tests","When model is enabled, idle comparison shares the same application process","Latencies include assertions; success percentiles are not a controlled causal comparison"));
      mapper.writerWithDefaultPrettyPrinter().writeValue(directory.resolve("service-benchmark.json").toFile(),result);
      System.out.println("SERVICE BENCHMARK evidence: "+directory.resolve("service-benchmark.json"));
    }
    assertThat(failures).isEmpty();
  }
  private void runPhase(ItemService items,AiService ai,String name,int size,boolean model) throws Exception {
    ConcurrentLinkedQueue<Long> latency=new ConcurrentLinkedQueue<>();CountDownLatch ready=new CountDownLatch(20),start=new CountDownLatch(1);
    AtomicLong deadline=new AtomicLong();List<Future<?>> jobs=new ArrayList<>();
    long started=System.nanoTime();String at=Instant.now().toString();
    var pool=Executors.newFixedThreadPool(21);
    try {
      for(int worker=0;worker<20;worker++){
        int offset=worker;jobs.add(pool.submit(()->{
          ready.countDown();try{start.await();}catch(InterruptedException interrupted){Thread.currentThread().interrupt();return;}
          for(int n=0;System.nanoTime()<deadline.get();n++){
            long callStart=System.nanoTime();
            try{assertPage(items,size,1+(offset+n)%10);latency.add((System.nanoTime()-callStart)/1000);}
            catch(Throwable failure){failures.add(name+": "+failure.getClass().getSimpleName());break;}
          }
        }));
      }
      assertThat(ready.await(5,TimeUnit.SECONDS)).isTrue();
      deadline.set(System.nanoTime()+TimeUnit.SECONDS.toNanos(20));start.countDown();
      Future<?> inference=model?pool.submit(()->{
        String[] inputs={"我只想认领别人捡到的钥匙，也得先发一个启事吗？","申请刚提交，还没被接受，什么时候才能看到对方联系方式？","认领被接受后，双方应该如何确认归还？"};
        try{
          var policy=mapper.readTree(Files.readString(Path.of("src/main/resources/ai-content-policy.json")));
          for(int i=0;i<inputs.length;i++){
            long callStart=System.nanoTime(),free=os.getFreeMemorySize();var reply=ai.chat(1L,inputs[i]);
            long finished=System.nanoTime();
            synchronized(modelCalls){modelCalls.add(Map.of("input",inputs[i],"result",reply,"elapsedMs",(finished-callStart)/1_000_000,"freeBeforeBytes",free,
              "startedWithinQueryLoad",callStart<deadline.get(),"finishedWithinQueryLoad",finished<=deadline.get()));}
            if(!reply.status().equals("GENERATED")||!reply.content().equals(policy.path("guideStatements").get(i+2).asText()))failures.add("Model case "+i+": unavailable or irrelevant");
          }
        }catch(Exception failure){failures.add("Model phase: "+failure.getClass().getSimpleName());}
      }):null;
      for(var job:jobs)job.get(30,TimeUnit.SECONDS);if(inference!=null)inference.get(70,TimeUnit.SECONDS);
    } finally {
      deadline.set(0);start.countDown();pool.shutdownNow();
      if(!pool.awaitTermination(15,TimeUnit.SECONDS))failures.add(name+": worker shutdown unconfirmed");
    }
    List<Long> values=new ArrayList<>(latency);Collections.sort(values);assertThat(values).isNotEmpty();
    Map<String,Object> phase=new LinkedHashMap<>();phase.put("name",name);phase.put("startedAt",at);phase.put("endedAt",Instant.now().toString());
    phase.put("pageSize",size);phase.put("scheduledLoadSeconds",20);phase.put("completed",values.size());phase.put("wallMs",(System.nanoTime()-started)/1_000_000);
    phase.put("latencyUnit","microseconds");phase.put("min",values.getFirst());phase.put("p50",percentile(values,.50));phase.put("p95",percentile(values,.95));phase.put("p99",percentile(values,.99));phase.put("max",values.getLast());
    phase.put("errorsSoFar",failures.size());phase.put("serviceLayerP95Over800ms",percentile(values,.95)>800_000);
    phases.add(phase);System.out.println("SERVICE PHASE "+name+": "+values.size()+" calls p95="+percentile(values,.95)/1000.0+"ms");
  }
  private static long percentile(List<Long> values,double fraction){return values.get(Math.max(0,(int)Math.ceil(values.size()*fraction)-1));}
  private void assertPage(ItemService service,int size,int page) {
    var result=service.page(1L,"public",page,size,"","",null,null,null);
    assertThat(result.total()).isEqualTo(8000);assertThat(result.records()).hasSize(size);
    for(var item:result.records())assertThat(item).containsEntry("status","APPROVED").doesNotContainKeys("description","contact","identification","internalNote");
  }
  private void seed(JdbcTemplate jdbc,DriverManagerDataSource datasource)throws Exception {
    jdbc.update("INSERT INTO users(id,username,password,nickname,role,is_test,created_at,updated_at) VALUES(1,'benchmark_synthetic_user','not-a-real-hash','合成性能用户','USER',TRUE,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)),(2,'benchmark_synthetic_admin','not-a-real-hash','合成管理员','ADMIN',TRUE,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))");
    jdbc.update("INSERT INTO campus_verifications(user_id,campus_code,stored_status,created_at,updated_at) VALUES(1,'TEST_CAMPUS','UNVERIFIED',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)),(2,'TEST_CAMPUS','UNVERIFIED',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))");
    jdbc.update("INSERT INTO verification_applications(id,user_id,application_version,campus_code,real_name,status,method,evidence_summary,valid_through,reviewed_by,reviewed_at,submitted_at,is_test) VALUES(1,1,1,'TEST_CAMPUS','合成性能用户','VERIFIED','IN_PERSON','Synthetic only','2099-12-31',2,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),TRUE)");
    jdbc.update("UPDATE campus_verifications SET stored_status='VERIFIED',current_application_id=1,last_application_version=1,expires_at='2100-01-01' WHERE user_id=1");
    try(var connection=datasource.getConnection();var statement=connection.prepareStatement("INSERT INTO items(id,publisher_id,title,description,type,category,location,status,created_at,updated_at,created_at_utc,updated_at_utc) VALUES(?,1,?,?,?,?,'合成地点',?,'2026-09-01','2026-09-01','2026-08-31 16:00:00','2026-08-31 16:00:00')")){
      connection.setAutoCommit(false);
      for(int i=1;i<=10000;i++){
        statement.setInt(1,i);statement.setString(2,"合成物品 "+i);statement.setString(3,"仅用于独立性能演练，无真实个人信息。".repeat(4));
        statement.setString(4,i%2==0?"FOUND":"LOST");statement.setString(5,i%3==0?"books":"other");statement.setString(6,i%5==0?"PENDING":"APPROVED");statement.addBatch();
        if(i%500==0)statement.executeBatch();
      }
      connection.commit();
    }
  }
  private com.fasterxml.jackson.databind.JsonNode provider(String path)throws Exception {
    try(var client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()){
      var response=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:11434"+path)).timeout(Duration.ofSeconds(5)).GET().build(),HttpResponse.BodyHandlers.ofString());
      assertThat(response.statusCode()).isEqualTo(200);return mapper.readTree(response.body());
    }
  }
  private void checkProvider()throws Exception {
    assertThat(os.getFreeMemorySize()).isGreaterThanOrEqualTo(4L*1024*1024*1024);
    assertThat(provider("/api/version").path("version").asText()).isEqualTo("0.34.1");assertThat(provider("/api/ps").path("models").isEmpty()).isTrue();
    boolean pinned=false;for(var tag:provider("/api/tags").path("models"))if(MODEL.equals(tag.path("name").asText())&&DIGEST.equals(tag.path("digest").asText()))pinned=true;assertThat(pinned).isTrue();
  }
  private void unload()throws Exception {
    try(var client=HttpClient.newHttpClient()){
      var response=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:11434/api/generate")).timeout(Duration.ofSeconds(10)).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString("{\"model\":\"qwen3:1.7b\",\"keep_alive\":0}")).build(),HttpResponse.BodyHandlers.discarding());
      assertThat(response.statusCode()).isEqualTo(200);assertThat(provider("/api/ps").path("models").isEmpty()).isTrue();
    }
  }
}
