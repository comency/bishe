# 正式配置启动门禁

此页描述`production` profile的安全失败条件，不表示当前系统已获真实校园部署批准。真实学校、核验责任、服务器、域名、证书、代理、备份与恢复责任仍须独立确认。

## 必填外部配置

以下变量不在仓库中提供默认业务值：

- `SERVER_ADDRESS`：仅允许`127.0.0.1`、`::1`或`localhost`，由同机HTTPS反向代理访问。
- `DB_HOST`、`DB_PORT`、`DB_NAME`、`DB_USERNAME`、`DB_PASSWORD`：正式MySQL专用最小权限账号。
- `REDIS_HOST`、`REDIS_PORT`、`REDIS_PASSWORD`：正式Redis；可选`REDIS_USERNAME`、`REDIS_DATABASE`。
- `MEDIA_ROOT`：独立持久存储的绝对路径，不能使用仓库内`.local`相对目录。
- `CAMPUS_ID`、`CAMPUS_NAME`、`CAMPUS_VERIFICATION_INSTRUCTIONS`、`CAMPUS_SUPPORT_CONTACT`：由校方/责任人确认的正式内容，禁止`TEST_`校园标识。
- `CORS_ALLOWED_ORIGINS`：同源部署留空；跨域时只能填写逗号分隔的明确HTTPS origin。
- `ADMIN_PASSWORD`：仅首次初始化且数据库中不存在管理员时使用；应通过受保护配置注入，已有管理员时可留空。

不要把密码写入命令行、Git、前端包、截图或阶段报告。部署平台应通过秘密文件/秘密存储注入，并限制读取权限。

## 固定保护

`ProductionConfiguration`在`production` profile启动时再次核对：

- `app.campus.test-mode=false`；
- 应用只监听回环，`server.forward-headers-strategy=none`；
- AI保持关闭；
- Hibernate仅`validate`，Flyway禁止自动baseline与clean，open-in-view关闭；
- 数据库及Redis密码非空；
- 媒体目录为绝对路径，临时媒体清理开启；
- CORS为空或全部为HTTPS origin。

外部高优先级配置不能绕过这些断言。任何一项失败时应用拒绝完成启动，而不是降级到开发值。

## 仍未解决的部署条件

- 反向代理必须终止HTTPS、限制请求体/超时，并实现可信来源IP和登录/注册限流。应用当前明确不读取`X-Forwarded-For`等客户端可伪造头。
- 数据库和Redis的传输加密、网络ACL与证书信任须按实际拓扑确定；只填变量不等于网络安全已验收。
- 正式备份频率、RPO/RTO、媒体与数据库一致性窗口、恢复责任和恢复后冒烟尚须签收。
- 正式AI仍不放行；本地模型测试结果不能改变`production` profile的关闭状态。
- 启动成功不代表部署成功，仍须按`RELEASE-CHECKLIST.md`核对候选摘要、迁移、权限、真实HTTP/浏览器、观察与回滚。

实现依据为Spring Boot 3.4的profile-specific配置及外部配置覆盖规则。门禁测试不连接数据库、Redis或网络。
