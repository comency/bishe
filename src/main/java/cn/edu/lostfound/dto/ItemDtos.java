package cn.edu.lostfound.dto;
import jakarta.validation.constraints.*;
import java.time.LocalDate;
public class ItemDtos {
  public record Save(@NotBlank @Size(max=100) String title,@NotBlank @Size(max=3000) String description,@NotBlank @Pattern(regexp="LOST|FOUND") String type,String category,String location,LocalDate occurredAt) {}
  public record Review(@NotBlank @Pattern(regexp="APPROVED|REJECTED|CLOSED") String status) {}
}
