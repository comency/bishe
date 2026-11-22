package cn.edu.lostfound.notification;

import cn.edu.lostfound.claim.ClaimStore;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Writes allowlisted, PII-free notification events into the caller's business transaction. */
@Service
public class NotificationOutbox {
  private final JdbcTemplate jdbc;
  private final Clock clock;

  public NotificationOutbox(JdbcTemplate jdbc, Clock clock) {
    this.jdbc = jdbc;
    this.clock = clock;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void claimEvent(String action, ClaimStore.Row claim, Long actorId, boolean admin) {
    String type = switch (action) {
      case "CLAIM_APPLIED" -> "RESPONSE_RECEIVED";
      case "CLAIM_ACCEPTED" -> "RESPONSE_ACCEPTED";
      case "CLAIM_REJECTED" -> "RESPONSE_REJECTED";
      case "CLAIM_CANCELLED" -> admin ? null : "RESPONSE_WITHDRAWN";
      case "CLAIM_HANDOVER_CONFIRMED" -> "HANDOVER_CONFIRMED";
      case "CLAIM_RECEIPT_CONFIRMED" -> "RECEIPT_CONFIRMED";
      case "CLAIM_COMPLETED" -> "RETURN_COMPLETED";
      default -> null;
    };
    if (type == null) return;

    List<Long> recipients = switch (action) {
      case "CLAIM_APPLIED", "CLAIM_CANCELLED" -> List.of(claim.publisherId());
      case "CLAIM_ACCEPTED", "CLAIM_REJECTED" -> List.of(claim.applicantId());
      case "CLAIM_HANDOVER_CONFIRMED", "CLAIM_RECEIPT_CONFIRMED" -> actorId == null ? List.of()
          : List.of(actorId == claim.publisherId() ? claim.applicantId() : claim.publisherId());
      case "CLAIM_COMPLETED" -> List.of(claim.publisherId(), claim.applicantId());
      default -> List.of();
    };
    LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    for (long recipient : recipients) {
      String eventKey = "claim:" + claim.id() + ":v" + claim.version() + ":" + action + ":u" + recipient;
      jdbc.update("""
          INSERT IGNORE INTO notification_outbox
            (event_key,recipient_user_id,claim_id,item_type_snapshot,event_type,created_at,next_attempt_at)
          VALUES (?,?,?,?,?,?,?)
          """, eventKey, recipient, claim.id(), claim.itemTypeSnapshot(), type, now, now);
    }
  }

}
