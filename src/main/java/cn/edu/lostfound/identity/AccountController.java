package cn.edu.lostfound.identity;

import cn.edu.lostfound.dto.ApiResponse;
import cn.edu.lostfound.security.UserContext;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/users/me")
public class AccountController {
  private final AccountWorkflow accounts;
  public AccountController(AccountWorkflow accounts) { this.accounts=accounts; }
  @GetMapping public ApiResponse<AccountDtos.UserMe> me() {
    return ApiResponse.ok(accounts.me(UserContext.id()));
  }
  @PutMapping public ApiResponse<AccountDtos.UserMe> update(@Valid @RequestBody AccountDtos.UpdateMe request) {
    return ApiResponse.ok(accounts.update(UserContext.id(), request));
  }
}
