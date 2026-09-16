package cn.edu.lostfound.identity;

import cn.edu.lostfound.verification.VerificationDtos.VerificationSummary;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.*;

public final class AccountDtos {
  private AccountDtos() {}
  public record UpdateMe(@NotBlank @Size(max=255) String nickname,
      @JsonProperty(required=true) @Size(max=100) String contact,
      @NotNull @PositiveOrZero Long expectedVersion) {}
  public record UserMe(Long userId, String username, String nickname, String contact,
      String role, long version, VerificationSummary verification) {}
}
