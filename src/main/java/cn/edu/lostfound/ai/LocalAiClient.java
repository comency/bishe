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
  public LocalAiClient(AiProperties config,AiResources resources,ObjectMapper mapper){
    this.config=config;this.resources=resources;this.mapper=mapper;
    this.policy=new AiContentPolicy(mapper);
    http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER)
      .proxy(new ProxySelector(){public List<Proxy> select(URI uri){return List.of(Proxy.NO_PROXY);}public void connectFailed(URI uri,SocketAddress address,IOException error){}}).build();
  }
  @PreDestroy public void close(){http.shutdownNow();}
  public AiDtos.Result generate(String input,boolean polish){
    if(!config.enabled())return AiDtos.Result.unavailable("DISABLED");
    if(polish&&policy.blockedPolishInput(input))return AiDtos.Result.unavailable("EMPTY_RESULT");
    String system=policy.system(polish);
    // Conservative UTF-8 token budget; never silently truncate input.
    if(system.getBytes(StandardCharsets.UTF_8).length+input.getBytes(StandardCharsets.UTF_8).length+config.outputTokens()+128>config.contextTokens()||
        !resources.available(config.minimumFreeBytes()))return AiDtos.Result.unavailable("RESOURCE_LIMIT");
    if(!slot.tryAcquire())return AiDtos.Result.unavailable("BUSY");
    CompletableFuture<HttpResponse<byte[]>> pending=null;
    try {
      var body=Map.of("model",config.model(),"messages",List.of(Map.of("role","system","content",system),Map.of("role","user","content",input)),
          "stream",false,"think",false,"keep_alive",0,"options",Map.of("temperature",0.2,"num_ctx",config.contextTokens(),"num_predict",config.outputTokens()));
      var request=HttpRequest.newBuilder(config.baseUrl().resolve("/api/chat")).timeout(Duration.ofMillis(config.timeoutMs()))
          .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build();
      pending=http.sendAsync(request,info->new LimitedBody());
      // Deadline covers the entire body, not just connection/headers.
      var response=pending.get(config.timeoutMs(),TimeUnit.MILLISECONDS);
      if(response.statusCode()==429||response.statusCode()==503)return AiDtos.Result.unavailable("BUSY");
      if(response.statusCode()!=200)return AiDtos.Result.unavailable("UPSTREAM_ERROR");
      var root=mapper.readTree(response.body());
      if(root==null||root.has("error")||!root.path("done").asBoolean()||root.path("message").has("tool_calls")||
          !root.path("message").path("role").asText().equals("assistant"))return AiDtos.Result.unavailable("UPSTREAM_ERROR");
      if(root.path("done_reason").asText().equals("length"))return AiDtos.Result.unavailable("RESOURCE_LIMIT");
      var node=root.path("message").path("content");
      if(!node.isTextual()||node.asText().isBlank())return AiDtos.Result.unavailable("EMPTY_RESULT");
      String content=node.asText().strip();
      if(content.length()>(polish?3000:12000)||content.contains("<think>")||content.contains("</think>"))return AiDtos.Result.unavailable("UPSTREAM_ERROR");
      if(!policy.accepts(input,content,polish))return AiDtos.Result.unavailable("EMPTY_RESULT");
      return new AiDtos.Result(content,"GENERATED",null);
    } catch(TimeoutException e){return AiDtos.Result.unavailable("TIMEOUT");}
    catch(InterruptedException e){Thread.currentThread().interrupt();return AiDtos.Result.unavailable("TIMEOUT");}
    catch(ExecutionException e){return AiDtos.Result.unavailable(e.getCause() instanceof HttpTimeoutException?"TIMEOUT":"UPSTREAM_ERROR");}
    catch(Exception e){return AiDtos.Result.unavailable("UPSTREAM_ERROR");}
    finally {if(pending!=null&&!pending.isDone())pending.cancel(true);slot.release();}
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
