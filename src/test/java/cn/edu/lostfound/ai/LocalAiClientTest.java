package cn.edu.lostfound.ai;

import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Loopback stub validates transport only, never represents actual model quality. */
class LocalAiClientTest {
  HttpServer server;
  ExecutorService executor;
  LocalAiClient client;
  AiResources resources=mock(AiResources.class);
  ObjectMapper mapper=new ObjectMapper();
  AtomicInteger hits=new AtomicInteger();
  AtomicReference<JsonNode> request=new AtomicReference<>();
  volatile String response="{\"done\":true,\"done_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":\"合成测试建议\"}}";
  volatile int status=200;
  volatile boolean authHeader,stallBody;
  volatile CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(0);
  @BeforeEach void setup()throws Exception{
    when(resources.available(anyLong())).thenReturn(true);
    server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);executor=Executors.newCachedThreadPool();server.setExecutor(executor);
    server.createContext("/api/chat",e->{
      try {
        hits.incrementAndGet();authHeader=e.getRequestHeaders().containsKey("Authorization");request.set(mapper.readTree(e.getRequestBody()));entered.countDown();release.await(3,TimeUnit.SECONDS);
        byte[] bytes=response.getBytes(StandardCharsets.UTF_8);e.getResponseHeaders().set("Content-Type","application/json");e.getResponseHeaders().set("Location","http://127.0.0.1:"+server.getAddress().getPort()+"/forbidden");e.sendResponseHeaders(status,bytes.length);
        if(stallBody){e.getResponseBody().write(bytes,0,1);e.getResponseBody().flush();Thread.sleep(700);e.getResponseBody().write(bytes,1,bytes.length-1);}else e.getResponseBody().write(bytes);
      }catch(InterruptedException ex){Thread.currentThread().interrupt();}finally{e.close();}
    });server.createContext("/forbidden",e->{hits.addAndGet(1000);e.close();});server.start();client=create(true,1500);
  }
  LocalAiClient create(boolean enabled,int timeout){return new LocalAiClient(new AiProperties(enabled,URI.create("http://127.0.0.1:"+server.getAddress().getPort()),"qwen3:1.7b",timeout,4096,512,1),resources,mapper);}
  @AfterEach void close(){release.countDown();client.close();server.stop(0);executor.shutdownNow();}
  @Test void disabledNeverCallsProviderOrResources(){client.close();client=create(false,1500);assertThat(client.generate("synthetic",true).reason()).isEqualTo("DISABLED");assertThat(hits.get()).isZero();verifyNoInteractions(resources);}
  @Test void lowMemoryDoesNotCallProvider(){when(resources.available(anyLong())).thenReturn(false);assertThat(client.generate("synthetic",true).reason()).isEqualTo("RESOURCE_LIMIT");assertThat(hits.get()).isZero();}
  @Test void tooLargeContextIsNotTruncated(){assertThat(client.generate("测".repeat(3000),true).reason()).isEqualTo("RESOURCE_LIMIT");assertThat(hits.get()).isZero();}
  @Test void localNativeRequestHasNoKeysToolsOrPrivateContext(){
    var result=client.generate("synthetic original",true);assertThat(result.status()).isEqualTo("GENERATED");assertThat(result.reason()).isNull();assertThat(result.content()).isEqualTo("合成测试建议");
    assertThat(authHeader).isFalse();var r=request.get();assertThat(r.path("messages").size()).isEqualTo(2);assertThat(r.path("messages").get(1).path("content").asText()).isEqualTo("synthetic original");
    assertThat(r.path("stream").asBoolean()).isFalse();assertThat(r.path("think").asBoolean()).isFalse();assertThat(r.path("keep_alive").asInt()).isZero();assertThat(r.has("tools")).isFalse();assertThat(r.path("options").path("num_predict").asInt()).isEqualTo(512);
  }
  @Test void simultaneousRequestIsRejectedWithoutQueue()throws Exception{
    release=new CountDownLatch(1);try(var pool=Executors.newSingleThreadExecutor()){
      var first=pool.submit(()->client.generate("first",false));assertThat(entered.await(1,TimeUnit.SECONDS)).isTrue();assertThat(client.generate("second",false).reason()).isEqualTo("BUSY");release.countDown();assertThat(first.get(2,TimeUnit.SECONDS).status()).isEqualTo("GENERATED");assertThat(hits.get()).isEqualTo(1);
    }
  }
  @Test void wholeBodyTimeoutAndSubsequentRequestRecovers(){client.close();client=create(true,200);stallBody=true;long before=System.nanoTime();assertThat(client.generate("synthetic",true).reason()).isEqualTo("TIMEOUT");assertThat((System.nanoTime()-before)/1_000_000).isLessThan(1500);stallBody=false;assertThat(client.generate("retry",true).status()).isEqualTo("GENERATED");}
  @Test void oversizedResponseFailsWithoutEchoingBody(){response="x".repeat(70000);assertThat(client.generate("synthetic",false).reason()).isEqualTo("UPSTREAM_ERROR");}
  @Test void redirectsAreNotFollowed(){status=302;assertThat(client.generate("synthetic",false).reason()).isEqualTo("UPSTREAM_ERROR");assertThat(hits.get()).isEqualTo(1);}
  @ParameterizedTest @ValueSource(ints={429,503}) void providerCapacityMapsToBusy(int code){status=code;assertThat(client.generate("synthetic",false).reason()).isEqualTo("BUSY");}
  @ParameterizedTest @ValueSource(strings={"{}","not-json","null","{\"error\":\"PRIVATE_UPSTREAM_ERROR\"}"}) void invalidResultsAreNotSuccessful(String body){response=body;var r=client.generate("synthetic",false);assertThat(r.reason()).isEqualTo("UPSTREAM_ERROR");assertThat(r.content()).doesNotContain("PRIVATE_UPSTREAM_ERROR");}
  @Test void blankResultIsExplicit(){response=response.replace("合成测试建议","   ");assertThat(client.generate("synthetic",true).reason()).isEqualTo("EMPTY_RESULT");}
  @Test void truncatedOutputIsNeverOfferedAsCompletedPolish(){response=response.replace("stop","length");assertThat(client.generate("synthetic",true).reason()).isEqualTo("RESOURCE_LIMIT");}
  @Test void reasoningIsNotExposed(){response=response.replace("合成测试建议","<think>private reasoning</think>");assertThat(client.generate("synthetic",true).reason()).isEqualTo("UPSTREAM_ERROR");}
  @Test void plainTextMayContainMarkupButNeverExecutes(){response=response.replace("合成测试建议","<img src=x onerror=alert(1)>");assertThat(client.generate("synthetic",true).content()).contains("<img");}
  @ParameterizedTest @ValueSource(strings={"https://example.com:443","http://localhost:11434","http://127.0.0.1:11434/v1","http://user:secret@127.0.0.1:11434","http://127.0.0.1:11434?x=1"})
  void refusesExternalOrAmbiguousEndpoints(String uri){assertThatThrownBy(()->new AiProperties(true,URI.create(uri),"qwen3:1.7b",1000,4096,512,1)).isInstanceOf(IllegalArgumentException.class);}
}
