package cn.edu.lostfound.config;

import cn.edu.lostfound.LostFoundApplication;
import com.fasterxml.jackson.databind.*;
import com.sun.management.OperatingSystemMXBean;
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.stream.IntStream;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import static org.assertj.core.api.Assertions.*;

/** Opt-in real loopback HTTP, real authentication and Redis DB15, owned MySQL13307. No model. */
@EnabledIfEnvironmentVariable(named="RUN_HTTP_BENCHMARK", matches="true")
class HttpLoadBenchmarkTest {
  private static final int WORKERS=20, SECONDS=30;
  private static final String LEASE="http-benchmark:lease";
  private static final String[] CATEGORIES={"books","digital","daily","other"};
  private final ObjectMapper mapper=new ObjectMapper();
  private final List<Map<String,Object>> phases=new ArrayList<>();
  private final List<String> tokens=new ArrayList<>(), failures=new ArrayList<>();
  private final AtomicLong minimumFree=new AtomicLong(Long.MAX_VALUE);
  private final boolean richData=Boolean.parseBoolean(System.getenv("RUN_HTTP_RICH_DATA"));
  private final boolean packaged=System.getenv("HTTP_CANDIDATE_JAR")!=null && !System.getenv("HTTP_CANDIDATE_JAR").isBlank();
  private final Queue<Map<String,Object>> resourceSamples=new ConcurrentLinkedQueue<>();
  private String origin;
  private int port;

  @Test void fourBoundedPhasesOfAuthenticatedPagination() throws Exception {
    String prefix=System.getenv("REHEARSAL_SCHEMA_PREFIX"), password=System.getenv("REHEARSAL_DB_PASSWORD");
    assertThat(prefix).matches("rehearsal_[0-9]{14}_[a-f0-9]{8}_");
    assertThat(password).matches("[a-f0-9]{48}");
    Path directory=Path.of(System.getenv("REHEARSAL_DIRECTORY")).toRealPath();
    assertThat(directory.startsWith(Path.of(".local/database-rehearsal").toRealPath())).isTrue();
    String startedAt=Instant.now().toString();
    var os=(OperatingSystemMXBean)ManagementFactory.getOperatingSystemMXBean();
    var sampler=Executors.newSingleThreadScheduledExecutor();
    sampler.scheduleAtFixedRate(()->{
      long free=os.getFreeMemorySize();minimumFree.accumulateAndGet(free,Math::min);
      resourceSamples.add(Map.of("at",Instant.now().toString(),"hostFreeBytes",free,
          "clientHeapUsedBytes",Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory()));
    },0,250,TimeUnit.MILLISECONDS);
    boolean completed=false, sessionsRemoved=false;
    try (var client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
        .followRedirects(HttpClient.Redirect.NEVER).version(HttpClient.Version.HTTP_1_1).build()) {
      String url="jdbc:mysql://127.0.0.1:13307/"+prefix+"httpbenchmark?useUnicode=true&characterEncoding=utf8&serverTimezone=UTC&connectTimeout=3000&socketTimeout=10000";
      var source=new DriverManagerDataSource(url,"rehearsal_runner",password); var jdbc=new JdbcTemplate(source);
      var identity=jdbc.queryForMap("SELECT @@port p,@@server_uuid u,@@datadir d");
      assertThat(((Number)identity.get("p")).intValue()).isEqualTo(13307);
      assertThat(identity.get("u")).isEqualTo(System.getenv("REHEARSAL_SERVER_UUID"));
      assertThat(Path.of(identity.get("d").toString()).toRealPath()).isEqualTo(directory.resolve("data").toRealPath());
      assertThat(jdbc.queryForList("SHOW TABLES",String.class)).isEmpty();
      Flyway.configure().dataSource(source).locations("classpath:db/migration").cleanDisabled(true).baselineOnMigrate(false).load().migrate();
      String loginPassword=UUID.randomUUID().toString(); seed(jdbc,source,loginPassword);
      if(richData) seedRelations(jdbc);
      var config=configuration(url,password,directory);
      try(var target=openTarget(config,directory)) {
        port=target.port(); origin="http://127.0.0.1:"+port;
        var redis=target.redis();
        boolean leaseOwned=false;
        try {
          // No flush. Refuse any pre-existing DB15 usage, including previous unexpired counters.
          try(var connection=Objects.requireNonNull(redis.getConnectionFactory()).getConnection()) {
            assertThat(connection.serverCommands().dbSize()).isZero();
          }
          assertThat(redis.opsForValue().setIfAbsent(LEASE,prefix,Duration.ofMinutes(10))).isTrue(); leaseOwned=true;
          var publicConfig=request(client,"GET","/api/public/config",null,null,200);
          assertThat(publicConfig.path("isTest").asBoolean()).isTrue();
          assertThat(publicConfig.path("aiEnabled").asBoolean()).isFalse();
          request(client,"GET","/api/items/page",null,null,401);
          for(int i=1;i<=WORKERS;i++) {
            var login=request(client,"POST","/api/auth/login",null,
                mapper.writeValueAsString(Map.of("username","http_bench_"+i,"password",loginPassword)),200);
            String token=login.path("token").asText(); assertThat(token).matches("[a-f0-9]{32}"); tokens.add(token);
            assertThat(login.path("userId").asLong()).isEqualTo(i);
            assertThat(redis.opsForValue().get("session:"+token)).isEqualTo(Integer.toString(i));
          }
          var publicIds=IntStream.iterate(10000,i->i>0,i->i-1).filter(i->i%5!=0).boxed().toList();
          var filteredIds=publicIds.stream().filter(i->i%2==0 && category(i).equals("digital")).toList();
          assertThat(publicIds).hasSize(8000); assertThat(filteredIds).hasSize(1000);
          long first=System.nanoTime(); assertPage(client,tokens.getFirst(),10,1,"",publicIds);
          phases.add(Map.of("name","first-pagination-after-seed-and-login","elapsedMicros",(System.nanoTime()-first)/1000,"notOsDiskCold",true));
          runPhase(client,"public-default",10,1,"",publicIds);
          runPhase(client,"public-maximum",50,1,"",publicIds);
          runPhase(client,"public-deep",10,701,"",publicIds);
          runPhase(client,"keyword-type-category",10,1,"&keyword=synthetic&type=FOUND&category=digital",filteredIds);
          if(packaged){target.assertAlive();runPhase(client,"public-maximum-sustained",50,1,"",publicIds,180);}
          target.assertAlive();
          assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM items",Long.class)).isEqualTo(10000);
          assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM claims",Long.class)).isEqualTo(richData?8000L:0L);
          assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM item_images",Long.class)).isEqualTo(richData?15000L:0L);
          assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM media_files",Long.class)).isEqualTo(richData?15000L:0L);
          completed=true;
        } finally {
          // Delete only the exact sessions this run obtained through login, never other keys.
          for(String token:tokens) {
            try {
              request(client,"POST","/api/auth/logout",token,null,200);
              assertThat(redis.hasKey("session:"+token)).isFalse();
            } catch(Exception|AssertionError error) {
              failures.add("Owned logout failed: "+error.getClass().getSimpleName());
              if(leaseOwned) redis.delete("session:"+token);
            }
          }
          sessionsRemoved=tokens.stream().allMatch(token->Boolean.FALSE.equals(redis.hasKey("session:"+token)));
          if(leaseOwned) {
            Long removed=redis.execute(new DefaultRedisScript<>(
                "if redis.call('GET',KEYS[1]) == ARGV[1] then return redis.call('DEL',KEYS[1]) else return 0 end",Long.class),List.of(LEASE),prefix);
            assertThat(removed).isEqualTo(1);
          }
        }
      }
      assertThat(failures).as("All errors and failed latency gates retained in evidence").isEmpty();
    } catch(Exception|AssertionError error) {
      failures.add("Run failed: "+error.getClass().getSimpleName()); throw error;
    } finally {
      sampler.shutdownNow();
      var result=new LinkedHashMap<String,Object>();
      result.put("startedAt",startedAt);result.put("endedAt",Instant.now().toString());result.put("completed",completed);
      result.put("passed",completed && failures.isEmpty() && sessionsRemoved);result.put("failures",failures);result.put("phases",phases);
      result.put("itemCount",10000);result.put("approvedCount",8000);result.put("users",20);result.put("concurrency",20);
      result.put("phaseSeconds",SECONDS);result.put("connectionPoolMaximum",3);result.put("httpPort",port);result.put("redisDatabase",15);
      result.put("heapMaxBytes",Runtime.getRuntime().maxMemory());result.put("minimumFreeBytes",minimumFree.get());
      result.put("resourceSamples",resourceSamples);
      result.put("targetMode",packaged?"packaged-child-jvm":"in-process");
      if(packaged){
        result.put("candidateRevision",System.getenv("HTTP_CANDIDATE_REVISION"));
        result.put("candidateSha256",System.getenv("HTTP_CANDIDATE_SHA256"));
        result.put("serverHeapMaxBytes",384L*1024*1024);result.put("sustainedPhaseSeconds",180);
      }
      result.put("ownedSessionsRemoved",sessionsRemoved);result.put("aiEnabled",false);
      result.put("dataset",richData?"relations-v1":"baseline-v1");
      result.put("imageMetadataCount",richData?15000:0);result.put("claimCount",richData?8000:0);
      result.put("os",System.getProperty("os.name"));result.put("javaVersion",System.getProperty("java.version"));result.put("processors",os.getAvailableProcessors());
      result.put("limitations",List.of(packaged?"Verified frozen JAR and client in separate JVMs on one host; loopback only, not production network"
              :"Real loopback HTTP/auth/Redis/JSON/JPA, but client and server share test JVM; not packaged candidate or production network",
          "Closed-loop 20 workers, one request in flight each; not a fixed arrival-rate or soak test; no coordinated-omission correction",
          "Success percentiles include body parsing/semantic assertions; error rate includes failed requests; no retries",
          "Seeded/warmed MySQL buffer cache, no OS cache flush; paginated endpoint has no application result cache",
          richData?"20 synthetic publishers, 10000 items, 0-3 image metadata per item, 8000 claims; uniform timestamps; no image bytes or downloads; other distributions remain untested"
              :"20 synthetic publishers, 10000 items, no images or claims, uniform timestamps; other distributions remain untested",
          "AI disabled; ordinary test service may remain running; mall stopped; not AI-parallel or full production NFR acceptance"));
      mapper.writerWithDefaultPrettyPrinter().writeValue(directory.resolve("http-benchmark.json").toFile(),result);
      System.out.println("HTTP BENCHMARK evidence: "+directory.resolve("http-benchmark.json"));
    }
  }

  private void runPhase(HttpClient client,String name,int size,int firstPage,String filter,List<Integer> ids) throws Exception {
    runPhase(client,name,size,firstPage,filter,ids,SECONDS);
  }
  private void runPhase(HttpClient client,String name,int size,int firstPage,String filter,List<Integer> ids,int seconds) throws Exception {
    // Twenty sequential warmup calls per scenario, excluded from measured statistics.
    for(int i=0;i<WORKERS;i++) assertPage(client,tokens.get(i),size,firstPage+i%10,filter,ids);
    var successful=new ConcurrentLinkedQueue<Long>();var all=new ConcurrentLinkedQueue<Long>();
    var errors=new ConcurrentLinkedQueue<String>();var ready=new CountDownLatch(WORKERS);var start=new CountDownLatch(1);
    var slow=new ConcurrentLinkedQueue<Map<String,Object>>();var slowCount=new AtomicInteger();
    var deadline=new AtomicLong(); var pool=Executors.newFixedThreadPool(WORKERS);var jobs=new ArrayList<Future<?>>();
    long started=0;String at=Instant.now().toString();
    try {
      for(int w=0;w<WORKERS;w++) {
        final int worker=w;
        jobs.add(pool.submit(()->{
          ready.countDown();try{start.await();}catch(InterruptedException e){Thread.currentThread().interrupt();return;}
          for(int n=0;System.nanoTime()<deadline.get();n++) {
            long requestStart=System.nanoTime();boolean ok=false;
            try {assertPage(client,tokens.get(worker),size,firstPage+(worker+n)%10,filter,ids);ok=true;}
            catch(Exception|AssertionError error) {if(errors.size()<100)errors.add(error.getClass().getSimpleName());}
            finally {
              long elapsed=(System.nanoTime()-requestStart)/1000;all.add(elapsed);if(ok)successful.add(elapsed);
              if(elapsed>800_000 && slowCount.getAndIncrement()<200)
                slow.add(Map.of("endedAt",Instant.now().toString(),"elapsedMicros",elapsed,"worker",worker,"success",ok));
            }
          }
        }));
      }
      assertThat(ready.await(5,TimeUnit.SECONDS)).isTrue();started=System.nanoTime();at=Instant.now().toString();
      deadline.set(started+TimeUnit.SECONDS.toNanos(seconds));start.countDown();
      // One shared join deadline, not 20 independent long waits.
      long joinDeadline=deadline.get()+TimeUnit.SECONDS.toNanos(15);
      for(var job:jobs)job.get(Math.max(1,joinDeadline-System.nanoTime()),TimeUnit.NANOSECONDS);
    } finally {
      deadline.set(0);start.countDown();pool.shutdownNow();
      if(!pool.awaitTermination(12,TimeUnit.SECONDS))failures.add(name+": worker shutdown not confirmed");
      long wall=started==0?1:System.nanoTime()-started;
      var phase=new LinkedHashMap<>(BenchmarkMetrics.summarize(new ArrayList<>(successful),all.size(),wall));
      phase.put("name",name);phase.put("startedAt",at);phase.put("endedAt",Instant.now().toString());
      phase.put("wallMs",wall/1_000_000);phase.put("scheduledLoadSeconds",seconds);phase.put("pageSize",size);phase.put("firstPage",firstPage);
      phase.put("over800ms",slowCount.get());phase.put("slowRequestsFirst200",slow);
      phase.put("errorKindsFirst100",new ArrayList<>(errors));phase.put("allAttemptLatency",BenchmarkMetrics.summarize(new ArrayList<>(all),all.size(),wall));
      phases.add(phase);
      if(!Boolean.TRUE.equals(phase.get("passed")))failures.add(name+": nonzero errors or P95 above 800ms or no samples");
      System.out.println("HTTP PHASE "+name+": "+phase.get("attempts")+" calls; p95="+phase.get("p95")+"us; errors="+phase.get("errors"));
    }
  }

  private JsonNode request(HttpClient client,String method,String path,String token,String body,int expected) throws Exception {
    var builder=HttpRequest.newBuilder(URI.create(origin+path)).timeout(Duration.ofSeconds(10));
    if(token!=null)builder.header("X-Token",token);
    if(body!=null)builder.header("Content-Type","application/json");
    var response=client.send(builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).as("HTTP status on %s",path).isEqualTo(expected);
    assertThat(response.headers().firstValue("cache-control").orElse("")).contains("no-store");
    assertThat(response.headers().firstValue("content-type").orElse("")).contains("application/json");
    var envelope=mapper.readTree(response.body());assertThat(envelope.path("code").asInt()).isEqualTo(expected==200?0:-1);
    return envelope.path("data");
  }

  private void assertPage(HttpClient client,String token,int size,int page,String filter,List<Integer> ids) throws Exception {
    int actor=tokens.indexOf(token)+1;
    assertThat(actor).isBetween(1,WORKERS);
    var result=request(client,"GET","/api/items/page?page="+page+"&pageSize="+size+filter,token,null,200);
    assertThat(result.path("total").asInt()).isEqualTo(ids.size());assertThat(result.path("records").size()).isEqualTo(size);
    assertThat(result.path("page").asInt()).isEqualTo(page);assertThat(result.path("pageSize").asInt()).isEqualTo(size);
    for(int i=0;i<size;i++) {
      var item=result.path("records").get(i);int id=ids.get((page-1)*size+i);
      assertThat(item.path("id").asInt()).isEqualTo(id);assertThat(item.path("status").asText()).isEqualTo("APPROVED");
      assertThat(item.path("title").asText()).isEqualTo("synthetic item "+id);
      assertThat(item.path("publisherId").asInt()).isEqualTo(1+((id-1)/500));
      assertThat(item.path("type").asText()).isEqualTo(id%2==0?"FOUND":"LOST");
      assertThat(item.path("category").asText()).isEqualTo(category(id));
      for(String field:List.of("description","contact","identification","internalNote","reviewReason","password",
          "evidence","applicantId","applicantContactSnapshot","publisherContactSnapshot","claims"))assertThat(item.has(field)).isFalse();
      assertThat(item.path("images").size()).isEqualTo(richData?imageCount(id):0);
      for(int n=0;n<item.path("images").size();n++) {
        var media=item.path("images").get(n);long mediaId=id*3L+imageCount(id)-n;
        assertThat(media.path("id").asLong()).isEqualTo(mediaId);
        assertThat(media.path("readPath").asText()).isEqualTo("/api/uploads/images/"+mediaId);
        assertThat(media.path("state").asText()).isEqualTo("BOUND");
        assertThat(media.path("mediaType").asText()).isEqualTo("image/png");
        assertThat(media.path("sizeBytes").asInt()).isEqualTo(68);
        assertThat(media.path("width").asInt()).isEqualTo(1);assertThat(media.path("height").asInt()).isEqualTo(1);
        for(String field:List.of("storageKey","storage_key","uploaderId","uploader_id","sha256","bytes"))assertThat(media.has(field)).isFalse();
      }
      assertThat(item.path("hasAcceptedClaim").asBoolean()).isEqualTo(richData && hasClaims(id) && claimState(id).equals("ACCEPTED"));
      Long mine=richData?ownClaim(id,actor):null;
      assertThat(item.has("myClaimId")).isTrue();
      if(mine==null)assertThat(item.path("myClaimId").isNull()).isTrue();
      else assertThat(item.path("myClaimId").asLong()).isEqualTo(mine);
    }
  }

  private static Map<String,Object> configuration(String url,String password,Path directory) {
    var c=new LinkedHashMap<String,Object>();
    c.put("server.address","127.0.0.1");c.put("server.port",0);
    c.put("spring.datasource.url",url);c.put("spring.datasource.username","rehearsal_runner");c.put("spring.datasource.password",password);
    c.put("spring.datasource.hikari.maximum-pool-size",3);c.put("spring.datasource.hikari.minimum-idle",1);c.put("spring.datasource.hikari.connection-timeout",10000);
    c.put("spring.flyway.enabled",false);c.put("spring.sql.init.mode","never");c.put("spring.jpa.hibernate.ddl-auto","validate");c.put("spring.jpa.generate-ddl",false);
    c.put("spring.data.redis.host","127.0.0.1");c.put("spring.data.redis.port",16380);c.put("spring.data.redis.database",15);
    c.put("spring.data.redis.username","");c.put("spring.data.redis.password","");
    c.put("app.campus.test-mode",true);c.put("app.campus.campus-id","TEST_CAMPUS");c.put("app.admin-password","");
    c.put("app.media.root",directory.resolve("http-media").toString());c.put("app.media.cleanup-enabled",false);c.put("ai.enabled",false);
    return c;
  }

  private interface Target extends AutoCloseable {
    int port();StringRedisTemplate redis();void assertAlive();
    @Override void close() throws Exception;
  }
  private Target openTarget(Map<String,Object> config,Path directory) throws Exception {
    if(packaged){
      var owned=PackagedBenchmarkTarget.start(config,directory);
      return new Target(){
        public int port(){return owned.port();}public StringRedisTemplate redis(){return owned.redis();}
        public void assertAlive(){owned.assertAlive();}public void close() throws Exception{owned.close();}
      };
    }
    var app=new SpringApplication(LostFoundApplication.class);app.setWebApplicationType(WebApplicationType.SERVLET);
    app.addInitializers(c->{c.getEnvironment().setActiveProfiles("httpbenchmark");
      c.getEnvironment().getPropertySources().addFirst(new MapPropertySource("owned-http-rehearsal",config));});
    var context=app.run();
    return new Target(){
      public int port(){return ((WebServerApplicationContext)context).getWebServer().getPort();}
      public StringRedisTemplate redis(){return context.getBean(StringRedisTemplate.class);}
      public void assertAlive(){assertThat(context.isActive()).isTrue();}public void close(){context.close();}
    };
  }

  private static String category(int id){return CATEGORIES[(id/2)%CATEGORIES.length];}
  static int imageCount(int id){return id%4;}
  static boolean hasClaims(int id){return id%2==0 && id%5!=0;}
  static int applicant(int id,int offset){return (((id-1)/500)+offset)%20+1;}
  static String claimState(int id){return new String[]{"APPLIED","ACCEPTED","REJECTED","CANCELLED"}[(id/2)%4];}
  static Long ownClaim(int id,int actor){
    if(!hasClaims(id))return null;
    if(actor==applicant(id,1))return id*2L-1;
    return actor==applicant(id,2)?id*2L:null;
  }

  /** Relational metadata only: this pagination test never reads image file contents. */
  private void seedRelations(JdbcTemplate jdbc) {
    for(int slot=1;slot<=3;slot++) {
      jdbc.update("""
          INSERT INTO media_files(id,uploader_id,storage_key,mime_type,size_bytes,width,height,sha256,lifecycle,created_at,updated_at)
          SELECT id*3+?,publisher_id,CONCAT(LPAD(HEX(id*3+?),32,'0'),'.bin'),'image/png',68,1,1,REPEAT('0',64),'BOUND',
          '2026-09-01','2026-09-01' FROM items WHERE MOD(id,4)>=?
          """,slot,slot,slot);
      // Reverse IDs to detect accidental sorting by media ID rather than display_order.
      jdbc.update("""
          INSERT INTO item_images(item_id,media_id,display_order,bound_at)
          SELECT id,id*3+?,MOD(id,4)-?+1,'2026-09-01' FROM items WHERE MOD(id,4)>=?
          """,slot,slot,slot);
    }
    for(int offset=1;offset<=2;offset++) {
      String state=offset==1?"ELT(MOD(FLOOR(id/2),4)+1,'APPLIED','ACCEPTED','REJECTED','CANCELLED')":"'REJECTED'";
      // SQL fragment is from the fixed two branches above, never request/environment text.
      jdbc.update("""
          INSERT INTO claims(id,item_id,publisher_id,applicant_id,item_title_snapshot,item_type_snapshot,
          item_content_version_snapshot,evidence,applicant_contact_snapshot,status,accepted_at,publisher_contact_snapshot,
          ended_at,end_reason_code,end_reason,created_at,updated_at)
          SELECT id*2-2+?,id,publisher_id,MOD(publisher_id-1+?,20)+1,title,'FOUND',1,
          'Synthetic private evidence','Synthetic applicant contact',s,
          IF(s='ACCEPTED','2026-09-02',NULL),IF(s='ACCEPTED','Synthetic publisher contact',NULL),
          IF(s IN ('REJECTED','CANCELLED'),'2026-09-03',NULL),
          IF(s IN ('REJECTED','CANCELLED'),'SYNTHETIC_END',NULL),
          IF(s IN ('REJECTED','CANCELLED'),'Synthetic ended claim',NULL),'2026-09-01','2026-09-03'
          FROM (SELECT id,publisher_id,title,%s s FROM items WHERE type='FOUND' AND status='APPROVED') fixture
          """.formatted(state),offset,offset);
    }
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM claims WHERE status='ACCEPTED'",Long.class)).isEqualTo(1000L);
  }
  private void seed(JdbcTemplate jdbc,DriverManagerDataSource source,String password) throws Exception {
    String hash=new BCryptPasswordEncoder().encode(password);
    for(int i=1;i<=21;i++) {
      jdbc.update("INSERT INTO users(id,username,password,nickname,role,is_test,created_at,updated_at) VALUES(?,?,?,?,?,TRUE,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",
          i,"http_bench_"+i,hash,"Synthetic "+i,i==21?"ADMIN":"USER");
      jdbc.update("INSERT INTO campus_verifications(user_id,campus_code,stored_status,created_at,updated_at) VALUES(?,'TEST_CAMPUS','UNVERIFIED',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",i);
    }
    for(int i=1;i<=20;i++) {
      jdbc.update("INSERT INTO verification_applications(id,user_id,application_version,campus_code,real_name,status,method,evidence_summary,valid_through,reviewed_by,reviewed_at,submitted_at,is_test) VALUES(?,?,1,'TEST_CAMPUS','Synthetic','VERIFIED','IN_PERSON','Synthetic only','2099-12-31',21,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),TRUE)",i,i);
      jdbc.update("UPDATE campus_verifications SET stored_status='VERIFIED',current_application_id=?,last_application_version=1,expires_at='2100-01-01' WHERE user_id=?",i,i);
    }
    try(var connection=source.getConnection();var statement=connection.prepareStatement("INSERT INTO items(id,publisher_id,title,description,type,category,location,status,created_at,updated_at,created_at_utc,updated_at_utc) VALUES(?,?,?,?,?,?,'Synthetic location',?,'2026-09-01','2026-09-01','2026-08-31 16:00:00','2026-08-31 16:00:00')")) {
      connection.setAutoCommit(false);
      for(int i=1;i<=10000;i++) {
        statement.setInt(1,i);statement.setInt(2,1+((i-1)/500));statement.setString(3,"synthetic item "+i);
        statement.setString(4,"Synthetic benchmark data without personal information. ".repeat(3));
        statement.setString(5,i%2==0?"FOUND":"LOST");statement.setString(6,category(i));statement.setString(7,i%5==0?"PENDING":"APPROVED");statement.addBatch();
        if(i%500==0)statement.executeBatch();
      }
      connection.commit();
    }
  }
}
