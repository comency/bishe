package cn.edu.lostfound.claim;

import cn.edu.lostfound.dto.ApiResponse;
import cn.edu.lostfound.security.UserContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.web.bind.annotation.*;

@RestController
public class ClaimController {
  private final ClaimService service;
  public ClaimController(ClaimService service){this.service=service;}
  @PostMapping("/api/items/{id}/claims") public ApiResponse<?> create(@PathVariable @Positive long id,@Valid @RequestBody ClaimDtos.Create r){return ApiResponse.ok(service.create(id,UserContext.id(),r));}
  @GetMapping("/api/claims/mine") public ApiResponse<?> mine(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="10") int pageSize,@RequestParam(defaultValue="") String status,@RequestParam(required=false) Long itemId){return ApiResponse.ok(service.page(UserContext.id(),"mine",page,pageSize,status,itemId,null,null));}
  @GetMapping("/api/claims/incoming") public ApiResponse<?> incoming(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="10") int pageSize,@RequestParam(defaultValue="") String status,@RequestParam(required=false) Long itemId){return ApiResponse.ok(service.page(UserContext.id(),"incoming",page,pageSize,status,itemId,null,null));}
  @GetMapping("/api/claims/{id}") public ApiResponse<?> get(@PathVariable @Positive long id,@RequestParam(defaultValue="1") int timelinePage,@RequestParam(defaultValue="10") int timelinePageSize){return ApiResponse.ok(service.get(id,UserContext.id(),false,timelinePage,timelinePageSize));}
  @PostMapping("/api/claims/{id}/accept") public ApiResponse<?> accept(@PathVariable @Positive long id,@Valid @RequestBody ClaimDtos.Accept r){return ApiResponse.ok(service.accept(id,UserContext.id(),r));}
  @PostMapping("/api/claims/{id}/reject") public ApiResponse<?> reject(@PathVariable @Positive long id,@Valid @RequestBody ClaimDtos.End r){return ApiResponse.ok(service.end(id,UserContext.id(),r,true));}
  @PostMapping("/api/claims/{id}/cancel") public ApiResponse<?> cancel(@PathVariable @Positive long id,@Valid @RequestBody ClaimDtos.End r){return ApiResponse.ok(service.end(id,UserContext.id(),r,false));}
  @PostMapping("/api/claims/{id}/confirm-handover") public ApiResponse<?> handover(@PathVariable @Positive long id,@RequestBody(required=false) String body){noBody(body);return ApiResponse.ok(service.confirm(id,UserContext.id(),true));}
  @PostMapping("/api/claims/{id}/confirm-receipt") public ApiResponse<?> receipt(@PathVariable @Positive long id,@RequestBody(required=false) String body){noBody(body);return ApiResponse.ok(service.confirm(id,UserContext.id(),false));}
  private static void noBody(String body){if(body!=null&&!body.isEmpty())throw new IllegalArgumentException("交接确认不接收请求体或版本号");}
  @GetMapping("/api/admin/claims") public ApiResponse<?> adminPage(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="10") int pageSize,@RequestParam(defaultValue="") String status,@RequestParam(required=false) Long itemId,@RequestParam(required=false) Long publisherId,@RequestParam(required=false) Long applicantId){return ApiResponse.ok(service.page(UserContext.id(),"admin",page,pageSize,status,itemId,publisherId,applicantId));}
  @GetMapping("/api/admin/claims/{id}") public ApiResponse<?> adminGet(@PathVariable @Positive long id,@RequestParam(defaultValue="1") int timelinePage,@RequestParam(defaultValue="10") int timelinePageSize){return ApiResponse.ok(service.get(id,UserContext.id(),true,timelinePage,timelinePageSize));}
  @PostMapping("/api/admin/claims/{id}/resolve") public ApiResponse<?> resolve(@PathVariable @Positive long id,@Valid @RequestBody ClaimDtos.Resolve r){return ApiResponse.ok(service.resolve(id,UserContext.id(),r));}
}
