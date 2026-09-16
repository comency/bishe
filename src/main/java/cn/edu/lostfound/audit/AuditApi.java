package cn.edu.lostfound.audit;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Whitelisted verification events, appended in the caller's transaction; never accepts evidence. */
@Service
public class AuditApi {
  private static final Set<String> EVENTS=Set.of("VERIFICATION_SUBMITTED","VERIFICATION_APPROVED",
      "VERIFICATION_REJECTED","VERIFICATION_REVOKED","VERIFICATION_REOPENED");
  private final JdbcTemplate jdbc;
  private final Clock clock;
  public AuditApi(JdbcTemplate jdbc,Clock clock) { this.jdbc=jdbc;this.clock=clock; }

  @Transactional(propagation=Propagation.MANDATORY)
  public void claim(String event,Long actorId,boolean admin,long itemId,long claimId,String from,String to,
      long version,String code,String reason,String conclusion,String note) {
    if(!Set.of("CLAIM_APPLIED","CLAIM_ACCEPTED","CLAIM_REJECTED","CLAIM_CANCELLED","CLAIM_HANDOVER_CONFIRMED",
        "CLAIM_RECEIPT_CONFIRMED","CLAIM_COMPLETED","CLAIM_RESOLVED_CONTINUE","CLAIM_RESOLVED_TERMINATE").contains(event))
      throw new IllegalArgumentException("Unsupported claim event");
    jdbc.update("""
      INSERT INTO business_logs(event_type,actor_id,actor_kind,item_id,claim_id,from_state,to_state,object_version,
      reason_code,user_reason,resolution_conclusion,internal_note,trace_id,occurred_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
      """,event,actorId,actorId==null?"SYSTEM":admin?"ADMIN":"USER",itemId,claimId,from,to,version,code,reason,conclusion,note,
      UUID.randomUUID().toString(),LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC));
  }

  @Transactional(propagation=Propagation.MANDATORY)
  public void item(String event,Long actorId,boolean admin,Long owner,Long itemId,String from,String to,
      long version,long contentVersion,String reason,String internalNote) {
    if(!Set.of("ITEM_CREATED","ITEM_EDITED","ITEM_APPROVED","ITEM_REJECTED","ITEM_CLOSED").contains(event))
      throw new IllegalArgumentException("Unsupported item event");
    jdbc.update("""
      INSERT INTO business_logs(event_type,actor_id,actor_kind,subject_user_id,item_id,from_state,to_state,
      object_version,content_version,user_reason,internal_note,trace_id,occurred_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)
      """,event,actorId,admin?"ADMIN":"USER",owner,itemId,from,to,version,contentVersion,reason,internalNote,
      UUID.randomUUID().toString(),LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC));
  }

  @Transactional(propagation=Propagation.MANDATORY)
  public void verification(String event,Long actorId,boolean admin,Long subjectUserId,Long applicationId,
      String fromState,String toState,long version,String reason) {
    if(!EVENTS.contains(event)) throw new IllegalArgumentException("Unsupported audit event");
    jdbc.update("""
        INSERT INTO business_logs(event_type,actor_id,actor_kind,subject_user_id,verification_application_id,
        from_state,to_state,object_version,user_reason,trace_id,occurred_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)
        """,event,actorId,admin?"ADMIN":"USER",subjectUserId,applicationId,fromState,toState,version,
        reason,UUID.randomUUID().toString(),LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC));
  }
}
