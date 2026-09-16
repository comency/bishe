package cn.edu.lostfound.media;
import cn.edu.lostfound.dto.ApiResponse;
import cn.edu.lostfound.security.UserContext;
import jakarta.validation.constraints.Positive;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController @RequestMapping("/api/uploads/images")
public class MediaController {
  private final MediaService media;
  public MediaController(MediaService media){this.media=media;}
  @PostMapping(consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
  public ApiResponse<MediaService.Meta> upload(@RequestParam("file") MultipartFile file){return ApiResponse.ok(media.upload(UserContext.id(),file));}
  @GetMapping("/{id}")
  public ResponseEntity<byte[]> read(@PathVariable @Positive long id){
    var content=media.read(id,UserContext.id(),UserContext.admin());
    return ResponseEntity.ok().contentType(MediaType.parseMediaType(content.mime())).cacheControl(CacheControl.noStore())
      .header("X-Content-Type-Options","nosniff").header("Content-Disposition","inline; filename=\"image\"").body(content.bytes());
  }
}
