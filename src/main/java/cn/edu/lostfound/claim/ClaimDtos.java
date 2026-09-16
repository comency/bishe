package cn.edu.lostfound.claim;

import jakarta.validation.constraints.*;

public final class ClaimDtos {
  private ClaimDtos() {}
  public record Create(@NotNull @Min(0) Long expectedItemVersion,
      @NotBlank @Size(max=1000) String identification,@NotBlank @Size(max=100) String contact) {}
  public record Accept(@NotNull @Min(0) Long expectedVersion,@NotBlank @Size(max=100) String contact) {}
  public record End(@NotNull @Min(0) Long expectedVersion,@NotBlank @Size(max=500) String reason) {}
  public record Resolve(@NotNull @Min(0) Long expectedVersion,@NotNull @Pattern(regexp="CONTINUE|TERMINATE") String action,
      @NotBlank @Size(max=500) String conclusion,@NotBlank @Size(max=500) String reason,@Size(max=500) String internalNote) {}
}
