package cn.edu.lostfound.controller;

import cn.edu.lostfound.config.CampusProperties;
import cn.edu.lostfound.dto.ApiResponse;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/public")
public class PublicController {
  private final CampusProperties campus;
  private final boolean aiEnabled;
  public PublicController(CampusProperties campus, @Value("${ai.enabled:false}") boolean aiEnabled) {
    this.campus=campus; this.aiEnabled=aiEnabled;
  }
  public record PublicConfig(String campusName, String timezone, boolean isTest,
      String verificationInstructions, String supportContact, List<String> categories,
      long maxImageBytes, int maxImagesPerItem, boolean aiEnabled) {}
  @GetMapping("/config") public ApiResponse<PublicConfig> config() {
    return ApiResponse.ok(new PublicConfig(campus.campusName(), campus.timezone(), campus.testMode(),
        campus.verificationInstructions(), campus.supportContact(),
        List.of("证件卡片", "数码设备", "书本文具", "衣物配饰", "生活用品", "其他"),
        5L*1024*1024, 3, aiEnabled));
  }
}
