package cn.edu.lostfound.identity;

import cn.edu.lostfound.common.BusinessException;
import cn.edu.lostfound.config.CampusProperties;
import cn.edu.lostfound.entity.User;
import cn.edu.lostfound.repository.UserRepository;
import cn.edu.lostfound.verification.VerificationApi;
import cn.edu.lostfound.verification.VerificationDtos.VerificationSummary;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AccountWorkflowTest {
  private final UserRepository users = mock(UserRepository.class);
  private final CampusProperties campus = new CampusProperties("TEST_CAMPUS", null,
      "Asia/Shanghai", true, "Synthetic test instructions", null);
  private final AccountApi accounts = new AccountApi(users, campus);
  private final VerificationApi verification = mock(VerificationApi.class);
  private final Clock clock = Clock.fixed(Instant.parse("2026-09-16T01:00:00Z"), ZoneOffset.UTC);
  private final AccountWorkflow workflow = new AccountWorkflow(accounts, users, verification, clock);

  @Test
  void currentRoleAlwaysComesFromTheAccountRecord() {
    User user = account();
    assertThat(accounts.currentRole(7L)).isEqualTo("USER");
    ReflectionTestUtils.setField(user, "role", "ADMIN");
    assertThat(accounts.currentRole(7L)).isEqualTo("ADMIN");
  }

  @Test
  void removedAccountIsUnauthenticated() {
    assertThatThrownBy(() -> accounts.currentRole(999L))
        .isInstanceOfSatisfying(BusinessException.class, error -> {
          assertThat(error.getHttpStatus()).isEqualTo(401);
          assertThat(error.getErrorCode()).isEqualTo("AUTH_REQUIRED");
        });
  }

  @Test
  void mismatchedEnvironmentCannotUseCurrentRoleOrReadSelfProfile() {
    User user = account();
    user.initializeEnvironment(false, clock.instant());
    assertThatThrownBy(() -> accounts.currentRole(7L))
        .isInstanceOfSatisfying(BusinessException.class, error -> {
          assertThat(error.getHttpStatus()).isEqualTo(403);
          assertThat(error.getErrorCode()).isEqualTo("FORBIDDEN");
        });
    assertThatThrownBy(() -> workflow.me(7L)).isInstanceOf(BusinessException.class);
    verifyNoInteractions(verification);
  }

  @Test
  void selfProfileDoesNotExposeCredentialsAndUsesCurrentQualification() throws Exception {
    account();
    var summary = mock(VerificationSummary.class);
    when(verification.summary(7L)).thenReturn(summary);

    var me = workflow.me(7L);

    assertThat(me.userId()).isEqualTo(7L);
    assertThat(me.username()).isEqualTo("student");
    assertThat(me.verification()).isSameAs(summary);
    var json = new ObjectMapper().valueToTree(me);
    assertThat(json.has("password")).isFalse();
    assertThat(json.has("passwordHash")).isFalse();
    verify(verification, never()).requireEligible(any());
    verify(verification, never()).lockForAccount(any());
  }

  @Test
  void updateLocksQualificationBeforeAccountAndChangesOnlyAllowedProfileFields() {
    User user = account();
    var result = workflow.update(7L, new AccountDtos.UpdateMe("  新昵称  ", "  TEST-CONTACT  ", 2L));

    assertThat(result.nickname()).isEqualTo("新昵称");
    assertThat(result.contact()).isEqualTo("TEST-CONTACT");
    assertThat(user.getUsername()).isEqualTo("student");
    assertThat(user.getPassword()).isEqualTo("synthetic-password-hash");
    assertThat(user.getRole()).isEqualTo("USER");
    assertThat(user.getPhone()).isEqualTo("synthetic-legacy-phone");
    assertThat(user.isTest()).isTrue();
    var order = inOrder(verification, users);
    order.verify(verification).lockForAccount(7L);
    order.verify(users).findById(7L);
    order.verify(users).flush();
    order.verify(verification).summary(7L);
    verify(verification, never()).requireEligible(any());
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = {"", "  "})
  void explicitNullOrBlankContactClearsIt(String contact) {
    account();
    var result = workflow.update(7L, new AccountDtos.UpdateMe("新昵称", contact, 2L));
    assertThat(result.contact()).isNull();
    verify(users).flush();
  }

  @Test
  void staleProfileVersionCannotWriteOrReturnSuccessfulUpdate() {
    User user = account();

    assertThatThrownBy(() -> workflow.update(7L, new AccountDtos.UpdateMe("stale", null, 1L)))
        .isInstanceOfSatisfying(BusinessException.class, error -> {
          assertThat(error.getHttpStatus()).isEqualTo(409);
          assertThat(error.getErrorCode()).isEqualTo("VERSION_CONFLICT");
        });

    assertThat(user.getNickname()).isEqualTo("旧昵称");
    assertThat(user.getContact()).isEqualTo("old-contact");
    verify(users, never()).flush();
    verify(verification, never()).summary(any());
  }

  private User account() {
    User user = new User("student", "synthetic-password-hash", "旧昵称", "USER");
    user.initializeEnvironment(true, clock.instant());
    user.updateProfile("旧昵称", "old-contact", clock.instant());
    user.setPhone("synthetic-legacy-phone");
    ReflectionTestUtils.setField(user, "id", 7L);
    ReflectionTestUtils.setField(user, "version", 2L);
    when(users.findById(7L)).thenReturn(Optional.of(user));
    return user;
  }
}
