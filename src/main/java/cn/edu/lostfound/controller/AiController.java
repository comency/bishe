package cn.edu.lostfound.controller;

import cn.edu.lostfound.dto.AiDtos;
import cn.edu.lostfound.dto.ApiResponse;
import cn.edu.lostfound.service.AiService;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ai")
public class AiController {
  private final AiService ai;

  public AiController(AiService ai) {
    this.ai = ai;
  }

  @PostMapping("/polish")
  public ApiResponse<Map<String, String>> polish(@Valid @RequestBody AiDtos.Polish body) {
    return ApiResponse.ok(Map.of("content", ai.polish(body.content())));
  }

  @PostMapping("/chat")
  public ApiResponse<Map<String, String>> chat(@Valid @RequestBody AiDtos.Chat body) {
    return ApiResponse.ok(Map.of("content", ai.chat(body.question())));
  }
}
