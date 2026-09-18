# 正式配置启动门禁

此页描述`production` profile的安全失败条件，不表示当前系统已获真实校园部署批准。真实学校、核验责任、服务器、域名、证书、代理、备份与恢复责任仍须独立确认。

## 必填外部配置

以下变量不在仓库中提供默认业务值：

- `SERVER_ADDRESS`：仅允许`127.0.0.1`、`::1`或`localhost`，由同机HTTPS反向代理访问。
- `SERVER_PORT`：应用在回环地址监听的明确端口，必须为1–65535；不能回退开发端口。
- `TRUSTED_PROXY_ADDRESSES`：同机反向代理实际连接应用所使用的回环IP字面量，只允许`127.0.0.1`、`::1`或二者逗号分隔；不能填写主机名、端口或网段。
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
- 应用只监听回环，`server.forward-headers-strategy=none`；仅当直接连接方命中显式可信代理时，账号限流解析有界的`X-Forwarded-For`链；
- 会话有效期限定1–24小时；登录限流1–300次/分钟、注册和认证/认领限流1–60次/分钟，默认分别为24小时和60/30/12次；
- AI保持关闭；
- Hibernate仅`validate`，JPA禁止生成DDL和输出SQL，Flyway必须启用且禁止自动baseline/clean，SQL脚本初始化和open-in-view关闭；
- 数据库及Redis密码非空；
- Redis端口限定1–65535、库号限定0–63；生产错误响应禁止包含消息、绑定错误和堆栈；
- 媒体目录为绝对路径，临时媒体清理开启；
- CORS为空或全部为HTTPS origin。

外部高优先级配置不能绕过这些断言。任何一项失败时应用拒绝完成启动，而不是降级到开发值。

## 仍未解决的部署条件

- 反向代理必须终止HTTPS、限制请求体/超时，并覆盖或按可信规则追加`X-Forwarded-For`，同时保留边缘层限流。应用只在直接连接方命中`TRUSTED_PROXY_ADDRESSES`时从右向左选取首个非可信IP；未命中、格式异常、超过16跳或整条链均为可信代理时回退直接连接IP，客户端不能自行启用转发头信任。
- 数据库和Redis的传输加密、网络ACL与证书信任须按实际拓扑确定；只填变量不等于网络安全已验收。
- 正式备份频率、RPO/RTO、媒体与数据库一致性窗口、恢复责任和恢复后冒烟尚须签收。
- 仓库提供的`New-ConsistentBackup.ps1`只在操作者已停写并双重确认后导出数据库和媒体、生成哈希清单；它不调度停流、不上传备份、不自动恢复，也不能替代上述策略签收。
- 正式AI仍不放行；本地模型测试结果不能改变`production` profile的关闭状态。
- 启动成功不代表部署成功，仍须按`RELEASE-CHECKLIST.md`核对候选摘要、迁移、权限、真实HTTP/浏览器、观察与回滚。
- `/api/health/live`只检查进程HTTP，`/api/health/ready`只读检查MySQL与Redis；探针固定返回UP/DOWN且不披露依赖信息。正式代理只能在ready为200后接流量，业务冒烟仍须另做。

实现依据为Spring Boot 3.4的profile-specific配置及外部配置覆盖规则。门禁测试不连接数据库、Redis或网络。
