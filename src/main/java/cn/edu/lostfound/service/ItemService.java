package cn.edu.lostfound.service;

import cn.edu.lostfound.audit.AuditApi;
import cn.edu.lostfound.common.BusinessException;
import cn.edu.lostfound.config.CampusProperties;
import cn.edu.lostfound.dto.ItemDtos;
import cn.edu.lostfound.entity.*;
import cn.edu.lostfound.identity.AccountApi;
import cn.edu.lostfound.media.MediaService;
import cn.edu.lostfound.repository.*;
import cn.edu.lostfound.verification.VerificationApi;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@Transactional(readOnly=true,isolation=Isolation.READ_COMMITTED)
public class ItemService {
  private final ItemRepository items;
  private final UserRepository users;
  private final VerificationApi verification;
  private final AccountApi accounts;
  private final MediaService media;
  private final AuditApi audit;
  private final JdbcTemplate jdbc;
  private final Clock clock;
  private final CampusProperties campus;
  private final ZoneId legacyZone;
  public ItemService(ItemRepository items,UserRepository users,VerificationApi verification,AccountApi accounts,
      MediaService media,AuditApi audit,JdbcTemplate jdbc,Clock clock,CampusProperties campus,
      @Value("${app.items.legacy-timezone:Asia/Shanghai}") String legacyZone){
    this.items=items;this.users=users;this.verification=verification;this.accounts=accounts;this.media=media;
    this.audit=audit;this.jdbc=jdbc;this.clock=clock;this.campus=campus;this.legacyZone=ZoneId.of(legacyZone);
  }
  public static BusinessException hidden(){return new BusinessException(404,"NOT_ACCESSIBLE","物品不存在或不可访问");}
  private static void conflict(String code,String message){throw new BusinessException(409,code,message);}
  private static void pageCheck(int page,int size){if(page<1||size<1||size>50||(long)(page-1)*size>Integer.MAX_VALUE)throw new IllegalArgumentException("分页参数不合法");}
  private void validate(ItemDtos.Save r){
    if(r.occurredAt()!=null&&r.occurredAt().isAfter(LocalDate.now(clock.withZone(campus.zoneId()))))throw new IllegalArgumentException("发生日期不能晚于校园今天");
  }
  private Item locked(long id,Long owner,long expected){
    Item i=items.lockById(id).orElseThrow(ItemService::hidden);
    if(owner!=null&&!owner.equals(i.getPublisherId()))throw hidden();
    if(i.getVersion()!=expected)conflict("VERSION_CONFLICT","物品已改变，请刷新");
    if(i.getStatus().equals("CLOSED"))conflict("STATE_CONFLICT","已关闭物品不能再次操作");
    return i;
  }
  private void logged(Item i,String action,long actor,boolean admin,String from,String reason,String note){
    items.flush(); // Snapshot the post-flush @Version in the same transaction as the event.
    audit.item(action,actor,admin,i.getPublisherId(),i.getId(),from,i.getStatus(),i.getVersion(),i.getContentVersion(),reason,note);
  }
  private Item createEntity(long actor,ItemDtos.Save r){
    verification.lockEligible(actor);validate(r);
    Item i=new Item(users.findById(actor).orElseThrow(ItemService::hidden),r.title().trim(),r.description().trim(),r.type(),r.category(),r.location(),r.occurredAt());
    i.initializeTime(clock.instant(),campus.zoneId());return items.saveAndFlush(i);
  }
  /** Non-HTTP adapter retained for qualification transaction regression tests. */
  @Transactional(isolation=Isolation.READ_COMMITTED)
  public Item create(Long actor,ItemDtos.Save r){Item i=createEntity(actor,r);logged(i,"ITEM_CREATED",actor,false,null,null,null);return i;}
  @Transactional(isolation=Isolation.READ_COMMITTED)
  public Map<String,Object> create(long actor,ItemDtos.Create r){
    Item i=createEntity(actor,r.content());media.bind(i.getId(),actor,r.getImageIds());logged(i,"ITEM_CREATED",actor,false,null,null,null);
    return detail(i,actor,false,1,10);
  }
  @Transactional(isolation=Isolation.READ_COMMITTED)
  public Map<String,Object> update(long id,long actor,ItemDtos.Update r){
    verification.lockEligible(actor);validate(r.content());Item i=locked(id,actor,r.expectedVersion);
    // Claims do not exist in this release. The claim module must acquire this item
    // lock and add its active-claim guard here before enabling claim creation.
    String from=i.getStatus();
    i.update(r.title.trim(),r.description.trim(),r.type,r.category,r.location,r.occurredAt);i.resubmit();i.changed(clock.instant(),campus.zoneId());
    media.bind(id,actor,r.getImageIds());logged(i,"ITEM_EDITED",actor,false,from,null,null);return detail(i,actor,false,1,10);
  }
  @Transactional(isolation=Isolation.READ_COMMITTED)
  public Map<String,Object> review(long id,long actor,ItemDtos.Review r){
    verification.lockForAccount(actor);accounts.requireAdministrator(actor);
    if(!Set.of("APPROVED","REJECTED","CLOSED").contains(r.status()))throw new IllegalArgumentException("审核状态不合法");
    if(!r.status().equals("APPROVED")&&(r.reason()==null||r.reason().isBlank()))throw new IllegalArgumentException("驳回或下架必须填写原因");
    if(r.status().equals("APPROVED")&&r.reason()!=null)throw new IllegalArgumentException("通过审核不接收驳回原因");
    Item i=locked(id,null,r.expectedVersion());
    if(r.status().equals("CLOSED"))return closeEntity(i,actor,true,"ADMIN_REMOVED",r.reason(),r.internalNote());
    if(!i.getStatus().equals("PENDING"))conflict("STATE_CONFLICT","只有待审核物品可审核");
    i.review(r.status(),actor,r.reason(),clock.instant());i.changed(clock.instant(),campus.zoneId());
    logged(i,"ITEM_"+r.status(),actor,true,"PENDING",r.reason(),r.internalNote());return detail(i,actor,true,1,10);
  }
  @Transactional(isolation=Isolation.READ_COMMITTED)
  public Map<String,Object> close(long id,long actor,ItemDtos.Close r){
    verification.lockEligible(actor);Item i=locked(id,actor,r.expectedVersion());
    if(!Set.of("WITHDRAWN","FOUND_BY_OWNER").contains(r.closeReason()))throw new IllegalArgumentException("关闭原因不合法");
    if(r.closeReason().equals("FOUND_BY_OWNER")&&!i.getType().equals("LOST"))throw new IllegalArgumentException("只有寻物启事可以标记本人找回");
    return closeEntity(i,actor,false,r.closeReason(),r.reason(),null);
  }
  @Transactional(isolation=Isolation.READ_COMMITTED)
  public Map<String,Object> adminClose(long id,long actor,ItemDtos.AdminClose r){
    verification.lockForAccount(actor);accounts.requireAdministrator(actor);
    return closeEntity(locked(id,null,r.expectedVersion()),actor,true,"ADMIN_REMOVED",r.reason(),null);
  }
  private Map<String,Object> closeEntity(Item i,long actor,boolean admin,String code,String reason,String note){
    if(reason==null||reason.isBlank())throw new IllegalArgumentException("关闭必须填写原因");
    String from=i.getStatus();i.close(code,reason,clock.instant());i.changed(clock.instant(),campus.zoneId());
    logged(i,"ITEM_CLOSED",actor,admin,from,reason,note);return detail(i,actor,admin,1,10);
  }
  public Map<String,Object> get(long id,long actor,boolean admin,int page,int size){
    if(admin)accounts.requireAdministrator(actor);else verification.requireEligible(actor);
    Item i=items.findById(id).orElseThrow(ItemService::hidden);
    if(!admin&&!i.getStatus().equals("APPROVED")&&!i.getPublisherId().equals(actor))throw hidden();
    pageCheck(page,size);return detail(i,actor,admin,page,size);
  }
  public List<Map<String,Object>> search(String keyword,String type){filterCheck(keyword,type,null,null);return items.search(keyword,type).stream().map(this::legacy).toList();}
  public List<Map<String,Object>> mine(Long id){return items.findByPublisherIdOrderByCreatedAtDesc(id).stream().map(this::legacy).toList();}
  public List<Map<String,Object>> pending(){return items.findByStatusOrderByCreatedAtDesc("PENDING").stream().map(this::legacy).toList();}
  private void filterCheck(String keyword,String type,String category,String status){
    if(keyword.length()>100||(!type.isEmpty()&&!Set.of("LOST","FOUND").contains(type))||(category!=null&&category.length()>40)||
      (status!=null&&!status.isEmpty()&&!Set.of("PENDING","APPROVED","REJECTED","CLOSED").contains(status)))throw new IllegalArgumentException("筛选参数不合法");
  }
  public ItemDtos.Page<Map<String,Object>> page(long actor,String scope,int page,int size,String keyword,String type,String category,String status,Long itemId){
    pageCheck(page,size);filterCheck(keyword,type,category,status);
    if(itemId!=null&&itemId<=0)throw new IllegalArgumentException("物品ID必须为正整数");
    if(scope.equals("admin"))accounts.requireAdministrator(actor);else verification.requireEligible(actor);
    var result=items.findAll((root,query,cb)->{
      var predicates=new ArrayList<jakarta.persistence.criteria.Predicate>();
      if(scope.equals("public"))predicates.add(cb.equal(root.get("status"),"APPROVED"));
      if(scope.equals("mine"))predicates.add(cb.equal(root.get("publisher").get("id"),actor));
      if(!keyword.isBlank()){
        String pattern="%"+keyword.toLowerCase(Locale.ROOT).replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%";
        predicates.add(cb.or(cb.like(cb.lower(root.get("title")),pattern,'\\'),cb.like(cb.lower(root.get("description")),pattern,'\\')));
      }
      if(!type.isEmpty())predicates.add(cb.equal(root.get("type"),type));
      if(category!=null&&!category.isEmpty())predicates.add(cb.equal(root.get("category"),category));
      if(!scope.equals("public")&&status!=null&&!status.isEmpty())predicates.add(cb.equal(root.get("status"),status));
      if(itemId!=null)predicates.add(cb.equal(root.get("id"),itemId));
      return cb.and(predicates.toArray(jakarta.persistence.criteria.Predicate[]::new));
    },PageRequest.of(page-1,size,Sort.by(Sort.Direction.DESC,"createdAt","id")));
    return new ItemDtos.Page<>(result.getContent().stream().map(this::summary).toList(),result.getTotalElements(),page,size);
  }
  private Map<String,Object> legacy(Item i){
    var m=new LinkedHashMap<String,Object>();m.put("id",i.getId());m.put("publisherId",i.getPublisherId());m.put("title",i.getTitle());m.put("description",i.getDescription());
    m.put("type",i.getType());m.put("category",i.getCategory());m.put("location",i.getLocation());m.put("occurredAt",i.getOccurredAt());m.put("status",i.getStatus());m.put("createdAt",i.getCreatedAt());return m;
  }
  private Map<String,Object> summary(Item i){
    var m=legacy(i);m.remove("description");m.put("publisherNickname",i.getPublisher().getNickname());m.put("closeReason",i.getCloseReason());
    m.put("createdAt",i.createdInstant(legacyZone));m.put("version",i.getVersion());m.put("contentVersion",i.getContentVersion());m.put("images",media.images(i.getId()));
    m.put("hasAcceptedClaim",false);m.put("myClaimId",null);return m;
  }
  private Map<String,Object> detail(Item i,long actor,boolean admin,int page,int size){
    var m=summary(i);m.put("description",i.getDescription());m.put("updatedAt",i.updatedInstant(legacyZone));
    if(admin||i.getPublisherId().equals(actor)){
      m.put("reviewReason",i.getReviewReason());
      var records=jdbc.query("SELECT id,event_type,occurred_at,user_reason FROM business_logs WHERE item_id=? ORDER BY id DESC LIMIT ? OFFSET ?",(rs,n)->{
        var event=new LinkedHashMap<String,Object>();event.put("id",rs.getLong("id"));event.put("action",rs.getString("event_type"));
        event.put("occurredAt",rs.getObject("occurred_at",LocalDateTime.class).toInstant(ZoneOffset.UTC));event.put("message",rs.getString("user_reason"));return event;
      },i.getId(),size,(page-1)*size);
      m.put("timeline",new ItemDtos.Page<>(records,jdbc.queryForObject("SELECT COUNT(*) FROM business_logs WHERE item_id=?",Long.class,i.getId()),page,size));
    }
    if(admin){var notes=jdbc.query("SELECT internal_note FROM business_logs WHERE item_id=? AND actor_kind='ADMIN' ORDER BY id DESC LIMIT 1",(rs,n)->rs.getString(1),i.getId());m.put("internalNote",notes.isEmpty()?null:notes.get(0));}
    return m;
  }
  public List<Map<String,Object>> matches(Long id,Long actor,boolean ignoredAdmin){
    Item base=items.findById(id).orElseThrow(ItemService::hidden);
    if(!base.getStatus().equals("APPROVED")&&!base.getPublisherId().equals(actor))throw hidden();
    return items.search("",base.getType().equals("LOST")?"FOUND":"LOST").stream().map(i->{
      var m=new LinkedHashMap<String,Object>();m.put("id",i.getId());m.put("title",i.getTitle());m.put("type",i.getType());m.put("location",i.getLocation());m.put("score",similarity(base,i));return (Map<String,Object>)m;
    }).filter(m->(double)m.get("score")>0).sorted(Comparator.<Map<String,Object>>comparingDouble(m->(double)m.get("score")).reversed().thenComparingLong(m->(long)m.get("id"))).limit(5).toList();
  }
  private Set<Integer> tokens(Item i){return (i.getTitle()+" "+i.getDescription()+" "+Objects.toString(i.getCategory(),"")+" "+Objects.toString(i.getLocation(),"")).toLowerCase(Locale.ROOT).codePoints().filter(Character::isLetterOrDigit).collect(HashSet::new,Set::add,Set::addAll);}
  private double similarity(Item a,Item b){var x=tokens(a);var y=tokens(b);var all=new HashSet<>(x);all.addAll(y);x.retainAll(y);return all.isEmpty()?0:Math.round(x.size()*10000.0/all.size())/100.0;}
}
