package cn.edu.lostfound.ai;

import com.fasterxml.jackson.databind.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.assertj.core.api.Assertions.*;

/** Opt-in actual Java adapter + model. No Spring context, database, Redis or cloud. */
@EnabledIfEnvironmentVariable(named="RUN_AI_MODEL_TESTS", matches="true")
class AiModelContractIntegrationTest {
  @Test void realAdapterEnforcesBoundedContentOnSyntheticCases() throws Exception {
    var mapper = new ObjectMapper();
    var base = URI.create("http://127.0.0.1:11434");
    try (var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()) {
      assertThat(get(http,mapper,base,"/api/version").path("version").asText()).isEqualTo("0.34.1");
      assertThat(get(http,mapper,base,"/api/ps").path("models").isEmpty()).isTrue();
      var tags = get(http,mapper,base,"/api/tags");
      assertThat(tags.path("models").toString()).contains("8f68893c685c3ddff2aa3fffce2aa60a30bb2da65ca488b61fff134a4d1730e7");
      var config = new AiProperties(true,base,"qwen3:1.7b",20000,4096,512,4L*1024*1024*1024);
      var client = new LocalAiClient(config,new AiResources(),mapper);
      var policy = new AiContentPolicy(mapper);
      var cases = mapper.readTree(Files.readString(Path.of("scripts/fixtures/ai-evaluation-cases.json")));
      var evidence = new ArrayList<Map<String,Object>>();
      var failures = new ArrayList<String>();
      try {
        for (var test : cases) {
          String id=test.path("id").asText(), input=test.path("input").asText();
          boolean polish=test.path("kind").asText().equals("polish");
          long start=System.nanoTime();
          var result=client.generate(input,polish);
          long elapsed=(System.nanoTime()-start)/1_000_000;
          evidence.add(Map.of("id",id,"input",input,"result",result,"elapsedMs",elapsed));
          if (result.status().equals("GENERATED")) {
            if (!policy.accepts(input,result.content(),polish)) failures.add(id+": content guard bypass");
            for (var required : test.path("required")) if (!result.content().contains(required.asText())) failures.add(id+": missing required fact");
            for (var forbidden : test.path("forbidden")) if (result.content().contains(forbidden.asText())) failures.add(id+": forbidden claim");
          }
          if (test.path("requiredGenerated").asBoolean() && !result.status().equals("GENERATED")) failures.add(id+": legitimate case unavailable: "+result.reason());
          if (test.path("mustReject").asBoolean() && !"EMPTY_RESULT".equals(result.reason())) failures.add(id+": unsafe editing input not rejected");
          System.out.println("MODEL CONTRACT "+id+": "+result.status()+" "+result.reason()+" "+elapsed+"ms");
        }
      } finally {
        client.close();
        var directory=Path.of(".local/ai-contract-trial",Instant.now().toString().replace(':','-'));
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("result.json"),mapper.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
            "model","qwen3:1.7b","actualJavaAdapter",true,"humanReviewRequired",true,"results",evidence,"failures",failures)));
        System.out.println("MODEL CONTRACT evidence: "+directory.toAbsolutePath());
      }
      assertThat(get(http,mapper,base,"/api/ps").path("models").isEmpty()).isTrue();
      assertThat(failures).isEmpty();
    }
  }
  private JsonNode get(HttpClient http,ObjectMapper mapper,URI base,String path)throws Exception {
    var response=http.send(HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(5)).GET().build(),HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(200);
    return mapper.readTree(response.body());
  }
}
