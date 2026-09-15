# 校园失物招领智能管理系统

本项目是一个适合毕业设计的 Spring Boot 后端：使用 MySQL 保存业务数据，Redis 管理会话和热点查询缓存，并通过兼容 OpenAI 的接口接入 AI 文案优化与问答。

## 功能

- 用户注册、登录、退出（Redis Token 会话）
- 发布、编辑、查看本人失物/招领信息
- 登录后搜索已审核信息；管理员审核信息
- 基于字符集合的失物/招领相似度匹配
- Redis 缓存已审核信息搜索结果
- AI 优化发布文案、失物招领问答（可按需启用）

## 启动

1. 创建数据库：`CREATE DATABASE lost_found DEFAULT CHARACTER SET utf8mb4;`
2. 启动 MySQL 和 Redis，并按需修改 [application.yml](src/main/resources/application.yml)。通过环境变量 `DB_USERNAME`（默认 `root`）、`DB_PASSWORD` 配置数据库账号，密码默认留空。
3. 使用 JDK 21，将 `JAVA_HOME` 指向 JDK 21 安装目录，运行：`mvn spring-boot:run`。
4. 如需初始化管理员，首次启动前设置环境变量 `ADMIN_PASSWORD`。系统仅在已配置密码且 `admin` 不存在时创建管理员；未配置时不自动创建，已有账号不会被覆盖。密码要求见下文。

数据库表由 JPA 自动建立。默认服务地址为 `http://localhost:8080`。

环境变量应在运行服务的同一终端或 IDE 运行配置中设置；项目不会自动加载 `.env` 文件。不要将真实密码或 AI 密钥写入提交文件。

## 验证

使用 JDK 21 执行 `mvn verify`，运行回归测试并打包。测试覆盖参数校验、缓存序列化、物品编辑审核与访问权限、管理员初始化及密码处理；不连接真实 MySQL、Redis 或 AI 服务。

当前依赖中的 Mockito / Byte Buddy 不支持 JDK 25，请勿以默认 JDK 25 的测试失败判断业务代码状态。

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
