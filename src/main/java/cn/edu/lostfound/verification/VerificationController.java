package cn.edu.lostfound.verification;

import cn.edu.lostfound.dto.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import static cn.edu.lostfound.verification.VerificationDtos.*;

@RestController
@RequestMapping("/api")
public class VerificationController {
  private final VerificationService service;
  public VerificationController(VerificationService service) { this.service=service; }

  @GetMapping("/verifications/me")
  public ApiResponse<MyVerification> mine(@RequestParam(defaultValue="1") int page,
      @RequestParam(defaultValue="10") int pageSize) { return ApiResponse.ok(service.mine(page,pageSize)); }
  @PostMapping("/verifications/me")
  public ApiResponse<MyVerification> submit(@Valid @RequestBody SubmitRequest request) {
    return ApiResponse.ok(service.submit(request));
  }
  @GetMapping("/admin/verifications")
  public ApiResponse<Page<AdminSummary>> list(@RequestParam(required=false) Status status,
      @RequestParam(required=false) String keyword,@RequestParam(required=false) Long userId,
      @RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="10") int pageSize) {
    return ApiResponse.ok(service.list(status,keyword,userId,page,pageSize));
  }
  @GetMapping("/admin/verifications/{userId}")
  public ApiResponse<AdminVerification> detail(@PathVariable Long userId,
      @RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="10") int pageSize) {
    return ApiResponse.ok(service.detail(userId,page,pageSize));
  }
  @PostMapping("/admin/verifications/{userId}/review")
  public ApiResponse<AdminVerification> review(@PathVariable Long userId,@Valid @RequestBody ReviewRequest request) {
    return ApiResponse.ok(service.review(userId,request));
  }
  @PostMapping("/admin/verifications/{userId}/revoke")
  public ApiResponse<AdminVerification> revoke(@PathVariable Long userId,@Valid @RequestBody VersionReasonRequest request) {
    return ApiResponse.ok(service.revoke(userId,request));
  }
  @PostMapping("/admin/verifications/{userId}/reopen")
  public ApiResponse<AdminVerification> reopen(@PathVariable Long userId,@Valid @RequestBody VersionReasonRequest request) {
    return ApiResponse.ok(service.reopen(userId,request));
  }
}
