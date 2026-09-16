# 校园失物招领智能管理系统

本项目是一个适合毕业设计的 Spring Boot + Vue 校园应用：使用 MySQL 保存业务数据，Redis 管理会话及账号入口限流，并预留 AI 文案优化与问答。

## 功能

- 用户注册、登录、退出（Redis Token 会话）
- 本人资料与联系方式维护，版本冲突保护
- 人工在校身份申请、审核、驳回重提、有效期、撤销及允许重核
- 本人认证历史、管理员审核页面及事务审计；测试认证明确标识
- 发布、编辑、查看本人失物/招领信息
- 登录后搜索已审核信息；管理员审核信息
- 基于字符集合的失物/招领相似度匹配
- 现有物品与 AI 入口检查实时校园资格；管理员通过独立管理入口审核
- AI 优化发布文案、失物招领问答（可按需启用）

## 开发基线与范围

账号与人工在校身份审核阶段已实现，包含文档 API-01～13 及升级准入后的原物品/AI接口，共22个操作。前端包含注册登录、本人资料、认证及管理审核页面。图片、完整发布与内容审核页面、分页详情、认领/双向交接仍属下一阶段；本机使用合成测试校园，尚未满足真实校园上线条件。

| 环境 | MySQL（本机 Windows 服务） | 独立 Redis | 后端 | 前端 |
| --- | --- | --- | --- | --- |
| 开发 | `127.0.0.1:13306/lost_found` | `127.0.0.1:16379` | `127.0.0.1:8080` | `127.0.0.1:5174` |
| 集成测试 | `127.0.0.1:13306/lost_found_test` | `127.0.0.1:16380` | `127.0.0.1:18080` | `127.0.0.1:15174`，`npm run dev:integration` |

商城的 `3306`、`6379`、`5173` 不属于本项目。测试库虽然单独授权，仍与开发库共用本机 MySQL 进程；数据库停机/磁盘故障测试需另建隔离实例。

## 首次准备

要求 JDK 21、Maven 3.9.x、Node 22.18+（22.x）、npm 10/11、Docker Desktop Linux 容器，以及本地 MySQL 8.0。具体前端依赖由 `frontend/package-lock.json` 锁定。不要修改商城工具或全局 Java 版本来启动本项目。

从仓库根目录执行：

```powershell
.\scripts\dev.ps1 doctor
.\scripts\dev.ps1 verify
.\scripts\dev.ps1 redis-up
```

`dev.ps1` 自动选择本机 Java 21，也可传 `-JavaHome`；只修改子进程环境，结束后恢复。Maven 会在使用 Java 25 等错误版本时提前拒绝构建。

数据库必须先确认实例、库内容和账号权限。`scripts/inspect-database.mjs` 只读检查固定的 `127.0.0.1:13306`，从当前进程读取 `DB_USERNAME`、`DB_PASSWORD`，不输出密码或业务记录。

仅在两个库及专用账号均不存在的**全新本机环境**，可使用：

```powershell
.\scripts\initialize-local-database.ps1 -InitializeEmpty
```

该初始化工具从 Windows 用户环境变量读取管理账号 `DB_USERNAME`、`DB_PASSWORD`；创建 `lost_found`、`lost_found_test` 和各自的专用 localhost 账号，不修改已有账号。它将随机生成的应用/测试管理员凭据保存为 `.local/*.credential.xml`，使用 Windows DPAPI 加密且限制本机文件权限。凭据只能由原 Windows 用户在原机器解密，不进 Git；换电脑须重新配置。初始化不是事务，失败时保留凭据并检查已创建内容，不能删除后盲目重跑。

**已经完成初始化的本机不要再次运行初始化工具。** 存量库必须先备份、比较结构，再单独确定接入迁移的方法；不得开启自动 baseline 来绕过检查。

## 日常启动

确认数据库后，在两个终端分别运行：

```powershell
# 终端一：先启动本项目 Redis，再启动后端。
.\scripts\dev.ps1 redis-up
.\scripts\dev.ps1 backend -DatabaseChecked -UseLocalCredentials
```

```powershell
# 终端二：首次或依赖变化后先 npm ci。
cd frontend
npm ci
npm run dev
```

浏览器访问 `http://127.0.0.1:5174`。前端 `/api` 代理到 `8080`；端口被占用会报错，不自动跳到商城端口。终端服务用 Ctrl+C 退出；仅停止本项目 Redis 可执行 `.\scripts\dev.ps1 redis-stop`，此命令不删除数据卷。

后端由 Flyway 执行版本化迁移，JPA 仅 `validate` 校验。V1 创建原 `users`、`items`；V2 扩展账号，增加 `campus_verifications`、`verification_applications`、`business_logs`，保留旧 `phone` 列（不超过100字符的值回填 `contact`），并为全部旧账号创建 UNVERIFIED 资格。共5张业务表，尚非完整八表设计。升级已有库前备份；已应用的迁移不可回改。管理员仅在密码已配置且 `admin` 不存在时创建，账号和未认证资格同事务创建，已有密码不覆盖。

V2 将旧本机合成账号标为测试数据、校园标识为 `TEST_CAMPUS`，不会自动认证。切换真实校园需独立数据及经审核的迁移；不能只改测试开关复用测试审批。当前开发库若尚在V1，下次启动会执行V2，请先备份；本轮迁移验证使用隔离测试库。

如不使用本机加密凭据，在当前终端或 IDE 配置 `.env.example` 中的实际变量；`-UseUserEnvironment` 可显式读取用户环境的凭据。`.env.example` 只是说明，Spring Boot 不会自动加载 `.env`。运行账号默认 `lost_found_app`，不要长期使用初始化时的管理账号。脚本拒绝继承的 `SPRING_*` 覆盖，防止误连别的工程；直接从 IDE 启动时也需自行检查配置优先级。

## 验证

```powershell
# 不连接真实服务的回归测试与打包
.\scripts\dev.ps1 verify
.\scripts\Test-DevScripts.ps1
cd frontend
npm ci
npm run verify
```

真实接口测试必须单独启动集成环境（从仓库根目录）：

```powershell
.\scripts\dev.ps1 redis-test-up
.\scripts\dev.ps1 backend -Profile integration -DatabaseChecked -UseLocalCredentials
```

在另一个终端运行：

```powershell
$testAdmin = Import-Clixml -LiteralPath '.local\test-admin.credential.xml'
$previousTestAdminPassword = $env:TEST_ADMIN_PASSWORD
$env:TEST_ADMIN_PASSWORD = $testAdmin.GetNetworkCredential().Password
try {
  node scripts/check-identity-api.mjs --confirm-test-environment
  node scripts/smoke-api.mjs --confirm-test-environment
}
finally { $env:TEST_ADMIN_PASSWORD = $previousTestAdminPassword }
```

烟测仅请求固定 `18080` 并确认测试配置，验证账号、人工核验、并发提交/审核、私密字段、版本冲突、撤销后的旧Token限制，以及认证后的原物品流程。每次保留带唯一标记的合成账号/申请/物品，不清库或清空 Redis，不打印密码和 Token。旧 `itemSearch` 缓存已停用，列表读取当前数据库事实。

真实浏览器验收：另启动 `cd frontend; npm run dev:integration`，按同样方式在运行脚本的进程中设置 `TEST_ADMIN_PASSWORD`，执行 `node scripts/check-identity-browser.mjs --confirm-test-environment`。使用独立无界面 Edge，真实填写注册、资料、申请和审核表单；截图和结果在 `.local/identity-browser/`。只连接15174/18080，不占用商城15175。

数据库事务与并发测试需显式启用：在JDK21终端设置 `RUN_IDENTITY_DB_TESTS=true`、专用 `TEST_DB_USERNAME/TEST_DB_PASSWORD` 后执行 `mvn -Dtest=IdentityDatabaseIntegrationTest test`；测试固定integration，仅新增合成记录。默认 `dev.ps1 verify` 跳过这8项真实数据库测试，仍运行其余回归及打包。验证包含注册/审计故障回滚、到期边界、重复注册/审核、资格撤销与业务写入两个锁顺序。

其他独立验证工具：`scripts/check-database-isolation.mjs dev|integration` 使用当前进程的专用 DB 凭据，检查本库 V1 和跨库拒绝；`scripts/check-frontend.mjs --help` 说明真实 Edge 浏览器验证的准备条件。浏览器脚本只连 5174/8080，不新增业务数据，截图与结果输出到已忽略的 `.local/browser-check`。

配置依据：[Spring Boot 外部配置](https://docs.spring.io/spring-boot/3.4/reference/features/external-config.html)、[版本化数据库初始化](https://docs.spring.io/spring-boot/3.4/how-to/data-initialization.html)、[Docker 本机端口发布](https://docs.docker.com/engine/network/port-publishing/)。

## AI 配置

在 `application.yml` 中设置 `ai.enabled: true`，并设置环境变量 `AI_API_KEY`。`ai.base-url` 默认为 OpenAI 兼容接口地址，可以替换为学校允许使用的其他兼容模型服务。

## 接口概要

公开配置、注册与登录无需Token；退出保留可选Token的幂等行为。其他请求使用 `X-Token: 登录返回的 token`。本人资料/认证仅需登录（L）；物品与AI需当前资格有效（G）；`/api/admin/**` 需当前管理员角色（M），不依赖学生认证。管理员使用普通业务仍需G且不绕过归属限制。

| 方法 | 地址 | 说明 |
| --- | --- | --- |
| GET | `/api/public/config` | 校园/测试标识、人工核验指引、支持与限制说明 |
| POST | `/api/auth/register` | 注册：username、password、nickname |
| POST | `/api/auth/login` | 登录：username、password |
| POST | `/api/auth/logout` | 退出：通过 X-Token 传入当前 token |
| GET / PUT | `/api/users/me` | 本人资料；修改 nickname/contact/expectedVersion |
| GET / POST | `/api/verifications/me` | 本人资格/分页历史；提交 expectedVersion/realName，学号与说明选填 |
| GET | `/api/admin/verifications` | 当前资格分页，status/keyword/userId筛选（含动态EXPIRED） |
| GET | `/api/admin/verifications/{userId}` | 当前申请及管理内部历史 |
| POST | `/api/admin/verifications/{userId}/review` | applicationId/expectedVersion；通过须method/evidenceSummary/validThrough，驳回须reason |
| POST | `/api/admin/verifications/{userId}/revoke` | 当前有效资格撤销，expectedVersion/reason |
| POST | `/api/admin/verifications/{userId}/reopen` | REVOKED→UNVERIFIED，允许重提，不直接认证通过 |
| POST | `/api/items` | 发布信息：title、description、type(LOST/FOUND) 等 |
| GET | `/api/items?keyword=&type=` | 搜索已审核信息 |
| GET | `/api/items/mine` | 本人发布记录 |
| PUT | `/api/items/{id}` | 编辑信息，普通用户修改后重新进入待审核状态 |
| GET | `/api/items/{id}/matches` | 查询相反类型的相似信息；未公开信息仅发布者或管理员可用 |
| GET | `/api/admin/items/pending` | 管理员待审核列表 |
| PUT | `/api/admin/items/{id}/review` | 管理员审核：status |
| POST | `/api/ai/polish` | AI 润色：content |
| POST | `/api/ai/chat` | AI 问答：question |

成功响应仍为 `{ "code": 0, "message": "success", "data": ... }`；失败包含 `code:-1`、`errorCode`、`traceId`。错误凭证401、用户名冲突/旧版本409、资格失效403 VERIFICATION_REQUIRED、依赖故障503；未知请求字段400。注册及初始管理员密码须为6–64字符且UTF-8≤72字节。

有效至日期包含当天，按校园时区次日零点排他到期；每次授权读数据库，不信任Token内旧角色或资格。资格写入与现有物品写入按资格锁协调，flush后提交前复核到期。审批历史、当前资格和审计同事务提交。管理员禁止自审；核验依据、内部备注、审核人仅管理详情可见，证明原图不上传。

公开配置来自服务端 `app.campus`：默认 `TEST_CAMPUS`、`Asia/Shanghai`、测试模式，真实学校与渠道未落实时不虚构。昵称/联系方式不会改变认证资格。账号限流使用Redis原子计数，默认登录60次/IP/分钟、注册30次/IP/分钟、认证提交12次/账号/分钟，超限429及Retry-After；可通过 `app.rate-limit.login-per-minute/register-per-minute/verification-per-minute` 调整。当前本机直连按来源IP限流，代理部署前需明确可信代理策略。

## 毕设论文可写模块

账号、认证、审计已建立独立模块入口；原物品模块仍在逐步升级。MySQL负责持久化与事务资格锁，Redis负责会话过期和限流；AI保留可关闭的调用封装。匹配使用Jaccard字符集合相似度。下一阶段按需求文档推进发布、图片、分页详情和内容审核，再实现认领与双向交接。
