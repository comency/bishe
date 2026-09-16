package cn.edu.lostfound.claim;

import cn.edu.lostfound.audit.AuditApi;
import cn.edu.lostfound.common.BusinessException;
import cn.edu.lostfound.config.CampusProperties;
import cn.edu.lostfound.dto.ItemDtos;
import cn.edu.lostfound.entity.Item;
import cn.edu.lostfound.identity.AccountApi;
import cn.edu.lostfound.repository.ItemRepository;
import cn.edu.lostfound.verification.VerificationApi;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@Transactional(readOnly=true,isolation=Isolation.READ_COMMITTED)
public class ClaimService {
  private final ClaimStore store;
  private final ClaimApi api;
  private final ItemRepository items;
  private final VerificationApi verification;
  private final AccountApi accounts;
  private final AuditApi audit;
  private final Clock clock;
  private final CampusProperties campus;
  private final org.springframework.jdbc.core.JdbcTemplate jdbc;
  private static final Set<String> STATES=Set.of("APPLIED","ACCEPTED","REJECTED","CANCELLED","COMPLETED");
  public ClaimService(ClaimStore store,ClaimApi api,ItemRepository items,VerificationApi verification,
      AccountApi accounts,AuditApi audit,Clock clock,CampusProperties campus,org.springframework.jdbc.core.JdbcTemplate jdbc){
    this.store=store;this.api=api;this.items=items;this.verification=verification;this.accounts=accounts;this.audit=audit;this.clock=clock;this.campus=campus;
    this.jdbc=jdbc;
  }
  static BusinessException hidden(){return new BusinessException(404,"NOT_ACCESSIBLE","认领记录不存在或不可访问");}
  static BusinessException conflict(String code,String message){return new BusinessException(409,code,message);}
  private static void state(boolean condition,String message){if(!condition)throw conflict("STATE_CONFLICT",message);}
  private static void version(long actual,long expected){if(actual!=expected)throw conflict("VERSION_CONFLICT","记录已改变，请刷新后再操作");}
  static void pageCheck(int page,int size){if(page<1||size<1||size>50||(long)(page-1)*size>Integer.MAX_VALUE)throw new IllegalArgumentException("分页参数不合法");}
  private ClaimStore.Row accessible(long id,long actor,boolean admin){
    var r=store.find(id).orElseThrow(ClaimService::hidden);if(!admin&&!r.participant(actor))throw hidden();return r;
  }
  private void direction(boolean allowed){if(!allowed)throw new BusinessException(403,"FORBIDDEN","当前参与者不能执行此方向的操作");}
  private Item lockItem(long id){return items.lockById(id).orElseThrow(ClaimService::hidden);}
  private void openFound(Item i){state(i.getType().equals("FOUND")&&i.getStatus().equals("APPROVED"),"只有已通过审核的招领启事可以交接");}
  private void lockParticipants(ClaimStore.Row r,long actor){
    // Qualification -> item -> claims. Stable ordering also serializes revocation with handover.
    for(long id:new TreeSet<>(List.of(r.publisherId(),r.applicantId()))) {
      if(id==actor)verification.lockEligible(id);else verification.lockForAccount(id);
    }
  }
  private void counterpart(ClaimStore.Row r,long actor){verification.lockCounterpartEligible(actor==r.publisherId()?r.applicantId():r.publisherId());}
  private void event(ClaimStore.Row r,String action,long actor,boolean admin,String from,String code,String reason,String conclusion,String note){
    audit.claim(action,actor,admin,r.itemId(),r.id(),from,r.status(),r.version(),code,reason,conclusion,note);
  }
  private void occupation(Item item){item.occupationChanged(clock.instant(),campus.zoneId());items.flush();}
  private void closeItem(Item item,long actor,boolean admin,String code,String reason,String note){
    String from=item.getStatus();item.close(code,reason,clock.instant());occupation(item);
    audit.item("ITEM_CLOSED",actor,admin,item.getPublisherId(),item.getId(),from,"CLOSED",item.getVersion(),item.getContentVersion(),reason,note);
  }

  @Transactional(isolation=Isolation.READ_COMMITTED)
  public Map<String,Object> create(long itemId,long actor,ClaimDtos.Create request){
    verification.lockEligible(actor);Item item=lockItem(itemId);
    if(!item.getStatus().equals("APPROVED"))throw hidden();
    direction(item.getPublisherId()!=actor);openFound(item);version(item.getVersion(),request.expectedItemVersion());
    if(api.myClaim(itemId,actor)!=null)throw conflict("CLAIM_EXISTS","同一物品只能申请一次，可在我的认领中查看原记录");
    state(!api.hasAccepted(itemId),"该物品已有接受的认领，请等待处理");
    long id=store.create(item,actor,request,clock.instant());var row=store.lock(id);
    event(row,"CLAIM_APPLIED",actor,false,null,null,null,null,null);return detail(row,actor,false,1,10);
  }
  @Transactional(isolation=Isolation.READ_COMMITTED)
  public Map<String,Object> accept(long id,long actor,ClaimDtos.Accept request){
    var locator=accessible(id,actor,false);direction(locator.publisherId()==actor);lockParticipants(locator,actor);
    Item item=lockItem(locator.itemId());var row=store.lock(id);version(row.version(),request.expectedVersion());
    state(row.status().equals("APPLIED"),"只有待处理申请可以接受");openFound(item);state(!api.hasAccepted(item.getId()),"该物品已有接受的认领");counterpart(row,actor);
    store.accept(row,request.contact(),clock.instant());occupation(item);var updated=store.lock(id);
    event(updated,"CLAIM_ACCEPTED",actor,false,row.status(),null,null,null,null);return detail(updated,actor,false,1,10);
  }
  @Transactional(isolation=Isolation.READ_COMMITTED)
  public Map<String,Object> end(long id,long actor,ClaimDtos.End request,boolean reject){
    var locator=accessible(id,actor,false);verification.lockEligible(actor);Item item=lockItem(locator.itemId());var row=store.lock(id);
    version(row.version(),request.expectedVersion());
    if(reject){direction(row.publisherId()==actor);state(row.status().equals("APPLIED"),"只有待处理申请可以拒绝");}
    else {state(Set.of("APPLIED","ACCEPTED").contains(row.status()),"该认领不能取消");if(row.status().equals("APPLIED"))direction(row.applicantId()==actor);state(!row.anyConfirmed(),"已有交接确认，不能直接取消，请联系管理员");}
    api.end(row,reject?"REJECTED":"CANCELLED",reject?"PUBLISHER_REJECTED":"USER_CANCELLED",request.reason(),false,actor,false);
    if(row.status().equals("ACCEPTED"))occupation(item);return detail(store.lock(id),actor,false,1,10);
  }
  @Transactional(isolation=Isolation.READ_COMMITTED)
  public Map<String,Object> confirm(long id,long actor,boolean handover){
    var locator=accessible(id,actor,false);direction(handover?locator.publisherId()==actor:locator.applicantId()==actor);
    lockParticipants(locator,actor);Item item=lockItem(locator.itemId());var rows=store.lockItem(item.getId());
    var row=rows.stream().filter(r->r.id()==id).findFirst().orElseThrow(ClaimService::hidden);
    if(row.status().equals("COMPLETED")) {
      boolean valid=row.handedOverAt()!=null&&row.receivedAt()!=null&&row.completedAt()!=null&&item.getType().equals("FOUND")&&
        item.getStatus().equals("CLOSED")&&"RETURNED".equals(item.getCloseReason())&&rows.stream().noneMatch(r->Set.of("APPLIED","ACCEPTED").contains(r.status()))&&
        rows.stream().filter(r->r.status().equals("COMPLETED")).count()==1;
      if(!valid)throw new BusinessException(500,"SERVICE_ERROR","交接记录需要管理员核对");
      return detail(row,actor,false,1,10);
    }
    state(row.status().equals("ACCEPTED"),"只有已接受的认领可以确认交接");openFound(item);counterpart(row,actor);
    if(handover?row.handedOverAt()!=null:row.receivedAt()!=null)return detail(row,actor,false,1,10);
    boolean complete=handover?row.receivedAt()!=null:row.handedOverAt()!=null;
    store.confirm(row,handover,complete,clock.instant());var updated=store.lock(id);
    event(updated,handover?"CLAIM_HANDOVER_CONFIRMED":"CLAIM_RECEIPT_CONFIRMED",actor,false,row.status(),null,null,null,null);
    if(complete){
      event(updated,"CLAIM_COMPLETED",actor,false,row.status(),null,null,null,null);
      for(var other:rows)if(other.status().equals("APPLIED"))api.end(other,"REJECTED","ITEM_RETURNED","物品已完成归还",false,null,false);
      closeItem(item,actor,false,"RETURNED","双方已确认物品归还",null);
    }
    return detail(updated,actor,false,1,10);
  }
  @Transactional(isolation=Isolation.READ_COMMITTED)
  public Map<String,Object> resolve(long id,long actor,ClaimDtos.Resolve request){
    verification.lockForAccount(actor);accounts.requireAdministrator(actor);var locator=accessible(id,actor,true);
    Item item=lockItem(locator.itemId());var rows=store.lockItem(item.getId());var row=rows.stream().filter(r->r.id()==id).findFirst().orElseThrow(ClaimService::hidden);
    version(row.version(),request.expectedVersion());state(row.status().equals("ACCEPTED")&&row.singleConfirmed(),"只有单方已确认的交接可进行异常处置");openFound(item);
    if(request.action().equals("CONTINUE"))store.touch(row,clock.instant());
    else if(request.action().equals("TERMINATE")){
      store.end(row,"CANCELLED","ADMIN_REMOVED",request.reason(),true,clock.instant());
      for(var other:rows)if(other.status().equals("APPLIED"))api.end(other,"REJECTED","ADMIN_REMOVED",request.reason(),false,null,false);
      closeItem(item,actor,true,"ADMIN_REMOVED",request.reason(),request.internalNote());
    } else throw new IllegalArgumentException("异常处置动作不合法");
    var updated=store.lock(id);event(updated,"CLAIM_RESOLVED_"+request.action(),actor,true,row.status(),request.action().equals("TERMINATE")?"ADMIN_REMOVED":null,
        request.reason().trim(),request.conclusion().trim(),request.internalNote());return detail(updated,actor,true,1,10);
  }

  public Map<String,Object> get(long id,long actor,boolean admin,int page,int size){
    pageCheck(page,size);if(admin)accounts.requireAdministrator(actor);else verification.requireEligible(actor);
    return detail(accessible(id,actor,admin),actor,admin,page,size);
  }
  public ItemDtos.Page<Map<String,Object>> page(long actor,String scope,int page,int size,String status,Long itemId,Long publisherId,Long applicantId){
    pageCheck(page,size);boolean admin=scope.equals("admin");if(admin)accounts.requireAdministrator(actor);else verification.requireEligible(actor);
    if(status!=null&&!status.isEmpty()&&!STATES.contains(status))throw new IllegalArgumentException("认领状态不合法");
    var params=new ArrayList<Object>();var where=new StringBuilder(" WHERE 1=1");
    if(!admin){where.append(scope.equals("mine")?" AND applicant_id=?":" AND publisher_id=?");params.add(actor);}
    if(status!=null&&!status.isEmpty()){where.append(" AND status=?");params.add(status);}
    addId(where,params,"item_id",itemId);if(admin){addId(where,params,"publisher_id",publisherId);addId(where,params,"applicant_id",applicantId);}
    long total=jdbc.queryForObject("SELECT COUNT(*) FROM claims"+where,Long.class,params.toArray());params.add(size);params.add((page-1)*size);
    var records=jdbc.query("SELECT * FROM claims"+where+" ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?",ClaimStore.MAPPER,params.toArray());
    return new ItemDtos.Page<>(records.stream().map(r->summary(r,actor,admin)).toList(),total,page,size);
  }
  private static void addId(StringBuilder where,List<Object> params,String column,Long id){if(id!=null){if(id<=0)throw new IllegalArgumentException("筛选ID必须为正整数");where.append(" AND ").append(column).append("=?");params.add(id);}}
  private Map<String,Object> summary(ClaimStore.Row r,long actor,boolean admin){
    var m=new LinkedHashMap<String,Object>();m.put("id",r.id());m.put("item",Map.of("itemId",r.itemId(),"title",r.title(),"type","FOUND","contentVersion",r.contentVersion()));
    m.put("publisherId",r.publisherId());m.put("applicantId",r.applicantId());m.put("status",r.status());m.put("version",r.version());
    m.put("createdAt",r.createdAt());m.put("acceptedAt",r.acceptedAt());m.put("handedOverAt",r.handedOverAt());m.put("receivedAt",r.receivedAt());m.put("completedAt",r.completedAt());m.put("endReason",r.endReason());
    boolean ownConfirmed=actor==r.publisherId()?r.handedOverAt()!=null:r.receivedAt()!=null;
    m.put("canConfirm",!admin&&r.participant(actor)&&r.status().equals("ACCEPTED")&&!ownConfirmed&&verification.eligible(r.publisherId())&&verification.eligible(r.applicantId()));return m;
  }
  private Map<String,Object> detail(ClaimStore.Row r,long actor,boolean admin,int page,int size){
    var m=summary(r,actor,admin);m.put("identification",r.evidence());
    var records=jdbc.query("SELECT id,event_type,occurred_at,user_reason FROM business_logs WHERE claim_id=? ORDER BY id DESC LIMIT ? OFFSET ?",(rs,n)->{
      var e=new LinkedHashMap<String,Object>();e.put("id",rs.getLong("id"));e.put("action",rs.getString("event_type"));e.put("occurredAt",rs.getObject("occurred_at",LocalDateTime.class).toInstant(ZoneOffset.UTC));e.put("message",rs.getString("user_reason"));return e;
    },r.id(),size,(page-1)*size);
    m.put("timeline",new ItemDtos.Page<>(records,jdbc.queryForObject("SELECT COUNT(*) FROM business_logs WHERE claim_id=?",Long.class,r.id()),page,size));
    if(!admin)m.put("counterpartContact",r.status().equals("ACCEPTED")?(actor==r.publisherId()?r.applicantContact():r.publisherContact()):null);
    else {
      m.put("applicantContactSnapshot",r.applicantContact());m.put("publisherContactSnapshot",r.publisherContact());
      var resolutions=jdbc.query("SELECT event_type,resolution_conclusion,user_reason,internal_note,occurred_at,actor_id FROM business_logs WHERE claim_id=? AND event_type IN ('CLAIM_RESOLVED_CONTINUE','CLAIM_RESOLVED_TERMINATE') ORDER BY id DESC LIMIT 1",(rs,n)->{
        var x=new LinkedHashMap<String,Object>();x.put("action",rs.getString("event_type").substring("CLAIM_RESOLVED_".length()));x.put("conclusion",rs.getString("resolution_conclusion"));x.put("reason",rs.getString("user_reason"));x.put("internalNote",rs.getString("internal_note"));x.put("occurredAt",rs.getObject("occurred_at",LocalDateTime.class).toInstant(ZoneOffset.UTC));x.put("actorId",rs.getLong("actor_id"));return x;
      },r.id());var latest=resolutions.isEmpty()?null:resolutions.get(0);m.put("latestResolution",latest);m.put("internalNote",latest==null?null:latest.get("internalNote"));
    }return m;
  }
}
