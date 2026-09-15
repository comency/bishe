# 校园失物招领智能管理系统

本项目是一个适合毕业设计的 Spring Boot 后端：使用 MySQL 保存业务数据，Redis 管理会话和热点查询缓存，并通过兼容 OpenAI 的接口接入 AI 文案优化与问答。

## 功能

- 用户注册、登录、退出（Redis Token 会话）
- 发布、编辑、查看本人失物/招领信息
- 登录后搜索已审核信息；管理员审核信息
- 基于字符集合的失物/招领相似度匹配
- Redis 缓存已审核信息搜索结果
- AI 优化发布文案、失物招领问答（可按需启用）

## 开发基线与范围

仓库包含后端原型和 `frontend` 前端基础工程。现有接口仍是下方的 12 个原型接口；人工在校核验、图片、完整认领/交接及新版权限尚未实现。只能用于本机合成数据开发，不能作为已满足正式需求的系统开放给学生。

| 环境 | MySQL（本机 Windows 服务） | 独立 Redis | 后端 | 前端 |
| --- | --- | --- | --- | --- |
| 开发 | `127.0.0.1:13306/lost_found` | `127.0.0.1:16379` | `127.0.0.1:8080` | `127.0.0.1:5174` |
| 集成测试 | `127.0.0.1:13306/lost_found_test` | `127.0.0.1:16380` | `127.0.0.1:18080` | API 烟测，不复用开发数据 |

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

后端现在由 Flyway 执行版本化迁移，JPA 仅 `validate` 校验。`V1__prototype_baseline.sql` 只创建现有 `users`、`items` 两张业务表，不代表正式八表设计已实现；Flyway 另建自己的历史表。已应用的迁移文件不可回改。管理员仅在密码已配置且 `admin` 不存在时创建，已有密码不会被覆盖。

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
try { node scripts/smoke-api.mjs --confirm-test-environment }
finally { $env:TEST_ADMIN_PASSWORD = $previousTestAdminPassword }
```

烟测仅请求固定 `18080`，验证旧接口的注册、登录、发布、审核、搜索冷/热缓存、编辑回审、退出和 401/403；每次保留一组有唯一标记的合成账号/物品，不清库、不清空 Redis，密码和 Token 不打印。它不是未实现的在校核验或认领验收，也不能替代浏览器与生产验证。

其他独立验证工具：`scripts/check-database-isolation.mjs dev|integration` 使用当前进程的专用 DB 凭据，检查本库 V1 和跨库拒绝；`scripts/check-frontend.mjs --help` 说明真实 Edge 浏览器验证的准备条件。浏览器脚本只连 5174/8080，不新增业务数据，截图与结果输出到已忽略的 `.local/browser-check`。

配置依据：[Spring Boot 外部配置](https://docs.spring.io/spring-boot/3.4/reference/features/external-config.html)、[版本化数据库初始化](https://docs.spring.io/spring-boot/3.4/how-to/data-initialization.html)、[Docker 本机端口发布](https://docs.docker.com/engine/network/port-publishing/)。

## AI 配置

在 `application.yml` 中设置 `ai.enabled: true`，并设置环境变量 `AI_API_KEY`。`ai.base-url` 默认为 OpenAI 兼容接口地址，可以替换为学校允许使用的其他兼容模型服务。

## 接口概要

除 `/api/auth/**` 外，调用接口需带请求头 `X-Token: 登录返回的 token`。

| 方法 | 地址 | 说明 |
| --- | --- | --- |
| POST | `/api/auth/register` | 注册：username、password、nickname |
| POST | `/api/auth/login` | 登录：username、password |
| POST | `/api/auth/logout` | 退出：通过 X-Token 传入当前 token |
| POST | `/api/items` | 发布信息：title、description、type(LOST/FOUND) 等 |
| GET | `/api/items?keyword=&type=` | 搜索已审核信息 |
| GET | `/api/items/mine` | 本人发布记录 |
| PUT | `/api/items/{id}` | 编辑信息，普通用户修改后重新进入待审核状态 |
| GET | `/api/items/{id}/matches` | 查询相反类型的相似信息；未公开信息仅发布者或管理员可用 |
| GET | `/api/admin/items/pending` | 管理员待审核列表 |
| PUT | `/api/admin/items/{id}/review` | 管理员审核：status |
| POST | `/api/ai/polish` | AI 润色：content |
| POST | `/api/ai/chat` | AI 问答：question |

响应格式为 `{ "code": 0, "message": "success", "data": ... }`；失败时 `code` 为 `-1`，同时返回相应 HTTP 错误状态。注册及初始管理员密码须为 6–64 个字符，UTF-8 编码后不超过 72 字节（BCrypt 限制）。

## 毕设论文可写模块

系统采用分层架构（Controller、Service、Repository）。MySQL 负责用户和信息持久化；Redis 负责会话过期管理和查询缓存；AI 模块使用大语言模型 API 完成非结构化文本整理和系统问答。匹配功能使用 Jaccard 字符集合相似度，避免模型不可用时核心功能失效。
