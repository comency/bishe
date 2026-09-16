package cn.edu.lostfound.config;

import java.time.ZoneId;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("app.campus")
public record CampusProperties(
    @DefaultValue("TEST_CAMPUS") String campusId,
    String campusName,
    @DefaultValue("Asia/Shanghai") String timezone,
    @DefaultValue("true") boolean testMode,
    @DefaultValue("测试环境：仅使用合成资料演示。请联系测试管理员，通过当面出示或授权名单核对本人、账号与当前在校身份；系统不接收证件图片。") String verificationInstructions,
    String supportContact) {
  public CampusProperties {
    ZoneId.of(timezone);
    if (campusId == null || campusId.isBlank() || campusId.length() > 64) {
      throw new IllegalArgumentException("必须配置有效的校园标识");
    }
    if (!testMode && (campusId.startsWith("TEST_") || campusName == null || campusName.isBlank()
        || verificationInstructions == null || verificationInstructions.isBlank()
        || supportContact == null || supportContact.isBlank())) {
      throw new IllegalArgumentException("真实环境必须配置独立校园标识、学校、核验指引与支持渠道");
    }
  }

  public ZoneId zoneId() { return ZoneId.of(timezone); }
}
