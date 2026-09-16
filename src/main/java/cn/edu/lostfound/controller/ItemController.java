package cn.edu.lostfound.controller;
import cn.edu.lostfound.dto.*;
import cn.edu.lostfound.security.UserContext;
import cn.edu.lostfound.service.ItemService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController @RequestMapping("/api/items")
public class ItemController {
  private final ItemService service;
  public ItemController(ItemService service){this.service=service;}
  @PostMapping public ApiResponse<?> create(@Valid @RequestBody ItemDtos.Create r){return ApiResponse.ok(service.create(UserContext.id(),r));}
  @GetMapping public ApiResponse<?> search(@RequestParam(defaultValue="") String keyword,@RequestParam(defaultValue="") String type){return ApiResponse.ok(service.search(keyword,type));}
  @GetMapping("/mine") public ApiResponse<?> mine(){return ApiResponse.ok(service.mine(UserContext.id()));}
  @PutMapping("/{id}") public ApiResponse<?> update(@PathVariable @Positive long id,@Valid @RequestBody ItemDtos.Update r){return ApiResponse.ok(service.update(id,UserContext.id(),r));}
  @GetMapping("/{id}") public ApiResponse<?> get(@PathVariable @Positive long id,@RequestParam(defaultValue="1") int timelinePage,@RequestParam(defaultValue="10") int timelinePageSize){return ApiResponse.ok(service.get(id,UserContext.id(),false,timelinePage,timelinePageSize));}
  @GetMapping("/{id}/matches") public ApiResponse<?> matches(@PathVariable @Positive long id){return ApiResponse.ok(service.matches(id,UserContext.id(),false));}
  @PostMapping("/{id}/close") public ApiResponse<?> close(@PathVariable @Positive long id,@Valid @RequestBody ItemDtos.Close r){return ApiResponse.ok(service.close(id,UserContext.id(),r));}
  @GetMapping("/page") public ApiResponse<?> page(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="10") int pageSize,@RequestParam(defaultValue="") String keyword,@RequestParam(defaultValue="") String type,@RequestParam(defaultValue="") String category){return ApiResponse.ok(service.page(UserContext.id(),"public",page,pageSize,keyword,type,category,null,null));}
  @GetMapping("/mine/page") public ApiResponse<?> minePage(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="10") int pageSize,@RequestParam(defaultValue="") String keyword,@RequestParam(defaultValue="") String type,@RequestParam(defaultValue="") String category,@RequestParam(defaultValue="") String status){return ApiResponse.ok(service.page(UserContext.id(),"mine",page,pageSize,keyword,type,category,status,null));}
}
