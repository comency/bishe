package cn.edu.lostfound.dto;
import jakarta.validation.constraints.*;
public class AuthDtos {
  public record Register(@NotBlank @Size(min=3,max=40) String username,@NotBlank @Size(min=6,max=64) String password,@NotBlank @Size(max=255) String nickname) {}
  public record Login(@NotBlank @Size(max=40) String username,@NotBlank @Size(max=64) String password) {}
}
