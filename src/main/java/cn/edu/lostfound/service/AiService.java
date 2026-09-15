package cn.edu.lostfound.service;

import com.fasterxml.jackson.databind.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.*;

@Service public class AiService {
  private static final Logger log = LoggerFactory.getLogger(AiService.class);
  private final boolean enabled; private final String key,model; private final RestClient client; private final ObjectMapper mapper;
  public AiService(@Value("${ai.enabled:false}") boolean enabled,@Value("${ai.base-url}") String url,@Value("${ai.api-key:}") String key,@Value("${ai.model}") String model,ObjectMapper mapper){this.enabled=enabled;this.key=key;this.model=model;this.mapper=mapper;this.client=RestClient.builder().baseUrl(url).build();}
  public String polish(String text){return ask("你是校园失物招领助手。将以下内容整理成简洁、客观的中文发布文案；不要编造信息，输出正文即可：\n"+text);}
  public String chat(String question){return ask("你是校园失物招领系统助手。仅回答发布、认领、查找物品相关问题，简洁中文回答。用户问题：\n"+question);}
  private String ask(String prompt){if(!enabled||key.isBlank())return "AI 服务尚未配置。请在 application.yml 设置 ai.enabled=true，并通过环境变量 AI_API_KEY 提供密钥。";try{Map<String,Object> body=Map.of("model",model,"messages",List.of(Map.of("role","user","content",prompt)),"temperature",0.3);String response=client.post().uri("/chat/completions").header(HttpHeaders.AUTHORIZATION,"Bearer "+key).contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(String.class);JsonNode root=mapper.readTree(response);return root.path("choices").path(0).path("message").path("content").asText("AI 未返回有效内容");}catch(Exception e){log.warn("AI 服务调用失败，异常类型：{}",e.getClass().getSimpleName());return "AI 服务调用失败，请稍后重试。";}}
}
