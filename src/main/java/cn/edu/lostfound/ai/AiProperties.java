package cn.edu.lostfound.ai;

import java.net.URI;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Local-only: no paid provider, DNS resolution or redirects. */
@ConfigurationProperties("ai")
public record AiProperties(@DefaultValue("false") boolean enabled,
    @DefaultValue("http://127.0.0.1:11434") URI baseUrl,
    @DefaultValue("qwen3:1.7b") String model,
    @DefaultValue("20000") int timeoutMs,
    @DefaultValue("4096") int contextTokens,
    @DefaultValue("512") int outputTokens,
    @DefaultValue("4294967296") long minimumFreeBytes) {
  public AiProperties {
    if(baseUrl==null||!"http".equals(baseUrl.getScheme())||!Set.of("127.0.0.1","[::1]").contains(baseUrl.getHost())||
        baseUrl.getPort()<1||baseUrl.getPort()>65535||baseUrl.getRawUserInfo()!=null||baseUrl.getRawQuery()!=null||
        baseUrl.getRawFragment()!=null||!(baseUrl.getRawPath().isEmpty()||baseUrl.getRawPath().equals("/")))
      throw new IllegalArgumentException("AI仅允许显式端口的本机回环HTTP地址，不接受外部服务、凭据或路径");
    if(!Set.of("qwen3:1.7b","qwen3:4b").contains(model)||timeoutMs<100||timeoutMs>20000||
        contextTokens<2048||contextTokens>8192||outputTokens<64||outputTokens>1024||minimumFreeBytes<1)
      throw new IllegalArgumentException("AI资源配置超出本地试验范围");
  }
}
