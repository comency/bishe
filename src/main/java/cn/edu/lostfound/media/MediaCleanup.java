package cn.edu.lostfound.media;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.*;

@Configuration @EnableScheduling
@ConditionalOnProperty(name="app.media.cleanup-enabled",havingValue="true")
public class MediaCleanup {
  private final MediaService service;
  public MediaCleanup(MediaService service){this.service=service;}
  @Scheduled(fixedDelayString="${app.media.cleanup-delay-ms:3600000}")
  public void cleanup(){service.cleanup();}
}
