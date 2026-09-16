package cn.edu.lostfound.ai;

import cn.edu.lostfound.dto.ApiResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;

/** Test-only marker; /api/admin/** remains protected by the ordinary current-role interceptor. */
@RestController
@Profile("modeltrial")
public class AiTrialController {
  public record TrialProof(boolean localTrial) {}
  @GetMapping("/api/admin/ai-trial") public ApiResponse<TrialProof> proof() {
    return ApiResponse.ok(new TrialProof(true));
  }
}
