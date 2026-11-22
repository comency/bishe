package cn.edu.lostfound.notification;

import cn.edu.lostfound.common.BusinessException;
import cn.edu.lostfound.dto.ItemDtos;
import cn.edu.lostfound.security.UserContext;
import cn.edu.lostfound.verification.VerificationApi;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import cn.edu.lostfound.dto.ApiResponse;

@RestController
@RequestMapping("/api/notifications")
public class NotificationService {
  private final JdbcTemplate jdbc;
  private final VerificationApi verification;

  public NotificationService(JdbcTemplate jdbc, VerificationApi verification) {
    this.jdbc = jdbc;
    this.verification = verification;
  }

  @GetMapping
  @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
  public ApiResponse<?> page(@RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int pageSize,
      @RequestParam(defaultValue = "false") boolean unreadOnly) {
    long actor = eligibleActor();
    checkPage(page, pageSize);
    String where = " WHERE user_id=?" + (unreadOnly ? " AND read_at IS NULL" : "");
    long total = jdbc.queryForObject("SELECT COUNT(*) FROM notifications" + where, Long.class, actor);
    var records = jdbc.query("""
        SELECT id,type,title,summary,related_type,related_id,created_at,read_at
        FROM notifications
        """ + where + " ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?", (rs, n) -> {
      var row = new LinkedHashMap<String, Object>();
      row.put("id", rs.getLong("id")); row.put("type", rs.getString("type"));
      row.put("title", rs.getString("title")); row.put("summary", rs.getString("summary"));
      row.put("relatedType", rs.getString("related_type")); row.put("relatedId", rs.getLong("related_id"));
      row.put("createdAt", instant(rs.getObject("created_at", LocalDateTime.class)));
      row.put("readAt", instant(rs.getObject("read_at", LocalDateTime.class)));
      return row;
    }, actor, pageSize, (page - 1) * pageSize);
    return ApiResponse.ok(new ItemDtos.Page<>(records, total, page, pageSize));
  }

  @GetMapping("/unread-count")
  @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
  public ApiResponse<?> unreadCount() {
    long actor = eligibleActor();
    return ApiResponse.ok(Map.of("count", jdbc.queryForObject(
        "SELECT COUNT(*) FROM notifications WHERE user_id=? AND read_at IS NULL", Long.class, actor)));
  }

  @PostMapping("/{id}/read")
  @Transactional(isolation = Isolation.READ_COMMITTED)
  public ApiResponse<?> markRead(@PathVariable long id) {
    long actor = eligibleActor();
    if (id <= 0) throw hidden();
    LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
    int changed = jdbc.update("UPDATE notifications SET read_at=COALESCE(read_at,?) WHERE id=? AND user_id=?",
        now, id, actor);
    if (changed == 0 && jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE id=? AND user_id=?",
        Long.class, id, actor) == 0) throw hidden();
    var readAt = jdbc.queryForObject("SELECT read_at FROM notifications WHERE id=? AND user_id=?",
        LocalDateTime.class, id, actor);
    return ApiResponse.ok(Map.of("id", id, "readAt", instant(readAt)));
  }

  @PostMapping("/read-all")
  @Transactional(isolation = Isolation.READ_COMMITTED)
  public ApiResponse<?> markAllRead() {
    long actor = eligibleActor();
    int updated = jdbc.update("UPDATE notifications SET read_at=? WHERE user_id=? AND read_at IS NULL",
        LocalDateTime.now(ZoneOffset.UTC), actor);
    return ApiResponse.ok(Map.of("updatedCount", updated));
  }

  private long eligibleActor() {
    long actor = UserContext.id();
    verification.requireEligible(actor);
    return actor;
  }

  private static BusinessException hidden() {
    return new BusinessException(404, "NOT_ACCESSIBLE", "通知不存在或不可访问");
  }

  private static void checkPage(int page, int pageSize) {
    if (page < 1 || pageSize < 1 || pageSize > 50 || (long) (page - 1) * pageSize > Integer.MAX_VALUE)
      throw new IllegalArgumentException("通知分页参数不合法");
  }

  private static Object instant(LocalDateTime value) {
    return value == null ? null : value.toInstant(ZoneOffset.UTC);
  }
}
