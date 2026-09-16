package cn.edu.lostfound.audit;

import cn.edu.lostfound.dto.*;
import cn.edu.lostfound.identity.AccountApi;
import cn.edu.lostfound.security.UserContext;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.*;
import org.springframework.web.bind.annotation.*;

@RestController
public class AuditController {
  private final JdbcTemplate jdbc;
  private final AccountApi accounts;
  public AuditController(JdbcTemplate jdbc,AccountApi accounts){this.jdbc=jdbc;this.accounts=accounts;}
  @GetMapping("/api/admin/logs")
  @Transactional(readOnly=true,isolation=Isolation.READ_COMMITTED)
  public ApiResponse<?> page(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="10") int pageSize,
      @RequestParam(defaultValue="") String objectType,@RequestParam(required=false) Long objectId,@RequestParam(defaultValue="") String action){
    accounts.requireAdministrator(UserContext.id());
    if(page<1||pageSize<1||pageSize>50||(long)(page-1)*pageSize>Integer.MAX_VALUE||action.length()>80||
        (!objectType.isEmpty()&&!Set.of("USER_VERIFICATION","ITEM","CLAIM").contains(objectType))||
        (objectId!=null&&(objectId<=0||objectType.isEmpty())))throw new IllegalArgumentException("日志筛选参数不合法");
    String type="CASE WHEN claim_id IS NOT NULL THEN 'CLAIM' WHEN item_id IS NOT NULL THEN 'ITEM' ELSE 'USER_VERIFICATION' END";
    String id="COALESCE(claim_id,item_id,subject_user_id)";
    var where=new StringBuilder(" WHERE 1=1");var params=new ArrayList<Object>();
    if(!objectType.isEmpty()){where.append(" AND (").append(type).append(")=?");params.add(objectType);}
    if(objectId!=null){where.append(" AND ").append(id).append("=?");params.add(objectId);}
    if(!action.isEmpty()){where.append(" AND event_type=?");params.add(action);}
    long total=jdbc.queryForObject("SELECT COUNT(*) FROM business_logs"+where,Long.class,params.toArray());params.add(pageSize);params.add((page-1)*pageSize);
    var records=jdbc.query("SELECT id,"+type+" AS object_type,"+id+" AS object_id,event_type,actor_id,occurred_at,from_state,to_state,user_reason FROM business_logs"+where+" ORDER BY occurred_at DESC,id DESC LIMIT ? OFFSET ?",(rs,n)->{
      var m=new LinkedHashMap<String,Object>();m.put("id",rs.getLong("id"));m.put("objectType",rs.getString("object_type"));m.put("objectId",rs.getLong("object_id"));m.put("action",rs.getString("event_type"));m.put("actorId",rs.getObject("actor_id",Long.class));
      m.put("occurredAt",rs.getObject("occurred_at",LocalDateTime.class).toInstant(ZoneOffset.UTC));m.put("beforeState",rs.getString("from_state"));m.put("afterState",rs.getString("to_state"));m.put("reason",rs.getString("user_reason"));return m;
    },params.toArray());return ApiResponse.ok(new ItemDtos.Page<>(records,total,page,pageSize));
  }
}
