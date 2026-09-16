package cn.edu.lostfound.identity;

import cn.edu.lostfound.common.BusinessException;
import cn.edu.lostfound.entity.User;
import cn.edu.lostfound.repository.UserRepository;
import cn.edu.lostfound.verification.VerificationApi;
import java.time.Clock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountWorkflow {
  private final AccountApi accounts;
  private final UserRepository users;
  private final VerificationApi verification;
  private final Clock clock;

  public AccountWorkflow(AccountApi accounts, UserRepository users, VerificationApi verification, Clock clock) {
    this.accounts=accounts; this.users=users; this.verification=verification; this.clock=clock;
  }

  @Transactional(readOnly=true, isolation=Isolation.READ_COMMITTED)
  public AccountDtos.UserMe me(Long userId) { return view(accounts.current(userId)); }

  @Transactional(isolation=Isolation.READ_COMMITTED)
  public AccountDtos.UserMe update(Long userId, AccountDtos.UpdateMe request) {
    verification.lockForAccount(userId);
    User user=accounts.current(userId);
    if (user.getVersion()!=request.expectedVersion()) {
      throw new BusinessException(409, "VERSION_CONFLICT", "资料已更新，请刷新后重试");
    }
    String contact=request.contact()==null || request.contact().isBlank() ? null : request.contact().strip();
    user.updateProfile(request.nickname().strip(), contact, clock.instant());
    users.flush();
    return view(user);
  }

  private AccountDtos.UserMe view(User user) {
    return new AccountDtos.UserMe(user.getId(), user.getUsername(), user.getNickname(),
        user.getContact(), user.getRole(), user.getVersion(), verification.summary(user.getId()));
  }
}
