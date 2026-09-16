package cn.edu.lostfound.controller;
import cn.edu.lostfound.dto.*;
import cn.edu.lostfound.security.UserContext;
import cn.edu.lostfound.service.ItemService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/admin/items")
public class AdminController {
  private final ItemService service;
  public AdminController(ItemService service){this.service=service;}
  @GetMapping("/pending") public ApiResponse<?> pending(){return ApiResponse.ok(service.pending());}
  @GetMapping public ApiResponse<?> page(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="10") int pageSize,@RequestParam(defaultValue="") String keyword,@RequestParam(defaultValue="") String type,@RequestParam(defaultValue="") String category,@RequestParam(defaultValue="") String status,@RequestParam(required=false) @Positive Long itemId){return ApiResponse.ok(service.page(UserContext.id(),"admin",page,pageSize,keyword,type,category,status,itemId));}
  @GetMapping("/{id}") public ApiResponse<?> get(@PathVariable @Positive long id,@RequestParam(defaultValue="1") int timelinePage,@RequestParam(defaultValue="10") int timelinePageSize){return ApiResponse.ok(service.get(id,UserContext.id(),true,timelinePage,timelinePageSize));}
  @PutMapping("/{id}/review") public ApiResponse<?> review(@PathVariable @Positive long id,@Valid @RequestBody ItemDtos.Review r){return ApiResponse.ok(service.review(id,UserContext.id(),r));}
  @PostMapping("/{id}/close") public ApiResponse<?> close(@PathVariable @Positive long id,@Valid @RequestBody ItemDtos.AdminClose r){return ApiResponse.ok(service.adminClose(id,UserContext.id(),r));}
}
