# 校园失物招领智能管理系统

本项目是一个适合毕业设计的 Spring Boot + Vue 校园应用：使用 MySQL 保存业务数据，Redis 管理会话及入口限流，支持默认关闭的本地 AI 文案辅助与问答适配。

## 功能

- 用户注册、登录、退出（Redis Token 会话）
- 本人资料与联系方式维护，版本冲突保护
- 人工在校身份申请、审核、驳回重提、有效期、撤销及允许重核
- 本人认证历史、管理员审核页面及事务审计；测试认证明确标识
- 发布、编辑重审、本人关闭，物品详情与公开/本人分页筛选
- JPEG/PNG受控上传（每张5MiB、最多3张），绑定与私密图片读取
- 管理员内容审核、驳回原因、内部备注、下架和版本化操作历史
- 认领申请、收到/发出的认领列表，接受/拒绝/取消与联系快照
- 双方分别确认交出/收到，原子归还结案、其他申请自动结束
- 单方交接的管理异常处置、保留确认事实与脱敏业务日志
- 基于字符集合的失物/招领相似度匹配
- 现有物品与 AI 入口检查实时校园资格；管理员通过独立管理入口审核
- AI 优化发布文案、失物招领问答（可按需启用）

## 开发基线与范围

账号、人工在校身份审核、物品发布/图片/内容审核及认领/双向交接已实现，覆盖文档 API-01～42。AI-43/44已实现本地适配、明确降级、问答页及人工采纳的文案预览；问答使用结构化选句及后端白名单。2026-09-16下午测试Redis恢复，旧候选5e9aa3a7普通业务2950项HTTP断言及62项浏览器检查通过（其中AI生成交互使用合成响应，不代表模型效果）。模型历史条件性成功/失败见30、31记录；最新32记录新增250ms回收复查，最终开发包连续3轮真实HTTP各161项通过，但快速浏览器仍资源不足，不能认定持续可用，AI默认关闭。普通测试入口已恢复最新后端；合成测试校园尚不满足真实校园上线条件。

| 环境 | MySQL（本机 Windows 服务） | 独立 Redis | 后端 | 前端 |
| --- | --- | --- | --- | --- |
| 开发 | `127.0.0.1:13306/lost_found` | `127.0.0.1:16379` | `127.0.0.1:8080` | `127.0.0.1:5174` |
| 集成测试 | `127.0.0.1:13306/lost_found_test` | `127.0.0.1:16380` | `127.0.0.1:18080` | `127.0.0.1:15174`，`npm run dev:integration` |
| 显式模型联调 | 同上专用测试库，禁用迁移 | 同上测试 Redis | `127.0.0.1:18081` | `127.0.0.1:15176`，`npm run dev:modeltrial` |

商城的 `3306`、`6379`、`5173`、`15175` 不属于本项目。测试库虽然单独授权，仍与开发库共用本机 MySQL 进程；数据库停机/磁盘故障测试需另建隔离实例。

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

后端由 Flyway 执行版本化迁移，JPA 仅 `validate` 校验。V1 创建原 `users`、`items`；V2 扩展账号并增加认证与日志；V3 扩展物品版本、审核/关闭字段，增加 `media_files` 与 `item_images`；V4增加`claims`、数据库唯一/状态约束、认领日志外键及物品内部占用修订号。共8张业务表。旧 `phone`、物品时间与宽文本列保留，不截断旧数据；新UTC物品时间单独保存。升级已有库前备份；已应用的迁移不可回改。管理员仅在密码已配置且 `admin` 不存在时创建，账号和未认证资格同事务创建，已有密码不覆盖。

V2 将旧本机合成账号标为测试数据、校园标识为 `TEST_CAMPUS`，不会自动认证。切换真实校园需独立数据及经审核的迁移。当前开发库仍在V1，下次启动会执行V2–V4，必须先备份并核对历史时间含义、空时间及CLOSED记录；V3遇到无法解释关闭原因的旧记录会停止，不伪造原因。本轮只将隔离测试库升级至V4，没有迁移开发库。

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
  node scripts/check-items-api.mjs --confirm-test-environment
  node scripts/smoke-api.mjs --confirm-test-environment
}
finally { $env:TEST_ADMIN_PASSWORD = $previousTestAdminPassword }
```

烟测仅请求固定 `18080` 并确认测试配置，验证账号、人工核验、并发提交/审核、私密字段、版本冲突、撤销后的旧Token限制，以及认证后的原物品流程。每次保留带唯一标记的合成账号/申请/物品，不清库或清空 Redis，不打印密码和 Token。旧 `itemSearch` 缓存已停用，列表读取当前数据库事实。

真实浏览器验收：另启动 `cd frontend; npm run dev:integration`，按同样方式在运行脚本的进程中设置 `TEST_ADMIN_PASSWORD`，执行 `node scripts/check-identity-browser.mjs --confirm-test-environment`。使用独立无界面 Edge，真实填写注册、资料、申请和审核表单；截图和结果在 `.local/identity-browser/`。只连接15174/18080，不占用商城15175。

物品浏览器验收在同一脚本追加 `--items`：`node scripts/check-identity-browser.mjs --confirm-test-environment --items`；真实上传、发布、驳回、修改重审、公开和关闭，结果在 `.local/items-browser/`。

浏览器回归还覆盖“关闭请求已提交但响应延迟时，切换另一物品详情”：业务页面按会话和资源路径隔离，旧页卸载时取消请求并清空私密预览，迟到响应不能填回新物品页面。2026-09-16推送前复核：后端135项（含13项真实库）、前端76项、真实浏览器26项通过，三个HTTP联调脚本分别2144/194/373项断言通过。

数据库事务与并发测试需显式启用：在JDK21终端设置 `RUN_IDENTITY_DB_TESTS=true`、`RUN_ITEM_DB_TESTS=true`、`RUN_CLAIM_DB_TESTS=true`、`RUN_AI_DB_TESTS=true`、专用 `TEST_DB_USERNAME/TEST_DB_PASSWORD/TEST_ADMIN_PASSWORD` 后执行 `mvn verify`。默认 `dev.ps1 verify` 只运行普通测试与打包，跳过需要真实数据库、模型及隔离演练的测试；实际数量以该轮Surefire报告为准，不能将跳过项算通过。真实库测试包含认领唯一接受竞争、双向并发确认、提交前对方到期、整体回滚、20组状态/确认组合（9组合法）、异常结案与跨模块限制，以及AI生成中撤销/到期、网络调用不持有数据库事务；只新增合成数据，不清库。

认领阶段新增真实联调命令（固定测试后端18080，浏览器另需前端15174）：

```powershell
node scripts/check-claims-api.mjs --confirm-test-environment
node scripts/check-claims-browser.mjs --confirm-test-environment
```

本阶段复核：后端152项（含27项真实库）、前端90项通过；认领HTTP 534项、账号2144项、物品194项、原流程烟测373项通过。认领真实Edge 20项断言/7张响应式截图，原账号与物品浏览器26项回归通过。截图与结果保存在已忽略的`.local/claims-browser/`，E盘实施记录另存验收副本。

图片文件根目录默认 `.local/media-dev`，集成测试 `.local/media-test`，均不是静态公开目录。仅受控GET携带X-Token返回图片，前端生成Blob预览并在卸载时撤销。尺寸默认1200万像素、最长边8192，临时图24小时；这些只是可配置的本地技术测试值。`MEDIA_CLEANUP_ENABLED=false` 默认不运行定时清理；显式开启后按小时处理已过期/已移除记录，先PURGING、文件删除成功后DELETED，失败可重试。已绑定文件不清理。提交结果不确定的孤儿文件仅由内部 `orphanInventory()` 列出超过7天且无元数据的候选，需运维核对，不自动删除、不对外暴露路径。

其他独立验证工具：`scripts/check-database-isolation.mjs dev|integration` 使用当前进程的专用 DB 凭据，检查本库 V1 和跨库拒绝；`scripts/check-frontend.mjs --help` 说明真实 Edge 浏览器验证的准备条件。浏览器脚本只连 5174/8080，不新增业务数据，截图与结果输出到已忽略的 `.local/browser-check`。

配置依据：[Spring Boot 外部配置](https://docs.spring.io/spring-boot/3.4/reference/features/external-config.html)、[版本化数据库初始化](https://docs.spring.io/spring-boot/3.4/how-to/data-initialization.html)、[Docker 本机端口发布](https://docs.docker.com/engine/network/port-publishing/)。

## 显式真实模型应用联调

普通开发和 integration 的 AI 仍默认关闭。确认测试库备份、V4结构及专用Redis后，构建最新JAR，再运行 `scripts/Start-AiIntegrationTrial.ps1 -ConfirmLocalTrial -DatabaseChecked`；前端运行 `npm run dev:modeltrial`。脚本仅本机18081/15176，拒绝占用端口及继承Spring/JVM覆盖；`integration,modeltrial`启动守卫在数据源初始化前核对测试库/Redis、校园、资源限制和禁用迁移。仅此组合提供管理员专用`/api/admin/ai-trial`标识，联调脚本在创建合成账号前检查它。

另行显式启动并预热已校验的便携模型，从当前进程加载测试管理员密码后，依次执行：

```powershell
node scripts/check-ai-live-api.mjs --confirm-local-model-trial --confirm-test-environment
node scripts/check-ai-live-browser.mjs --confirm-local-model-trial --confirm-test-environment
```

脚本不替换真实模型响应，不清库；覆盖实际生成、资格、限流、单并发，以及预览/采用、原文变化和真实响应延迟。浏览器的响应延迟撤销场景不等于“模型计算过程中撤销”，后者另有真实数据库+受控提供者测试。结果分别保留在`.local/ai-http-trial/`和`.local/ai-live-browser/`，失败不得计为端到端通过。

历史故障阶段：241项普通后端、106项前端通过，另此前29项真实库通过。当时Docker启动缺少安装注册表项，测试Redis不可用，成功链路未执行；未修改注册表或重装。已利用当时故障完成22项真实HTTP/Edge故障断言：明确503、不放行、不把依赖故障伪装为AI降级，匿名仍401；375px及桌面截图已复核。复现命令 `node scripts/check-redis-outage.mjs --confirm-test-redis-unavailable` 要求测试Redis已经停止，脚本不会停止依赖。该故障轮无新账号、无模型生成。下午Redis已恢复，后续成功/失败尝试见下方最新记录，不能继续引用本段为当前故障状态。

后续加固：启动守卫还拒绝Hikari专属连接覆盖、Redis URL/集群/哨兵覆盖和额外JPA属性；强制关闭SQL初始化与JPA建表，只接受本项目测试图片目录。41项启动配置检查通过，打包JAR的非法Redis URL在数据源/迁移/监听之前拒绝，正确试验组合正常启动。完整后端293项中286通过（257普通+29真实库）、7跳过（6隔离演练+1模型），无失败；演练另外单独执行如下。

## 隔离迁移与数据库/图片恢复演练

`powershell.exe -NoProfile -File scripts/Test-IsolatedDatabase.ps1 -ConfirmIsolatedRehearsal` 使用既有MySQL8.0.41，在独占回环13307及全新`.local/database-rehearsal/<唯一目录>/data`运行，不连接开发库/测试库/商城，不注册服务。开始前至少4GiB可用内存；端口占用拒绝；`--no-defaults`避免读已有实例配置，随机凭据只在私有目录以DPAPI保存。默认普通构建不执行此演练。

6项真实测试通过：空库V1–V4与重复迁移、合成V1事实保留且不自动认证、旧CLOSED不明原因拒绝升级、迁移checksum改变拒绝、未管理非空库拒绝自动baseline、SQL+PNG恢复到另一空库并校对9张表数据/完整定义/图片摘要及绑定。Windows父子进程均验证归属，正常结束只关闭本轮实例；文件保留、不清库。`result.json`记录是否强制停止/是否剩余进程，失败不可忽略。40项环境脚本检查通过。

此前失败轮（进程识别、mysqldump参数）均保留，详见E盘24记录；修正后两轮增强演练通过。这里的极小合成恢复不是生产RTO/RPO、时间点恢复或恢复后应用全流程验收；现有开发库仍V1，不能直接启动迁移。

## 本地候选交付包（不部署）

在工作区干净且已提交时执行 `powershell.exe -NoProfile -File scripts/New-LocalRelease.ps1 -CreateCandidate`。工具从该提交导出全新源码快照，在独立`.local/release-build/`重新后端验证、前端`npm ci`及完整验证，不动现有node_modules/target、不启动服务或连接业务库。成功后生成`.local/releases/`候选目录，包含JAR、前端、源码快照、运行说明和逐文件SHA-256清单；失败保留构建日志，不宣布验收通过。

使用 `node scripts/check-release.mjs <候选目录>`只读检查，23项工具测试覆盖篡改、缺失/新增文件、路径穿越、符号链接、错误测试摘要及伪造生产放行标记。另43项开发环境脚本检查通过。完整性清单不是数字签名，不能证明文件和清单同时被改后的可信来源。详见[候选包上线门禁](infra/RELEASE-CHECKLIST.md)；真实校园、HTTPS/代理、性能、实际应用模型联调与最终上线验收仍须独立完成，包构建不等于上线许可。

## 跨域访问边界

不再使用通配来源：开发仅允许127.0.0.1/localhost的5174，integration仅15174，modeltrial仅15176；商城15175及其他来源被拒绝。请求只开放业务所需方法和Content-Type/X-Token/Accept，不共享Cookie凭据。CORS允许源仍须通过原有登录、角色、资格和对象权限检查。

正式同源/代理环境须显式配置`CORS_ALLOWED_ORIGINS`为真实HTTPS origin（不含路径、查询、凭据或通配符）；空值只允许同源请求。明文HTTP仅允许带明确端口的上述回环主机；integration/modeltrial使用各自固定值。生产HTTPS/可信代理仍须落实，不因本地允许源通过而视为上线完成。

21项CORS策略/MVC测试、1项环境映射和新增试验守卫通过；真实18081 HTTP13项以及15176浏览器Redis故障22项通过，未产生账号或模型调用。复现：`node scripts/check-cors-api.mjs --confirm-local-trial`，需显式试验后端；CORS错误是框架在业务鉴权前的403，不按业务JSON包络解释。

## AI 配置

默认 `AI_ENABLED=false`，集成测试配置强制关闭。适配器仅调用本机 Ollama 原生 `/api/chat`；`AI_BASE_URL` 默认 `http://127.0.0.1:11434`，只接受显式端口的回环IP，禁用代理和重定向，无云端回退或API密钥。`AI_MODEL` 默认候选 `qwen3:1.7b`，仅另允许 `qwen3:4b`；这不表示模型已下载或已验证。安装、模型来源/量化确认及效果试跑需另行安排。

保守保护值：调用前至少4GiB可用系统内存（安全余量，不是模型最低内存结论）、上下文4096、输出512 token、完整响应20秒、响应体64KiB、应用内单并发零排队、两接口合计每账号6次/分钟。按UTF-8字节做保守输入预算，超限不截断。请求 `think=false`、`keep_alive=0`；本机推理引擎还需独立限制并发和排队。HTTP取消只保证应用停止等待，不能据此声称引擎立即停止计算或释放显存。

返回 `data={content,status,reason}`。`GENERATED` 的 `reason=null`；不可用时HTTP200、`UNAVAILABLE`，原因为 `DISABLED/RESOURCE_LIMIT/BUSY/TIMEOUT/UPSTREAM_ERROR/EMPTY_RESULT`。认证、输入和限流错误保持403/400/429等错误响应，不伪装降级。调用前后均重新检查在校资格，网络等待不持有数据库事务，不附带身份材料或数据库记录。输出仅纯文本展示，不自动发布或判断归属；文案须显式采纳，原文变化、页面卸载或资格失效后不能采用旧预览。静态使用帮助明确不是模型回答。

验证命令（HTTP/浏览器需上述测试管理员环境变量；浏览器另需15174）：

```powershell
.\scripts\Inspect-AiResources.ps1
node scripts/check-ai-api.mjs --confirm-test-environment
node scripts/check-ai-browser.mjs --confirm-test-environment
```

本阶段后端182项（29项真实库）、前端103项通过；AI HTTP 78项、浏览器16项/4张截图通过。模型成功、超时、异常及并发使用受控HTTP替身验证，浏览器成功预览使用显式合成响应；真实后端验证的是关闭降级和资格限制，均不构成真实模型质量/性能证明。截图与结果在 `.local/ai-browser/`，E盘有验收副本。原账号/物品/认领/烟测HTTP回归分别2144/200/534/373项通过；物品并发测试断言数随竞争分支变化。

### 本机模型准备（2026-09-16）

已从官方来源下载便携Ollama `0.34.1`（校验官方SHA-256及有效数字签名）和 `qwen3:1.7b` / `Q4_K_M`。模型摘要固定为 `8f68893c685c3ddff2aa3fffce2aa60a30bb2da65ca488b61fff134a4d1730e7`，大小1,359,293,444字节；标签为1.7b，当前服务元数据报告2.0B，不凭标签推断实际占用。文件位于E盘 `项目流程/本地AI资源`，不进入Git。没有修改Docker的10GB上限、用户PATH或开机启动。

两次生成准入检查分别仅约3.60/3.73GiB可用内存，均在发送生成请求前拒绝；实际生成次数为0。这里的4GiB是应用保守余量，不是硬件无法运行该模型的结论。下载和本地服务启动成功不代表中文效果、生成耗时或主业务并行影响验证完成。收尾时停止本次便携服务，应用仍默认关闭。

可用内存稳定充足后，从仓库根目录显式启动（不自动安装或下载）：

```powershell
.\scripts\Start-LocalAi.ps1 -ResourceRoot 'E:\JAVA\校园失物招领智能管理系统\项目流程\本地AI资源' -ConfirmLocalTrial
# 另一个终端执行；只用合成文本，不读数据库、不启用应用AI。
node scripts/check-ai-model.mjs --confirm-local-model-trial
# 不启动模型的脚本检查；本机需nvidia-smi。
.\scripts\Test-AiTrialScripts.ps1
```

启动器仅设置当前进程环境：回环监听、关闭云功能、单模型单并发、队列上限1、默认生成后卸载；临时文件、模型及运行状态均指向E盘。低于4GiB时服务器仍可提供元数据/下载，但应用和试跑脚本禁止发起生成；不要绕过它们直接调用生成接口。停止前台服务用Ctrl+C。服务无局域网发布，不是带认证的生产推理网关。

试跑脚本固定运行时/模型摘要，逐例检查内存，复用实际Java提示词，准备8组事实保留、流程、越界及注入样本；记录本机资源快照和每次生成耗时，20秒超时、512输出、4096上下文、不驻留。关键词筛查不是人工效果验收；即使脚本退出0仍须人工审阅，且这组测试不含应用端到端或暖驻留性能评估。结果在`.local/ai-model-trial/时间戳/`。新增11项脚本检查、37项环境检查与78项关闭态HTTP回归通过。

### 后续真实生成实测（2026-09-16 10:22–10:26）

用户关闭Docker/WSL后，资源准入已通过。17次尝试中首次20秒超时；诊断模式首个成功请求37.48秒（包含加载、输入处理和解码），随后7次驻留短响应0.078–0.646秒。之后按应用当前20秒/生成后卸载策略完成8次，耗时2.029–3.418秒、中位2.1445秒；环境已初始化，不代表首次开机冷态。采样最低可用内存4.70GiB、整卡显存已用峰值约2.64GiB（包含其他应用），服务报告模型驻留显存约1.59GiB。

质量不合格：两轮均出现润色变认证提示、认领角色流程混淆、认可虚构导出、越界写诗及输出脚本字符串。助手逐条语义审阅发现8类样本中5类有缺陷，不能用关键词筛查通过替代人工验收；未发生真实数据库导出，前端纯文本显示也不等于执行脚本。原始输出及资源记录已保存至E盘19报告及证据目录。

本次新增可选诊断命令，用于与默认策略对照；仅诊断首请求放宽至90秒、临时驻留60秒，finally仍卸载，不修改应用配置：

```powershell
node scripts/check-ai-model.mjs --confirm-local-model-trial --diagnostic-warm
```

11项脚本检查通过。模型服务已停止，AI保持关闭；Docker仍关闭，因此没有完成Redis依赖的业务并行或应用端到端验收。下一步先改进任务/输出约束与首次加载策略，使用独立样本复测；不自动下载更大模型，不直接放宽应用超时。

### 内容约束修正（真实模型复测待续）

针对上述缺陷，策略改为保守模式，并明确显示在前端：润色只整理标点/空白，后端拒绝文字、数字、顺序、否定词及数字分隔符的变更；显式脚本或改变任务的编辑指令在调用前拒绝。问答只允许模型逐字选取1–3条已审核流程语句，不允许自由编写规则。模型原始输出校验成功才返回GENERATED，不用固定文字冒充生成结果；被拒绝时沿用UNAVAILABLE/EMPTY_RESULT。静态帮助一直可用。

策略集中在 `src/main/resources/ai-content-policy.json`，Java实际适配器和原始模型诊断读取同一版本。表面一致性不能证明所有标点调整都语义等价，问答选句也可能不相关，仍需用户核对。不能把这些约束或关键词拦截宣称为完整的提示词注入防护。

`Warm-LocalAi.ps1 -ConfirmLocalTrial` 是显式运维预热：至少4GiB余量，核对运行时/模型摘要和无已加载模型，用合成请求覆盖加载、输入处理和解码，最多90秒，随后卸载。不会下载、自动启用AI或修改普通请求20秒期限；不是后台无限驻留。此轮只完成脚本保护检查，预热效果仍需实测。

普通Maven回归181项通过，29项真实库与1项真实模型测试显式跳过；前端103项以及lint/类型检查/构建通过；14项试跑脚本检查通过。新增实际Java适配器+模型的测试覆盖原8组和新增8组样本，需人工启动已准备的Ollama，在JDK21终端显式运行：

```powershell
.\scripts\Warm-LocalAi.ps1 -ConfirmLocalTrial
$env:RUN_AI_MODEL_TESTS='true'
try { mvn '-Dtest=AiModelContractIntegrationTest' test }
finally { Remove-Item Env:RUN_AI_MODEL_TESTS -ErrorAction SilentlyContinue }
```

该测试不使用数据库或Redis、不启用应用AI，结果写入`.local/ai-contract-trial/`。Node诊断只是原始模型输出观察，不执行Java内容守卫；不能替代此测试。20记录保存此前等待另一项目完成Docker更新的暂停状态；用户随后确认更新完成并授权退出，复测结果如下。

### 内容约束后真实复测（2026-09-16）

退出Docker/WSL后可用内存约7.60GiB；显式预热成功3.006秒（加载2.748秒），确认卸载。环境此前已运行过模型，不是首次开机或首次安装冷态证明。应用期限仍20秒，AI仍关闭。

实际Java适配器同16组样本运行两轮：分别8/16、9/16返回GENERATED，其余EMPTY_RESULT；两轮JUnit均失败。9个必须可用的正常场景分别7/9、8/9成功：认领者是否必须先发布的问题连续两轮被拒，交接确认问题第一轮拒绝、第二轮正确。5个正常描述样本两轮均保留事实，但主要是原文返回，仅数字样本去掉句末标点，不代表有明显润色收益。每轮两个异常编辑输入在调用前拒绝。两轮已接受输出逐条复核未见本组样本的虚构事实或越界操作声明；不是通用安全保证。

28次实际模型调用（不含4次本地预拒绝）耗时2.035–2.826秒，中位2.167秒；0次超时/资源拒绝。覆盖预热、两轮适配器与额外原始诊断的147次资源采样，最低可用内存6449.4MiB（6.30GiB），整卡显存已用峰值2745MiB（2.68GiB，包含桌面等进程）。不能据此推断与占7GB以上的商城并行可用。

另16次原始诊断发现：模型给正确认领规则加“已审核语句：”前缀、把系统指令当答案、仍可照写越界小说或回显脚本。该诊断不走Java守卫，不能当作已放行的应用结果，也不是前两轮被拒原文的逐请求记录；两轮适配器对应越界用例均拒绝。未放宽守卫或修改断言来获得通过。

证据归档于E盘“项目流程/智能辅助约束复测_20260916_105347”，结论见21记录。服务已停止、11434不再监听、Docker保持关闭；未下载模型、未改Docker内存配置。本轮没有重跑数据库/浏览器端到端，也没有推送。下一步先改善问答输出格式稳定性并补充未参与调优的新样本，再安排Redis可用条件下的应用端到端与并行业务测试；通过前不启用AI。

### 结构化问答修正与复测（最新，2026-09-16）

策略`2026-09-16-structured-v2.2`：问答通过Ollama `format` JSON Schema请求`{"statements":["已审核语句"]}`，元素只能是原有8条语句之一，1–3条，temperature=0。主题标签帮助选择，不进入展示正文。Java再次严格校验完整JSON，拒绝重复键、额外字段、重复/未审核语句、非字符串、空/超长数组及尾随内容；不依赖推理引擎一定遵守schema。只解码模型实际选中的正文，不按ID补答案、不修补任意前缀、不把降级提示伪称GENERATED。前端明确显示“模型选取、系统校验后展示”，仍需核对相关性。

明显脚本/格式异常、要求忽略或覆盖规则/提示/schema的问答输入在调用前EMPTY_RESULT拒绝。这是有意收窄的FAQ输入边界，会有误拒绝可能，不是完整提示词注入检测。普通修改启事、取消流程、审核规则问题保留；描述整理仍为原保守模式、temperature=0.2。授权、并发、内存和20秒期限未放宽。

本轮离线回归：Maven共246项，216普通项通过、30项显式跳过；前端103项及lint/类型检查/构建通过；脚本14项通过。真实模型另行执行，不能把离线绿色当作模型验收通过。

模型样本扩为39组（原16组、新增15组、提示词冻结后独立8组）。中间方案存在选句不相关，失败记录全部保留。最终完整尝试29条GENERATED逐条复核符合本组事实/相关性要求，7组EMPTY_RESULT预拒绝；其余3组RESOURCE_LIMIT未调用，分别是独立的单方确认取消规则、菜谱越界、角色练习虚构导出。25个必须正常回答的样本有24个有效，另1个资源不足，整轮JUnit退出1，不宣称39组通过。实际调用2.091–3.451秒，中位2.689秒；不是首次开机冷态或应用端到端数据。

上述是Docker再次出现时的第6次尝试，已保留历史失败。用户随后明确“已不用，可以再次关闭并补测”：正常退出Docker超时后，核对路径强制停止两个Docker后台并关闭WSL，可用内存回升8.66GiB。策略/断言不变，第7次独立8组通过（7有效、1预拒绝）；第8次完整39组通过（32有效、7预拒绝），25个正常场景全部有效，0超时/资源拒绝。完整组实际调用2.029–2.359秒、中位2.2215秒；补测窗口最低可用内存7342MiB（7.17GiB），整卡显存峰值2739MiB。服务收尾停止，Docker关闭，当前阶段继续应用联调，不等同上线或与商城并行验收。

期间资源反复波动，第6次收尾发现Docker后台再次运行、约5.23GiB工作集，可用内存约0.52GiB；先保留并询问，获得上述授权后才再次停止。补测后模型/采样服务已停止，11434无监听，AI仍默认关闭。没有下载、改Docker配置、迁移或云端回退。下一步安排应用端到端和并行负载验证。本轮未推送。

实际模型测试默认跑全部39组；低资源诊断可显式设置`AI_MODEL_CASE_SET=original/additional/holdout`（三者选一，默认`all`），证据记录分组与策略版本。分组通过不等于持续完整试跑通过。测试工具可用`MAVEN_OPTS=-Xms32m -Xmx192m`及`-DargLine=-Xms32m -Xmx192m`限制自身堆；应用4GiB余量不变。结果见E盘22记录及“结构化问答复测_20260916_110458”证据目录。

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
| PUT | `/api/items/{id}` | 仅本人编辑，必填expectedVersion；编辑后重新待审，关闭后不可编辑 |
| GET | `/api/items/{id}/matches` | 相反类型非零相似候选；未公开基准物品仅本人可用，管理员无普通入口豁免 |
| GET | `/api/items/page`、`/api/items/mine/page` | 公开/本人分页筛选；pageSize默认10、最大50 |
| GET | `/api/items/{id}` | 公开或本人详情；他人看不到审核反馈与历史 |
| POST | `/api/items/{id}/close` | 本人关闭：expectedVersion、closeReason、reason |
| GET | `/api/admin/items/pending` | 管理员待审核列表 |
| GET | `/api/admin/items`、`/api/admin/items/{id}` | 管理分页与详情 |
| PUT | `/api/admin/items/{id}/review` | status、expectedVersion；驳回/下架必须有reason，internalNote可选 |
| POST | `/api/admin/items/{id}/close` | 管理下架：expectedVersion、reason；与review CLOSED同一逻辑 |
| POST / GET | `/api/uploads/images`、`/api/uploads/images/{id}` | 上传单张图片/按当前对象权限读取二进制图片 |
| POST | `/api/items/{id}/claims` | 他人FOUND申请：expectedItemVersion、identification、contact；同人同物仅一次 |
| GET | `/api/claims/mine`、`/api/claims/incoming`、`/api/claims/{id}` | 本人申请/收到申请/参与者私密详情；独立历史分页 |
| POST | `/api/claims/{id}/accept` | 发布者接受：expectedVersion、contact；双方资格有效、唯一占用 |
| POST | `/api/claims/{id}/reject`、`/api/claims/{id}/cancel` | expectedVersion、reason；只允许指定角色和状态 |
| POST | `/api/claims/{id}/confirm-handover`、`/api/claims/{id}/confirm-receipt` | 无请求体，发布者交出/申请者收到；按方向幂等，不代确认 |
| GET | `/api/admin/claims`、`/api/admin/claims/{id}` | 管理分页/受控私密详情；不要求学生认证 |
| POST | `/api/admin/claims/{id}/resolve` | 单方确认异常：expectedVersion、action、conclusion、reason、internalNote选填 |
| GET | `/api/admin/logs` | 按objectType/objectId/action分页查询脱敏日志 |
| POST | `/api/ai/polish` | AI 润色：content |
| POST | `/api/ai/chat` | AI 问答：question |

成功响应仍为 `{ "code": 0, "message": "success", "data": ... }`；失败包含 `code:-1`、`errorCode`、`traceId`。错误凭证401、用户名冲突/旧版本409、资格失效403 VERIFICATION_REQUIRED、依赖故障503；未知请求字段400。注册及初始管理员密码须为6–64字符且UTF-8≤72字节。

有效至日期包含当天，按校园时区次日零点排他到期；每次授权读数据库，不信任Token内旧角色或资格。资格写入与现有物品写入按资格锁协调，flush后提交前复核到期。审批历史、当前资格和审计同事务提交。管理员禁止自审；核验依据、内部备注、审核人仅管理详情可见，证明原图不上传。

公开配置来自服务端 `app.campus`：默认 `TEST_CAMPUS`、`Asia/Shanghai`、测试模式，真实学校与渠道未落实时不虚构。昵称/联系方式不会改变认证资格。账号限流使用Redis原子计数，默认登录60次/IP/分钟、注册30次/IP/分钟、认证提交及认领申请分别12次/账号/分钟，超限429及Retry-After；认领申请采用跨物品统一计数，与认证独立计数，但暂共用`app.rate-limit.verification-per-minute`限额配置。当前本机直连按来源IP限流，代理部署前需明确可信代理策略。

认领写入按资格（双方时按userId升序）→物品→认领（多条按id升序）锁定。接受及未完成的交接在提交前再次核对双方到期；对方失效仅返回409 COUNTERPART_INELIGIBLE，不泄露核验原因。活动认领阻止物品编辑和本人关闭。只有双方确认才在同一事务内完成认领、关闭物品为RETURNED、拒绝其余待处理申请并记日志；已完成重试不增加版本/日志，但会校验完成事实。管理异常终止保留单方确认，不能伪造收到或标记归还。

## 毕设论文可写模块

账号、认证、审计、物品、媒体、认领和双向交接已建立业务入口。MySQL负责持久化、资格锁与认领状态一致性，Redis负责会话过期和限流；匹配使用Jaccard字符集合相似度，仅返回非零候选。下午Redis恢复后的普通业务及真实模型应用测试见下段。已有39组适配器通过及条件性应用功能通过，不代表持续可用、商城并行或上线验收。下一步补充资源稳定性及完整HTTP并行业务性能，AI保持默认关闭。

## 测试堆上限准备（最新，2026-09-16 16:20）

`Start-AiIntegrationTrial.ps1`增加可选`-MaximumHeapMiB 256|384|512`，默认仍512；仅作用于显式启动的临时18081后端，不改普通服务、系统JVM设置或模型4GiB准入门槛。仍须`-ConfirmLocalTrial -DatabaseChecked`及原有凭据、端口、环境检查。按find-docs流程查询时Context7额度耗尽，改核对[Oracle JDK 21说明](https://docs.oracle.com/en/java/javase/21/docs/specs/man/java.html)：`-Xmx`是堆上限，不是进程总内存；不能把512→256解释为实际省出256MiB。

本轮37项脚本检查通过（新增4个非法上限拒绝和3个合法上限仍需确认），现有普通服务AI关闭态78项HTTP断言通过。未重建业务JAR、未重跑全量Java测试、未启动小堆服务或真实模型，不能声称小堆方案已验证。原普通服务保持18080/15174运行，AI仍关闭；合成账号659保留，未清理数据。

试验前发现商城15容器刚重新运行，用户明确要求保留；可用内存采样约1925MiB（1.88GiB），因此没有暂停Docker/商城或普通服务，也没有开始模型推理。普通后端384MiB上限下只读快照：堆已提交236MiB、使用约192MiB；另一次进程采样工作集约37MiB、私有提交约526MiB，压力下工作集可被换出，不能据此推断小堆足够或模型余量。日志在`.local/ai-heap-preparation-20260916/`，补充记录见E盘32。

下一步待商城可暂停且内存恢复后，以同一业务JAR试验256MiB上限，记录实际进程占用、GC及普通业务响应，再做快速真实HTTP/浏览器连续复验；若资源仍不足，保留失败并继续AI默认关闭。本轮仅本地提交，不推送、不冻结新候选。

## 回收窗口修正（2026-09-16）

真实运行时4次测量：生成返回瞬间可用内存比调用前少733–789MiB，随后首次`/api/ps`探测在65–79ms返回时已观察到明显回升。该端点探测本身也耗时，不能把65–79ms当成精确卸载时长，主机内存变化也不唯一来自模型。[Ollama v0.34.1调度源码](https://github.com/ollama/ollama/blob/v0.34.1/server/sched.go)在请求结束后异步处理卸载；结果返回不等于主机内存已完全释放。

`LocalAiClient`新增有限恢复复查：先非阻塞取得全局单槽；只有本服务在此前1秒内收到过模型完整响应、且当前内存不足时，才每25ms复查、最多250ms。等待计入原有20秒总期限；4GiB准入不变，不重发推理、不排队、不常驻模型。冷态/较早请求后的内存不足直接拒绝，250ms后仍不足也拒绝，中断保留标记并释放单槽，超时不授予新的恢复窗口。该改动处理短暂衔接，不承诺解决持续资源不足。

最终普通回归337项中298通过、39项真实库/模型等显式跳过；新增8项恢复、并发、中断和期限测试；30项脚本检查通过。中间修正版另跑全部39组真实适配器：31生成、7明确预拒绝、1资源拒绝；25个必须正常回答场景均通过。JUnit退出0仅表示契约断言通过，不能称39组全部有效；最终版进一步禁止250ms窗口外的迟到准入，后续采用最终包真实HTTP验证。

最终JAR SHA256：`ca745c29c3582b263b7f1014d8f09a35157ba3f9cba43c0a71c5dbb4fc6ce1c0`，保存在`.local/ai-recovery-20260916/backend-final.jar`；这是本轮开发构建，不是旧5e9aa3a7冻结候选。前端未改，仍使用旧候选静态产物。最终快速HTTP连续3轮各161项通过、合计15次生成，2159–2769ms、中位2294ms。但快速浏览器3轮仍未完整通过，后两轮明确RESOURCE_LIMIT；不能把接口通过当页面或持续可用验收通过。

期间普通/临时双后端同时驻留时内存降至3.67GiB，2轮HTTP和1轮浏览器在预检即拒绝，未创建账号、未生成；获用户明确许可暂停旧普通服务后才执行上述新包试验。组合操作有执行保护拒绝记录，未更改权限或资源检查；各个受限操作均经原工具校验执行。

收尾模型已卸载，11434/18081/15176关闭；普通15174/18080已恢复为最终新后端+未改静态前端，isTest=true/aiEnabled=false，AI关闭态78项HTTP冒烟通过。商城保持暂停、测试Redis保留，未改配置/卷、未推送。下一步评估更低的测试服务自身占用与稳定内存余量，再补快速浏览器及持续HTTP负载；不继续放宽准入等待。完整成功与失败证据见E盘32记录和“模型回收窗口修正_20260916”。

运行时回收诊断：`node scripts/check-ai-recovery.mjs --confirm-local-model-trial`，须先启动已校验的本地Ollama。只发合成描述、每轮20秒及4GiB门槛、固定1600ms观测间隔，自动卸载自身试验模型；不走Java内容守卫、不访问DB，不是快速连续成功率证明。

## 前一轮快速定向复测（2026-09-16 15:43–15:47）

沿用5e9aa3a7候选及固定模型，不重建、不重跑普通全量业务、不降低4GiB门槛。批量3轮真实HTTP均在连续请求时RESOURCE_LIMIT失败（实际生成分别2/1/1次）；快速浏览器2轮为1失败/1通过，通过轮21项、4次真实生成，**没有启用等待内存模式**。失败轮页面明确资源不足，不是语义错误。偶发可用不等于稳定，失败全部保留。

新增独立短对照：`node scripts/check-ai-live-api.mjs --confirm-local-model-trial --confirm-test-environment --business-overlap-only`。10个基线并发只读业务请求40–107ms；模型已报告加载且应用请求尚未完成时，另外10个并发业务读取35–104ms，全部完整落在模型请求期间。真实问答2310ms，语义正确，122项检查通过；只有1次模型和20个读取，不代表1万条/20并发持续HTTP验收，不能推断GPU计算逐毫秒重叠。

本轮5次成功生成HTTP为2140–2310ms，中位2259ms；加上浏览器4次合计9次业务模型调用，另一次操作员预热2007ms。174次秒级资源采样最低3750.8MiB，逐请求边界采样最低3626MiB；整卡显存峰值3166MiB。测试工具现在记录请求前后内存，在页面明确失败时立即结束等待，避免原35秒空等；未增加业务重试。

候选23个JS/CSS资源各读取5轮，115次状态/MIME/内容SHA256全部通过。两轮浏览器未复现模块加载失败，但未确定此前根因，不能宣称已修复。新增资源响应类型/网络错误诊断仅记录本地路径，不记录Token或其他接口正文。

模型试验服务及采样器已停止，普通15174/18080恢复AI关闭，商城仍暂停；脚本检查28项及diff检查通过。下一步转入资源预算与连续可用性方案，再做正式HTTP负载，不反复用同样低余量重跑整套测试。E盘31记录及“模型快速复测_20260916_1544”保存本轮全部证据；尚不推送或上线。

## 前一轮候选应用实测（2026-09-16下午）

运行冻结候选`.local/releases/20260916T050629Z-5e9aa3a7-5243c2`的JAR与静态前端，源码5e9aa3a75999c2ac936f0a8de668e095b98ffa86。本轮只增强测试证据采集及文档，没有修改候选业务代码、模型策略或4GiB准入门槛。

普通业务：账号/认证2144、物品/图片194、认领/交接534、AI关闭态78项HTTP断言通过；浏览器分别26、20、16项通过。AI关闭态中的预览交互使用合成响应，真实模型结果另算。测试前备份专用测试库，确认开发库V1/测试库V4及跨库拒绝；不清库，合成记录保留。

用户随后授权暂停商城：停止15个已核对归属的商城容器，保留测试Redis16380和既有buildkit，因此Docker/WSL并未整体关闭，配置/卷未改。模型测试使用便携Ollama0.34.1和固定摘要的qwen3:1.7b、同一候选显式modeltrial入口。操作员预热3.107秒；不计入业务延迟、不代表首次开机冷态。

- 真实HTTP共3轮：第1轮65项后失败（2次生成，第三次未生成；旧记录未保存具体降级原因）；第2轮161项通过（5次生成、1次BUSY、输入保护、429、撤销资格等）；第3轮59项后失败，第二次生成请求明确RESOURCE_LIMIT。通过轮5次生成HTTP耗时2049/2340/2189/2262/2386ms，中位2262ms，不是持续成功率或P95证明。
- 真实浏览器快速模式3轮均未整轮通过：旧预览未出现；一次动态模块加载失败；一次明确资源不足降级。没有将失败改为通过。新增失败截图、模型结果元数据及本地网络错误记录，不保存Token/密码或其他接口正文。
- 显式有间隔功能模式1轮21项通过、4次真实生成：问答、预览/人工采用、不自动发布、原文变化禁用旧预览、撤销资格并刷新路由后阻止迟到结果。每次请求前等待模型卸载且可用内存≥4.25GiB持续1秒，上限15秒；实测等待1036–1049ms，单独记录，无请求重试。不能以此代替快速连续可用性。最后一项是生成结束后的响应交付延迟，不是模型计算中撤销。

有间隔模式命令：`node scripts/check-ai-live-browser.mjs --confirm-local-model-trial --confirm-test-environment --wait-for-model-idle`；仍须先显式启动隔离模型服务和测试入口、设置专用管理员进程环境。默认不加末尾标志为原快速模式。

392次采样覆盖15:22:19–15:29:53，最低可用内存3475.2MiB（3.39GiB），整卡显存峰值3181MiB（包含桌面等其他进程）。本轮合计15次业务模型调用，另1次操作员预热；未接受的资源保护不计生成成功。描述仍主要原文返回，仅证明保守保留事实，不能称大幅润色。

收尾卸载模型并停止11434/18081/15176试验服务，恢复15174/18080普通候选，公开配置确认isTest=true、aiEnabled=false。商城15容器保持停止，测试Redis健康。测试脚本语法/显式确认等28项通过；本轮不重跑未改变的全量业务单测、不推送。证据（含所有失败）在E盘“项目流程/真实应用与模型联调_20260916_1514”；私有数据库备份和原始运行日志未对外归档。下一步须在更稳定余量下验证连续操作、复核页面资源偶发失败及主业务HTTP并行影响，不能直接启用正式AI。

## 历史物品时间兼容

旧数据兼容性：物品创建/更新时间原值及UTC列都缺失时，分页/详情保留`null`，不抛异常或补造日期；已知UTC优先，已知旧时间按配置时区转换。真实编辑写入本次更新时间，但不补造未知创建时间。前端类型、接口说明和真实库回归同步覆盖。

## 独立服务层性能试验（非HTTP验收）

`powershell -NoProfile -File scripts/Test-IsolatedDatabase.ps1 -ConfirmIsolatedRehearsal -IncludeServiceBenchmark`在新建的独立MySQL13307实例中完成6项迁移/恢复检查，再以真实Spring业务服务查询1万条合成物品。20并发、连接池3条、JVM堆768MiB，各阶段20秒；记录分页10/50条的成功延迟分位数、错误数和最低可用内存。只保留合成数据，不连接或清理已有业务数据库，最后核对自身实例正常退出。

如需加入3次真实模型问答，先按本文件说明启动并预热已核验的本地运行时，再额外加 `-IncludeLocalModel -ConfirmLocalModel`；不会下载模型或启用常规应用AI。记录每个模型调用是否完整处于查询负载窗口；未完整重叠不能混称全程并行。

该试验没有HTTP、Redis会话/限流、序列化或浏览器开销；数据只有一个发布者且无图片/认领，不能替代NFR-04全链路验收、代表全部分布或证明商城并行可用。证据保存在私有忽略目录`.local/database-rehearsal/<本轮>/service-benchmark.json`，初始化日志及凭据不得公开。默认构建跳过此试验，候选构建也显式禁用其环境开关。

## 独立数据库连接故障演练

`powershell -NoProfile -File scripts/Test-IsolatedDatabase.ps1 -ConfirmIsolatedRehearsal -IncludeDatabaseOutage`另建自己的临时13307实例，使用只转发原始字节的回环临时端口切断**自身测试连接**。真实账号控制器/业务服务/JPA事务及异常处理器在进程内MVC中执行，身份由测试手工设置；不算Redis登录、权限拦截器或网络HTTP验收，不停止现有数据库。

验证断连读写失败、失败写入无变化、连接池恢复后无需重启应用可继续读写；并在外层测试事务中执行真实资料更新，flush之后、COMMIT之前断连，核对未提交内容回滚。使用短测试专用超时，不代表生产恢复SLA；不能推断COMMIT回执丢失一定回滚，更不能无条件自动重试写入。证据为私有演练目录`database-outage.json`，默认构建及候选构建均跳过。
