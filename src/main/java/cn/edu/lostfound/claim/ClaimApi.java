package cn.edu.lostfound.claim;

import cn.edu.lostfound.audit.AuditApi;
import cn.edu.lostfound.common.PageItemIds;
import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.annotation.*;

/** Narrow item-module boundary. Every write requires the caller to own the item lock. */
@Service
public class ClaimApi {
  private final ClaimStore store;
  private final AuditApi audit;
  private final Clock clock;
  private final org.springframework.jdbc.core.JdbcTemplate jdbc;
  private final NamedParameterJdbcTemplate namedJdbc;
  public ClaimApi(ClaimStore store,AuditApi audit,Clock clock,org.springframework.jdbc.core.JdbcTemplate jdbc){this.store=store;this.audit=audit;this.clock=clock;this.jdbc=jdbc;this.namedJdbc=new NamedParameterJdbcTemplate(jdbc);}
  @Transactional(propagation=Propagation.MANDATORY)
  public void ensureNoActive(long itemId){
    if(jdbc.queryForObject("SELECT COUNT(*) FROM claims WHERE item_id=? AND status IN ('APPLIED','ACCEPTED')",Long.class,itemId)>0)
      throw ClaimService.conflict("STATE_CONFLICT","存在进行中的认领，暂时不能编辑或自行关闭");
  }
  @Transactional(propagation=Propagation.MANDATORY)
  public void adminClosing(long itemId,long actor,String reason){
    var rows=store.lockItem(itemId);
    if(rows.stream().anyMatch(r->r.status().equals("ACCEPTED")&&r.anyConfirmed()))
      throw ClaimService.conflict("STATE_CONFLICT","已有单方交接确认，请从认领异常处置入口处理");
    for(var r:rows)if(Set.of("APPLIED","ACCEPTED").contains(r.status()))
      end(r,r.status().equals("APPLIED")?"REJECTED":"CANCELLED","ADMIN_REMOVED",reason,false,actor,true);
  }
  void end(ClaimStore.Row r,String status,String code,String reason,boolean exceptional,Long actor,boolean admin){
    store.end(r,status,code,reason,exceptional,clock.instant());
    audit.claim("CLAIM_"+status,actor,admin,r.itemId(),r.id(),r.status(),status,r.version()+1,code,reason,null,null);
  }
  @Transactional(readOnly=true,isolation=Isolation.READ_COMMITTED)
  public boolean hasAccepted(long itemId){return jdbc.queryForObject("SELECT COUNT(*) FROM claims WHERE item_id=? AND status='ACCEPTED'",Long.class,itemId)>0;}
  @Transactional(readOnly=true,isolation=Isolation.READ_COMMITTED)
  public Long myClaim(long itemId,long actor){return jdbc.queryForList("SELECT id FROM claims WHERE item_id=? AND applicant_id=?",Long.class,itemId,actor).stream().findFirst().orElse(null);}

  public record PageSummary(boolean hasAcceptedClaim,Long myClaimId){
    public static final PageSummary EMPTY=new PageSummary(false,null);
  }
  /** Caller supplies an authorized page. Only the current applicant's ID is projected, never others' evidence/contact. */
  @Transactional(readOnly=true,isolation=Isolation.READ_COMMITTED)
  public Map<Long,PageSummary> summariesForItems(Collection<Long> visibleItemIds,long actor){
    var ids=PageItemIds.copyOf(visibleItemIds);
    if(actor<=0)throw new IllegalArgumentException("Invalid page actor");
    if(ids.isEmpty())return Map.of();
    var rows=namedJdbc.query("""
        SELECT item_id,MAX(CASE WHEN status='ACCEPTED' THEN 1 ELSE 0 END) AS has_accepted,
          MAX(CASE WHEN applicant_id=:actor THEN id ELSE NULL END) AS my_claim_id
        FROM claims WHERE item_id IN (:ids) AND (status='ACCEPTED' OR applicant_id=:actor) GROUP BY item_id
        """,Map.of("ids",ids,"actor",actor),(r,n)->{
          long mine=r.getLong("my_claim_id");Long ownId=r.wasNull()?null:mine;
          return Map.entry(r.getLong("item_id"),new PageSummary(r.getInt("has_accepted")!=0,ownId));
        });
    Map<Long,PageSummary> result=new HashMap<>();
    for(var row:rows)result.put(row.getKey(),row.getValue());return Map.copyOf(result);
  }
}
