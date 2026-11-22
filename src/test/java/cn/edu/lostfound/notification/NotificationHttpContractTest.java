package cn.edu.lostfound.notification;

import cn.edu.lostfound.security.UserContext;
import cn.edu.lostfound.verification.VerificationApi;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class NotificationHttpContractTest {
  private static final long ACTOR = 42L;
  private JdbcTemplate jdbc;
  private VerificationApi verification;
  private MockMvc mvc;

  @BeforeEach void setup() {
    jdbc = mock(JdbcTemplate.class);
    verification = mock(VerificationApi.class);
    mvc = standaloneSetup(new NotificationService(jdbc, verification)).build();
    UserContext.set(ACTOR, "USER");
  }

  @AfterEach void clearContext() { UserContext.clear(); }

  @Test void unreadCountIsScopedToTheAuthenticatedUser() throws Exception {
    when(jdbc.queryForObject(contains("WHERE user_id=? AND read_at IS NULL"), eq(Long.class), eq(ACTOR)))
        .thenReturn(3L);

    mvc.perform(get("/api/notifications/unread-count"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0))
        .andExpect(jsonPath("$.data.count").value(3));

    verify(verification).requireEligible(ACTOR);
    verify(jdbc).queryForObject(contains("WHERE user_id=? AND read_at IS NULL"), eq(Long.class), eq(ACTOR));
  }

  @Test void listReturnsOnlyRowsSelectedForTheAuthenticatedUser() throws Exception {
    when(jdbc.queryForObject(contains("SELECT COUNT(*) FROM notifications WHERE user_id=?"), eq(Long.class), eq(ACTOR)))
        .thenReturn(0L);
    when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());

    mvc.perform(get("/api/notifications").param("page", "1").param("pageSize", "20"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0))
        .andExpect(jsonPath("$.data.records").isArray())
        .andExpect(jsonPath("$.data.records.length()").value(0))
        .andExpect(jsonPath("$.data.total").value(0));

    verify(verification).requireEligible(ACTOR);
    verify(jdbc).queryForObject(contains("SELECT COUNT(*) FROM notifications WHERE user_id=?"), eq(Long.class), eq(ACTOR));
  }

  @Test void markAllReadChangesOnlyUnreadRowsForTheAuthenticatedUser() throws Exception {
    when(jdbc.update(contains("UPDATE notifications SET read_at=? WHERE user_id=? AND read_at IS NULL"),
        any(LocalDateTime.class), eq(ACTOR))).thenReturn(2);

    mvc.perform(post("/api/notifications/read-all"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0))
        .andExpect(jsonPath("$.data.updatedCount").value(2));

    verify(verification).requireEligible(ACTOR);
    verify(jdbc).update(contains("UPDATE notifications SET read_at=? WHERE user_id=? AND read_at IS NULL"),
        any(LocalDateTime.class), eq(ACTOR));
  }
}
