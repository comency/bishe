package cn.edu.lostfound.config;

import com.fasterxml.jackson.databind.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import static org.assertj.core.api.Assertions.*;

/** Frozen JAR, real HTTP/auth/Redis; cuts only owned sockets to an identity-checked MySQL13307. */
@EnabledIfEnvironmentVariable(named="RUN_HTTP_DB_OUTAGE_REHEARSAL",matches="true")
class HttpDatabaseOutageTest {
  private static final String LEASE="http-benchmark:lease";
  private final ObjectMapper mapper=new ObjectMapper();
  private final List<Map<String,Object>> requests=new ArrayList<>(), cycles=new ArrayList<>();
  private final List<String> tokens=new ArrayList<>(), failures=new ArrayList<>();
  private String origin, databasePassword, loginPassword;

  @Test void authenticatedRequestsFailClosedAndRecoverTwiceWithoutRestart() throws Exception {
    String prefix=System.getenv("REHEARSAL_SCHEMA_PREFIX");databasePassword=System.getenv("REHEARSAL_DB_PASSWORD");
    assertThat(prefix).matches("rehearsal_[0-9]{14}_[a-f0-9]{8}_");assertThat(databasePassword).matches("[a-f0-9]{48}");
    Path directory=Path.of(System.getenv("REHEARSAL_DIRECTORY")).toRealPath();
    assertThat(directory.startsWith(Path.of(".local/database-rehearsal").toRealPath())).isTrue();
    String startedAt=Instant.now().toString();boolean completed=false,sessionsRemoved=false;int relayPort=0,forwarded=0,redisRelayPort=0,redisForwarded=0;
    try(var client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER)
        .version(HttpClient.Version.HTTP_1_1).build()) {
      String suffix="/"+prefix+"outage?useUnicode=true&characterEncoding=utf8&serverTimezone=UTC&connectTimeout=1000&socketTimeout=2000";
      var source=new DriverManagerDataSource("jdbc:mysql://127.0.0.1:13307"+suffix,"rehearsal_runner",databasePassword);
      var jdbc=new JdbcTemplate(source);var identity=jdbc.queryForMap("SELECT @@port p,@@server_uuid u,@@datadir d");
      assertThat(((Number)identity.get("p")).intValue()).isEqualTo(13307);
      assertThat(identity.get("u")).isEqualTo(System.getenv("REHEARSAL_SERVER_UUID"));
      assertThat(Path.of(identity.get("d").toString()).toRealPath()).isEqualTo(directory.resolve("data").toRealPath());
      assertThat(jdbc.queryForList("SHOW TABLES",String.class)).isEmpty();
      Flyway.configure().dataSource(source).locations("classpath:db/migration").cleanDisabled(true).baselineOnMigrate(false).load().migrate();
      loginPassword=UUID.randomUUID().toString();
      jdbc.update("INSERT INTO users(id,username,password,nickname,role,is_test,created_at,updated_at) VALUES(1,'http_outage',?,'Before outage','USER',TRUE,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",new BCryptPasswordEncoder().encode(loginPassword));
      jdbc.update("INSERT INTO campus_verifications(user_id,campus_code,stored_status,created_at,updated_at) VALUES(1,'TEST_CAMPUS','UNVERIFIED',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))");
      try(var relay=new DatabaseOutageRehearsalTest.OwnedRelay();var redisRelay=new DatabaseOutageRehearsalTest.OwnedRelay(16380)) {
        relayPort=relay.port();redisRelayPort=redisRelay.port();
        try(var target=PackagedBenchmarkTarget.start(configuration("jdbc:mysql://127.0.0.1:"+relayPort+suffix,directory,redisRelayPort),directory)) {
          origin="http://127.0.0.1:"+target.port();var redis=target.redis();boolean leaseOwned=false;
          try {
            try(var connection=Objects.requireNonNull(redis.getConnectionFactory()).getConnection()) {
              assertThat(connection.serverCommands().dbSize()).isZero();
            }
            assertThat(redis.opsForValue().setIfAbsent(LEASE,prefix,Duration.ofMinutes(10))).isTrue();leaseOwned=true;
            assertHealth(client,"initial-live","/api/health/live",200,"UP");
            assertHealth(client,"initial-ready","/api/health/ready",200,"UP");
            assertPublicConfig(request(client,"initial-config","GET","/api/public/config",null,null,200));
            request(client,"initial-anonymous","GET","/api/users/me",null,null,401);
            String token=login(client,"initial-login");
            assertThat(redis.opsForValue().get("session:"+token)).isEqualTo("1");
            assertProfile(request(client,"initial-profile","GET","/api/users/me",token,null,200),"Before outage",0);
            for(int cycle=1;cycle<=2;cycle++) {
              String phase="cycle-"+cycle, before=cycle==1?"Before outage":"Recovered 1", after="Recovered "+cycle;
              long version=cycle-1;String update=updateBody(after,version);
              int cutSockets=relay.cut();assertThat(cutSockets).isPositive();
              assertHealth(client,phase+"-live-cut","/api/health/live",200,"UP");
              assertHealth(client,phase+"-ready-cut","/api/health/ready",503,"DOWN");
              request(client,phase+"-read-cut","GET","/api/users/me",token,null,503);
              request(client,phase+"-write-cut","PUT","/api/users/me",token,update,503);
              var sessionsBefore=new HashSet<>(Objects.requireNonNull(redis.keys("session:*")));
              request(client,phase+"-login-cut","POST","/api/auth/login",null,loginBody(),503);
              assertThat(redis.keys("session:*")).containsExactlyInAnyOrderElementsOf(sessionsBefore);
              assertThat(redis.opsForValue().get("session:"+token)).isEqualTo("1");
              request(client,phase+"-anonymous-cut","GET","/api/users/me",null,null,401);
              assertPublicConfig(request(client,phase+"-config-cut","GET","/api/public/config",null,null,200));
              assertStored(jdbc,before,version);target.assertAlive();
              relay.restore();long restored=System.nanoTime(), deadline=restored+TimeUnit.SECONDS.toNanos(12);JsonNode recovered;
              do {
                recovered=request(client,phase+"-recovery-read","GET","/api/users/me",token,null,200,503);
                if(recovered.path("code").asInt()==0)break;
                Thread.sleep(100);
              } while(System.nanoTime()<deadline);
              assertProfile(recovered,before,version);
              assertHealth(client,phase+"-ready-restored","/api/health/ready",200,"UP");
              long recoveryMs=(System.nanoTime()-restored)/1_000_000;assertThat(recoveryMs).isLessThan(12000);
              assertProfile(request(client,phase+"-write-restored","PUT","/api/users/me",token,update,200),after,version+1);
              var stale=request(client,phase+"-stale-write","PUT","/api/users/me",token,update,409);
              assertThat(stale.path("errorCode").asText()).isEqualTo("VERSION_CONFLICT");
              assertProfile(request(client,phase+"-confirmed-read","GET","/api/users/me",token,null,200),after,version+1);
              assertStored(jdbc,after,version+1);target.assertAlive();
              cycles.add(Map.of("cycle",cycle,"cutSockets",cutSockets,"recoveryMs",recoveryMs,"versionAfter",version+1));
            }
            int redisCutSockets=redisRelay.cut();assertThat(redisCutSockets).isPositive();
            assertHealth(client,"redis-live-cut","/api/health/live",200,"UP");
            assertHealth(client,"redis-ready-cut","/api/health/ready",503,"DOWN");
            request(client,"redis-authenticated-read-cut","GET","/api/users/me",token,null,503);
            assertPublicConfig(request(client,"redis-config-cut","GET","/api/public/config",null,null,200));
            request(client,"redis-anonymous-cut","GET","/api/users/me",null,null,401);
            redisRelay.restore();long redisRestored=System.nanoTime(),redisDeadline=redisRestored+TimeUnit.SECONDS.toNanos(12);JsonNode redisRecovered;
            do {
              redisRecovered=request(client,"redis-recovery-read","GET","/api/users/me",token,null,200,503);
              if(redisRecovered.path("code").asInt()==0)break;
              Thread.sleep(100);
            } while(System.nanoTime()<redisDeadline);
            assertProfile(redisRecovered,"Recovered 2",2);
            assertHealth(client,"redis-ready-restored","/api/health/ready",200,"UP");
            long redisRecoveryMs=(System.nanoTime()-redisRestored)/1_000_000;assertThat(redisRecoveryMs).isLessThan(12000);
            cycles.add(Map.of("dependency","redis","cutSockets",redisCutSockets,"recoveryMs",redisRecoveryMs));
            String second=login(client,"login-after-two-recoveries");
            assertThat(second).isNotEqualTo(token);assertThat(redis.opsForValue().get("session:"+second)).isEqualTo("1");
            assertProfile(request(client,"fresh-session-read","GET","/api/users/me",second,null,200),"Recovered 2",2);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users",Long.class)).isEqualTo(1L);completed=true;
          } finally {
            relay.restore();redisRelay.restore();
            try {
              for(String token:tokens) {
                try {request(client,"owned-logout","POST","/api/auth/logout",token,null,200);}
                catch(Exception|AssertionError error) {
                  failures.add("Logout failed: "+error.getClass().getSimpleName());
                  if(leaseOwned) compareDelete(redis,"session:"+token,"1");
                }
              }
              sessionsRemoved=tokens.stream().allMatch(token->Boolean.FALSE.equals(redis.hasKey("session:"+token)));
              assertThat(sessionsRemoved).isTrue();
            } finally {if(leaseOwned)assertThat(compareDelete(redis,LEASE,prefix)).isEqualTo(1);}
          }
        } finally {forwarded=relay.forwardedConnections();redisForwarded=redisRelay.forwardedConnections();}
      }
      assertThat(failures).isEmpty();
    } catch(Exception|AssertionError error) {failures.add("Run failed: "+error.getClass().getSimpleName());throw error;}
    finally {
      var evidence=new LinkedHashMap<String,Object>();
      evidence.put("startedAt",startedAt);evidence.put("endedAt",Instant.now().toString());evidence.put("completed",completed);
      evidence.put("passed",completed && sessionsRemoved && failures.isEmpty());evidence.put("failures",failures);
      evidence.put("requests",requests);evidence.put("cycles",cycles);evidence.put("relayPort",relayPort);evidence.put("upstreamPort",13307);
      evidence.put("forwardedConnections",forwarded);evidence.put("redisRelayPort",redisRelayPort);evidence.put("redisUpstreamPort",16380);
      evidence.put("redisForwardedConnections",redisForwarded);evidence.put("ownedSessionsRemoved",sessionsRemoved);evidence.put("redisDatabase",15);
      evidence.put("candidateSha256",System.getenv("HTTP_CANDIDATE_SHA256"));evidence.put("candidateRevision",System.getenv("HTTP_CANDIDATE_REVISION"));
      evidence.put("aiEnabled",false);evidence.put("networkHttp",true);
      evidence.put("limits",List.of("Loopback HTTP, frozen separate JVM, real Redis sessions and authentication interceptor; synthetic account only",
          "Only owned relay sockets cut; MySQL remains alive; no server crash, blackhole, lost COMMIT acknowledgment or mid-transaction HTTP claim",
          "Pool max 2/min 0/acquire 1000ms/validate 500ms; driver connect 1000ms/socket 2000ms; test bounds, not production SLA",
          "Only recovery GETs poll; no automatic write retry; deliberate stale PUT must return 409",
          "Database and Redis outages cut only owned relay sockets; upstream services remain alive",
          "Own sessions and lease removed; rate counters expire naturally; no Redis flush or existing database changes"));
      mapper.writerWithDefaultPrettyPrinter().writeValue(directory.resolve("http-database-outage.json").toFile(),evidence);
      System.out.println("HTTP DATABASE OUTAGE evidence: "+directory.resolve("http-database-outage.json"));
    }
  }
  private static Long compareDelete(org.springframework.data.redis.core.StringRedisTemplate redis,String key,String value) {
    return redis.execute(new DefaultRedisScript<>("if redis.call('GET',KEYS[1]) == ARGV[1] then return redis.call('DEL',KEYS[1]) else return 0 end",Long.class),List.of(key),value);
  }
  private String loginBody() throws Exception {return mapper.writeValueAsString(Map.of("username","http_outage","password",loginPassword));}
  String updateBody(String nickname,long version) throws Exception {
    // contact must be present even when null; preserve the production request contract.
    return mapper.writeValueAsString(new cn.edu.lostfound.identity.AccountDtos.UpdateMe(nickname,null,version));
  }
  static void assertExpectedStatus(int actual,int... expected) {assertThat(expected).contains(actual);}
  private String login(HttpClient client,String phase) throws Exception {
    String token=request(client,phase,"POST","/api/auth/login",null,loginBody(),200).path("data").path("token").asText();
    assertThat(token).matches("[a-f0-9]{32}");tokens.add(token);return token;
  }
  private JsonNode request(HttpClient client,String phase,String method,String path,String token,String body,int... expected) throws Exception {
    var builder=HttpRequest.newBuilder(URI.create(origin+path)).timeout(Duration.ofSeconds(6));
    if(token!=null)builder.header("X-Token",token);if(body!=null)builder.header("Content-Type","application/json");
    long start=System.nanoTime();
    HttpResponse<String> response;
    try {
      response=client.send(builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    } catch(java.io.IOException|InterruptedException error) {
      requests.add(Map.of("phase",phase,"exceptionType",error.getClass().getSimpleName(),"elapsedMs",(System.nanoTime()-start)/1_000_000));
      if(error instanceof InterruptedException)Thread.currentThread().interrupt();
      throw error;
    }
    long elapsed=(System.nanoTime()-start)/1_000_000;
    requests.add(Map.of("phase",phase,"status",response.statusCode(),"elapsedMs",elapsed));
    assertExpectedStatus(response.statusCode(),expected);assertThat(elapsed).isLessThan(6000);
    assertThat(response.headers().firstValue("cache-control").orElse("")).contains("no-store");
    assertThat(response.headers().firstValue("content-type").orElse("")).contains("application/json");
    var envelope=mapper.readTree(response.body());assertThat(envelope.path("code").asInt()).isEqualTo(response.statusCode()==200?0:-1);
    if(response.statusCode()!=200) {
      assertThat(envelope.path("traceId").asText()).isNotBlank();assertThat(envelope.path("data").isNull()).isTrue();
      assertThat(response.body()).doesNotContain("jdbc:","13307","rehearsal_","SQL","Before outage","Recovered",databasePassword,loginPassword);
      if(response.statusCode()==503)assertThat(envelope.path("errorCode").asText()).isEqualTo("SERVICE_UNAVAILABLE");
    }
    return envelope;
  }
  private void assertHealth(HttpClient client,String phase,String path,int expected,String status) throws Exception {
    long start=System.nanoTime();
    HttpResponse<String> response=client.send(HttpRequest.newBuilder(URI.create(origin+path)).timeout(Duration.ofSeconds(6))
        .header("Accept","application/json").GET().build(),HttpResponse.BodyHandlers.ofString());
    long elapsed=(System.nanoTime()-start)/1_000_000;
    requests.add(Map.of("phase",phase,"status",response.statusCode(),"elapsedMs",elapsed));
    assertThat(response.statusCode()).isEqualTo(expected);assertThat(elapsed).isLessThan(6000);
    assertThat(response.headers().firstValue("cache-control").orElse("")).contains("no-store");
    assertThat(response.headers().firstValue("content-type").orElse("")).contains("application/json");
    JsonNode body=mapper.readTree(response.body());
    assertThat(body.size()).isEqualTo(1);assertThat(body.path("status").asText()).isEqualTo(status);
    assertThat(response.body()).doesNotContain("jdbc:","13307","rehearsal_","SQL",databasePassword,loginPassword);
  }
  private static void assertProfile(JsonNode envelope,String nickname,long version) {
    assertThat(envelope.path("code").asInt()).isZero();var data=envelope.path("data");
    assertThat(data.path("nickname").asText()).isEqualTo(nickname);assertThat(data.path("version").asLong()).isEqualTo(version);
    assertThat(data.has("password")).isFalse();
  }
  private static void assertPublicConfig(JsonNode envelope) {
    assertThat(envelope.path("data").path("isTest").asBoolean()).isTrue();
    assertThat(envelope.path("data").path("aiEnabled").asBoolean()).isFalse();
  }
  private static void assertStored(JdbcTemplate jdbc,String nickname,long version) {
    assertThat(jdbc.queryForMap("SELECT nickname,row_version AS version FROM users WHERE id=1"))
        .containsEntry("nickname",nickname).containsEntry("version",version);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM business_logs",Long.class)).isZero();
  }
  private Map<String,Object> configuration(String url,Path directory,int redisPort) {
    var c=new LinkedHashMap<String,Object>();c.put("server.address","127.0.0.1");c.put("server.port",0);
    c.put("spring.datasource.url",url);c.put("spring.datasource.username","rehearsal_runner");c.put("spring.datasource.password",databasePassword);
    c.put("spring.datasource.hikari.maximum-pool-size",2);c.put("spring.datasource.hikari.minimum-idle",0);
    c.put("spring.datasource.hikari.connection-timeout",1000);c.put("spring.datasource.hikari.validation-timeout",500);
    c.put("spring.flyway.enabled",false);c.put("spring.sql.init.mode","never");c.put("spring.jpa.hibernate.ddl-auto","validate");c.put("spring.jpa.generate-ddl",false);
    c.put("spring.data.redis.host","127.0.0.1");c.put("spring.data.redis.port",redisPort);c.put("spring.data.redis.database",15);
    c.put("spring.data.redis.username","");c.put("spring.data.redis.password","");
    c.put("app.campus.test-mode",true);c.put("app.campus.campus-id","TEST_CAMPUS");c.put("app.admin-password","");
    c.put("app.media.root",directory.resolve("http-outage-media").toString());c.put("app.media.cleanup-enabled",false);c.put("ai.enabled",false);
    return c;
  }
}
