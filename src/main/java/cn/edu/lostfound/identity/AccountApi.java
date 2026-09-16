package cn.edu.lostfound.identity;

import cn.edu.lostfound.common.BusinessException;
import cn.edu.lostfound.config.CampusProperties;
import cn.edu.lostfound.entity.User;
import cn.edu.lostfound.repository.UserRepository;
import org.springframework.stereotype.Service;

/** Current account facts; roles are deliberately not authorized from the Redis snapshot. */
@Service
public class AccountApi {
  private final UserRepository users;
  private final CampusProperties campus;
  public AccountApi(UserRepository users, CampusProperties campus) { this.users=users; this.campus=campus; }

  public User current(Long userId) {
    User user = users.findById(userId)
        .orElseThrow(() -> new BusinessException(401, "AUTH_REQUIRED", "请先登录"));
    if (user.isTest()!=campus.testMode()) {
      throw new BusinessException(403, "FORBIDDEN", "账号不属于当前运行环境，请联系支持人员");
    }
    return user;
  }

  public String currentRole(Long userId) { return current(userId).getRole(); }

  public void requireAdministrator(Long userId) {
    if (!"ADMIN".equals(currentRole(userId))) {
      throw new BusinessException(403, "FORBIDDEN", "仅管理员可操作");
    }
  }
}
