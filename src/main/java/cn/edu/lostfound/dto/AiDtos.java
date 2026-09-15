package cn.edu.lostfound.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class AiDtos {
  private AiDtos() {}

  public record Polish(@NotBlank @Size(max = 3000) String content) {}

  public record Chat(@NotBlank @Size(max = 3000) String question) {}
}
