package cn.edu.lostfound.dto;
import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.util.List;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
public class ItemDtos {
  public record Save(@NotBlank @Size(max=100) String title,@NotBlank @Size(max=3000) String description,@NotBlank @Pattern(regexp="LOST|FOUND") String type,String category,String location,LocalDate occurredAt) {}
  public static class Create {
    @NotBlank @Size(max=100) public String title;
    @NotBlank @Size(max=3000) public String description;
    @NotBlank @Pattern(regexp="LOST|FOUND") public String type;
    @Size(max=40) public String category;
    @Size(max=100) public String location;
    public LocalDate occurredAt;
    @Size(max=3) private List<@NotNull @Positive Long> imageIds;
    @JsonSetter(nulls=Nulls.FAIL) public void setImageIds(List<Long> ids){imageIds=ids;}
    public List<Long> getImageIds(){return imageIds;}
    public Save content(){return new Save(title,description,type,category,location,occurredAt);}
  }
  public static class Update extends Create { @NotNull @PositiveOrZero public Long expectedVersion; }
  public record Review(@NotBlank @Pattern(regexp="APPROVED|REJECTED|CLOSED") String status,
      @NotNull @PositiveOrZero Long expectedVersion,@Size(max=500) String reason,@Size(max=500) String internalNote) {}
  public record Close(@NotNull @PositiveOrZero Long expectedVersion,
      @NotBlank @Pattern(regexp="WITHDRAWN|FOUND_BY_OWNER") String closeReason,@NotBlank @Size(max=500) String reason) {}
  public record AdminClose(@NotNull @PositiveOrZero Long expectedVersion,@NotBlank @Size(max=500) String reason) {}
  public record Page<T>(List<T> records,long total,int page,int pageSize) {}
}
