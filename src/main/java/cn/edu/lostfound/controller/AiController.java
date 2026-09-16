package cn.edu.lostfound.controller;

import cn.edu.lostfound.dto.AiDtos;
import cn.edu.lostfound.dto.ApiResponse;
import cn.edu.lostfound.service.AiService;
import jakarta.validation.Valid;
import cn.edu.lostfound.security.UserContext;
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
  public ApiResponse<AiDtos.Result> polish(@Valid @RequestBody AiDtos.Polish body) {
    return ApiResponse.ok(ai.polish(UserContext.id(),body.content()));
  }

  @PostMapping("/chat")
  public ApiResponse<AiDtos.Result> chat(@Valid @RequestBody AiDtos.Chat body) {
    return ApiResponse.ok(ai.chat(UserContext.id(),body.question()));
  }
}
