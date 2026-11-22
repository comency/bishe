package cn.edu.lostfound.notification;

import java.time.*;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Polls the MySQL outbox. One transaction per event makes delivery and retry independent. */
@Component
@ConditionalOnProperty(prefix = "app.notifications", name = "enabled", havingValue = "true", matchIfMissing = true)
public class NotificationDelivery {
  private static final Logger log = LoggerFactory.getLogger(NotificationDelivery.class);
  private final JdbcTemplate jdbc;
  private final TransactionTemplate transaction;
  private final Clock clock;

  public NotificationDelivery(JdbcTemplate jdbc, PlatformTransactionManager transactions, Clock clock) {
    this.jdbc = jdbc;
    this.transaction = new TransactionTemplate(transactions);
    this.clock = clock;
  }

  @Scheduled(fixedDelayString = "${app.notifications.poll-delay-ms:3000}")
  public void deliverPending() {
    List<Long> ids = jdbc.queryForList("""
        SELECT id FROM notification_outbox
        WHERE processed_at IS NULL AND next_attempt_at<=UTC_TIMESTAMP(6)
        ORDER BY id LIMIT 30
        """, Long.class);
    int delivered = 0;
    for (long id : ids) {
      try {
        Boolean done = transaction.execute(status -> deliverOne(id));
        if (Boolean.TRUE.equals(done)) delivered++;
      } catch (RuntimeException failure) {
        recordFailure(id);
      }
    }
    if (delivered > 0) log.debug("Delivered {} application notifications", delivered);
  }

  @Scheduled(fixedDelayString = "${app.notifications.metrics-delay-ms:60000}")
  public void reportBacklog() {
    Map<String, Object> stats = jdbc.queryForMap("""
        SELECT COUNT(*) AS pending_count,
          COALESCE(TIMESTAMPDIFF(SECOND,MIN(created_at),UTC_TIMESTAMP(6)),0) AS oldest_pending_seconds,
          COALESCE(SUM(CASE WHEN attempts>0 THEN 1 ELSE 0 END),0) AS retried_count
        FROM notification_outbox WHERE processed_at IS NULL
        """);
    long pending = ((Number) stats.get("pending_count")).longValue();
    if (pending > 0) log.info("Notification outbox backlog: pending={}, oldestSeconds={}, retried={}",
        pending, stats.get("oldest_pending_seconds"), stats.get("retried_count"));
  }

  private Boolean deliverOne(long id) {
    var rows = jdbc.query("""
        SELECT id,event_key,recipient_user_id,claim_id,item_type_snapshot,event_type,processed_at
        FROM notification_outbox WHERE id=? FOR UPDATE
        """, (rs, n) -> Map.<String, Object>of(
        "eventKey", rs.getString("event_key"), "userId", rs.getLong("recipient_user_id"),
        "claimId", rs.getLong("claim_id"), "itemType", rs.getString("item_type_snapshot"),
        "eventType", rs.getString("event_type"), "processed", rs.getTimestamp("processed_at") != null), id);
    if (rows.isEmpty() || Boolean.TRUE.equals(rows.getFirst().get("processed"))) return false;
    Map<String, Object> row = rows.getFirst();
    String itemType = (String) row.get("itemType");
    String eventType = (String) row.get("eventType");
    var copy = copy(itemType, eventType);
    LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    jdbc.update("""
        INSERT IGNORE INTO notifications
          (user_id,source_event_key,type,title,summary,related_type,related_id,created_at)
        VALUES (?,?,?,?,?,'CLAIM',?,?)
        """, row.get("userId"), row.get("eventKey"), eventType, copy.title(), copy.summary(), row.get("claimId"), now);
    jdbc.update("UPDATE notification_outbox SET processed_at=?,last_error_code=NULL WHERE id=? AND processed_at IS NULL",
        now, id);
    return true;
  }

  private void recordFailure(long id) {
    try {
      transaction.executeWithoutResult(status -> {
        Integer attempts = jdbc.query("SELECT attempts FROM notification_outbox WHERE id=? AND processed_at IS NULL FOR UPDATE",
            (rs, n) -> rs.getInt(1), id).stream().findFirst().orElse(null);
        if (attempts == null) return;
        long seconds = Math.min(3600L, 5L << Math.min(attempts, 9));
        LocalDateTime next = LocalDateTime.ofInstant(clock.instant().plusSeconds(seconds), ZoneOffset.UTC);
        jdbc.update("UPDATE notification_outbox SET attempts=attempts+1,next_attempt_at=?,last_error_code='DELIVERY_FAILED' WHERE id=?",
            next, id);
      });
    } catch (RuntimeException retryFailure) {
      log.error("Unable to record notification delivery retry; event id {} remains pending", id);
    }
  }

  private static NotificationCopy copy(String itemType, String eventType) {
    boolean lost = "LOST".equals(itemType);
    return switch (eventType) {
      case "RESPONSE_RECEIVED" -> new NotificationCopy(lost ? "收到新的拾获线索" : "收到新的认领申请",
          lost ? "有人回应了你的寻物启事，查看线索并决定是否接受。" : "有人申请认领你的招领物品，查看申请并决定是否接受。");
      case "RESPONSE_ACCEPTED" -> new NotificationCopy(lost ? "拾获线索已接受" : "认领申请已接受",
          "发布者已接受回应，可在记录详情中查看本次交接信息。");
      case "RESPONSE_REJECTED" -> new NotificationCopy(lost ? "拾获线索已结束" : "认领申请未被接受",
          "这条回应已结束。请在记录详情中查看处理结果。");
      case "RESPONSE_WITHDRAWN" -> new NotificationCopy(lost ? "拾获线索已撤回" : "认领申请已取消",
          "对方已结束这条回应，可查看记录状态。");
      case "HANDOVER_CONFIRMED" -> new NotificationCopy("交接有新进展",
          "对方已确认交出物品；请在完成实际交接后确认收到。");
      case "RECEIPT_CONFIRMED" -> new NotificationCopy("交接有新进展",
          "对方已确认收到物品；请在完成实际交接后确认交出。");
      case "RETURN_COMPLETED" -> new NotificationCopy(lost ? "寻物启事已完成归还" : "招领物品已完成归还",
          "双方已确认物品交接完成。");
      default -> throw new IllegalArgumentException("Unsupported notification event");
    };
  }

  private record NotificationCopy(String title, String summary) {}
}
