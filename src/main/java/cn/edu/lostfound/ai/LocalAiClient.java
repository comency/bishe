package cn.edu.lostfound.ai;

import cn.edu.lostfound.dto.AiDtos;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.stereotype.Component;

/** Ollama native chat. No repositories, prompts in logs, cloud keys, tools or conversation storage. */
@Component
public class LocalAiClient {
  private final AiProperties config;
  private final AiResources resources;
  private final ObjectMapper mapper;
  private final Semaphore slot=new Semaphore(1);
  private final HttpClient http;
  private final AiContentPolicy policy;
  // Accessed only while holding slot. A just-finished keep_alive=0 call may
  // return before the local runner releases host memory; never queue callers.
  private boolean hasProviderCompletion;
  private long providerCompletedAt;
  public LocalAiClient(AiProperties config,AiResources resources,ObjectMapper mapper){
    this.config=config;this.resources=resources;this.mapper=mapper;
    this.policy=new AiContentPolicy(mapper);
    http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER)
      .proxy(new ProxySelector(){public List<Proxy> select(URI uri){return List.of(Proxy.NO_PROXY);}public void connectFailed(URI uri,SocketAddress address,IOException error){}}).build();
  }
  @PreDestroy public void close(){http.shutdownNow();}
  public AiDtos.Result generate(String input,boolean polish){
    long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(config.timeoutMs());
    if(!config.enabled())return AiDtos.Result.unavailable("DISABLED");
    if(polish&&policy.blockedPolishInput(input))return AiDtos.Result.unavailable("EMPTY_RESULT");
    if(!polish&&policy.blockedChatInput(input))return AiDtos.Result.unavailable("EMPTY_RESULT");
    String system=policy.system(polish);
    // Conservative UTF-8 token budget; never silently truncate input.
    if(system.getBytes(StandardCharsets.UTF_8).length+input.getBytes(StandardCharsets.UTF_8).length+config.outputTokens()+128>config.contextTokens())return AiDtos.Result.unavailable("RESOURCE_LIMIT");
    if(!slot.tryAcquire())return AiDtos.Result.unavailable("BUSY");
    CompletableFuture<HttpResponse<byte[]>> pending=null;
    try {
      if(!resourcesReady(deadline))return AiDtos.Result.unavailable(System.nanoTime()>=deadline?"TIMEOUT":"RESOURCE_LIMIT");
      var body=new LinkedHashMap<String,Object>();
      body.put("model",config.model());
      body.put("messages",List.of(Map.of("role","system","content",system),Map.of("role","user","content",input)));
      body.put("stream",false);body.put("think",false);body.put("keep_alive",0);
      body.put("options",Map.of("temperature",polish?0.2:0.0,"num_ctx",config.contextTokens(),"num_predict",config.outputTokens()));
      if(!polish)body.put("format",policy.guidanceFormat());
      long remaining=deadline-System.nanoTime();
      if(remaining<=0)return AiDtos.Result.unavailable("TIMEOUT");
      var request=HttpRequest.newBuilder(config.baseUrl().resolve("/api/chat")).timeout(Duration.ofNanos(remaining))
          .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build();
      hasProviderCompletion=false;
      pending=http.sendAsync(request,info->new LimitedBody());
      // Deadline covers the entire body, not just connection/headers.
      remaining=deadline-System.nanoTime();
      if(remaining<=0)throw new TimeoutException();
      var response=pending.get(remaining,TimeUnit.NANOSECONDS);
      hasProviderCompletion=true;providerCompletedAt=System.nanoTime();
      if(response.statusCode()==429||response.statusCode()==503)return AiDtos.Result.unavailable("BUSY");
      if(response.statusCode()!=200)return AiDtos.Result.unavailable("UPSTREAM_ERROR");
      var root=mapper.readTree(response.body());
      if(root==null||root.has("error")||!root.path("done").asBoolean()||root.path("message").has("tool_calls")||
          !root.path("message").path("role").asText().equals("assistant"))return AiDtos.Result.unavailable("UPSTREAM_ERROR");
      if(root.path("done_reason").asText().equals("length"))return AiDtos.Result.unavailable("RESOURCE_LIMIT");
      if(!root.path("done_reason").asText().equals("stop"))return AiDtos.Result.unavailable("UPSTREAM_ERROR");
      var node=root.path("message").path("content");
      if(!node.isTextual()||node.asText().isBlank())return AiDtos.Result.unavailable("EMPTY_RESULT");
      String content=node.asText().strip();
      if(content.length()>(polish?3000:12000)||content.contains("<think>")||content.contains("</think>"))return AiDtos.Result.unavailable("UPSTREAM_ERROR");
      if(!polish)content=policy.decodeGuidance(content);
      if(!policy.accepts(input,content,polish))return AiDtos.Result.unavailable("EMPTY_RESULT");
      return new AiDtos.Result(content,"GENERATED",null);
    } catch(TimeoutException e){return AiDtos.Result.unavailable("TIMEOUT");}
    catch(InterruptedException e){Thread.currentThread().interrupt();return AiDtos.Result.unavailable("TIMEOUT");}
    catch(ExecutionException e){return AiDtos.Result.unavailable(e.getCause() instanceof HttpTimeoutException?"TIMEOUT":"UPSTREAM_ERROR");}
    catch(Exception e){return AiDtos.Result.unavailable("UPSTREAM_ERROR");}
    finally {if(pending!=null&&!pending.isDone())pending.cancel(true);slot.release();}
  }
  private boolean resourcesReady(long deadline)throws InterruptedException{
    if(resources.available(config.minimumFreeBytes()))return true;
    // No grace for cold-start low memory, sustained pressure, or upstream timeouts.
    if(!hasProviderCompletion||System.nanoTime()-providerCompletedAt>=TimeUnit.SECONDS.toNanos(1))return false;
    long end=System.nanoTime()+Math.min(TimeUnit.MILLISECONDS.toNanos(250),Math.max(0,deadline-System.nanoTime()));
    while(System.nanoTime()<end){
      TimeUnit.NANOSECONDS.sleep(Math.min(TimeUnit.MILLISECONDS.toNanos(25),Math.max(0,end-System.nanoTime())));
      if(System.nanoTime()>=end||System.nanoTime()>=deadline)return false;
      if(resources.available(config.minimumFreeBytes()))return true;
    }
    return false;
  }
  private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
    private final HttpResponse.BodySubscriber<byte[]> delegate=HttpResponse.BodySubscribers.ofByteArray();
    private Flow.Subscription subscription;
    private long bytes;
    public CompletionStage<byte[]> getBody(){return delegate.getBody();}
    public void onSubscribe(Flow.Subscription s){subscription=s;delegate.onSubscribe(s);}
    public void onNext(List<ByteBuffer> buffers){
      for(var buffer:buffers)bytes+=buffer.remaining();
      if(bytes>65536){subscription.cancel();delegate.onError(new IOException("AI response limit"));}else delegate.onNext(buffers);
    }
    public void onError(Throwable error){delegate.onError(error);}
    public void onComplete(){delegate.onComplete();}
  }
}
