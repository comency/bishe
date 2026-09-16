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

账号、人工在校身份审核、物品发布/图片/内容审核及认领/双向交接已实现，覆盖文档 API-01～42。AI-43/44已实现本地适配、明确降级、问答页及人工采纳的文案预览；本机小模型已真实生成测试，但输出质量不合格、首次请求有超时风险，继续默认关闭。本机使用合成测试校园，尚未满足真实校园上线条件。详细实施与边界见 E盘“项目流程/17_智能辅助与安全降级实施记录_V1.0.md”“18_本地模型准备与资源准入记录_V1.0.md”和“19_小模型效果与性能实测记录_V1.0.md”。

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

数据库事务与并发测试需显式启用：在JDK21终端设置 `RUN_IDENTITY_DB_TESTS=true`、`RUN_ITEM_DB_TESTS=true`、`RUN_CLAIM_DB_TESTS=true`、`RUN_AI_DB_TESTS=true`、专用 `TEST_DB_USERNAME/TEST_DB_PASSWORD/TEST_ADMIN_PASSWORD` 后执行 `mvn verify`。默认 `dev.ps1 verify` 运行181项普通测试与打包，跳过29项真实库测试及1项真实模型测试。真实库测试包含认领唯一接受竞争、双向并发确认、提交前对方到期、整体回滚、20组状态/确认组合（9组合法）、异常结案与跨模块限制，以及AI生成中撤销/到期、网络调用不持有数据库事务；只新增合成数据，不清库。

认领阶段新增真实联调命令（固定测试后端18080，浏览器另需前端15174）：

```powershell
node scripts/check-claims-api.mjs --confirm-test-environment
node scripts/check-claims-browser.mjs --confirm-test-environment
```

本阶段复核：后端152项（含27项真实库）、前端90项通过；认领HTTP 534项、账号2144项、物品194项、原流程烟测373项通过。认领真实Edge 20项断言/7张响应式截图，原账号与物品浏览器26项回归通过。截图与结果保存在已忽略的`.local/claims-browser/`，E盘实施记录另存验收副本。

图片文件根目录默认 `.local/media-dev`，集成测试 `.local/media-test`，均不是静态公开目录。仅受控GET携带X-Token返回图片，前端生成Blob预览并在卸载时撤销。尺寸默认1200万像素、最长边8192，临时图24小时；这些只是可配置的本地技术测试值。`MEDIA_CLEANUP_ENABLED=false` 默认不运行定时清理；显式开启后按小时处理已过期/已移除记录，先PURGING、文件删除成功后DELETED，失败可重试。已绑定文件不清理。提交结果不确定的孤儿文件仅由内部 `orphanInventory()` 列出超过7天且无元数据的候选，需运维核对，不自动删除、不对外暴露路径。

其他独立验证工具：`scripts/check-database-isolation.mjs dev|integration` 使用当前进程的专用 DB 凭据，检查本库 V1 和跨库拒绝；`scripts/check-frontend.mjs --help` 说明真实 Edge 浏览器验证的准备条件。浏览器脚本只连 5174/8080，不新增业务数据，截图与结果输出到已忽略的 `.local/browser-check`。

配置依据：[Spring Boot 外部配置](https://docs.spring.io/spring-boot/3.4/reference/features/external-config.html)、[版本化数据库初始化](https://docs.spring.io/spring-boot/3.4/how-to/data-initialization.html)、[Docker 本机端口发布](https://docs.docker.com/engine/network/port-publishing/)。

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

该测试不使用数据库或Redis、不启用应用AI，结果写入`.local/ai-contract-trial/`。Node诊断只是原始模型输出观察，不执行Java内容守卫；不能替代此测试。用户要求等待另一项目完成Docker更新后才能再次退出Docker/WSL，此时内存不足，真实模型复测暂停，尚无修正后质量通过结论。详见E盘20记录。

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

账号、认证、审计、物品、媒体、认领和双向交接已建立业务入口。MySQL负责持久化、资格锁与认领状态一致性，Redis负责会话过期和限流；匹配使用Jaccard字符集合相似度，仅返回非零候选。智能辅助契约、页面与降级已实现，本机真实生成测试发现资源可支撑当前单请求但质量不合格。下一步改进任务/输出约束与首次加载策略，复测通过后再做主业务并行及端到端验收，决定是否启用。随后进行一期全流程验收、部署准备与论文素材整理。
