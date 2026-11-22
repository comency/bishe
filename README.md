# 校园失物招领智能管理系统

面向单一校园的模块化单体应用，提供在校身份人工审核、失物/招领发布与审核、认领和双方交接闭环。AI 文案辅助与问答适配已实现，但默认关闭。当前使用合成测试校园，不代表真实校园上线条件。

## 技术栈

- 后端：Java 21、Spring Boot、Maven、MySQL、Redis
- 前端：Vue、Vite、Node.js 22
- 数据库版本：Flyway 管理，JPA 仅校验结构

## 本地端口

| 环境 | MySQL | Redis | 后端 | 前端 |
| --- | --- | --- | --- | --- |
| 开发 | `127.0.0.1:3306/lost_found` | `16379` | `8080` | `5174` |
| 集成测试 | `127.0.0.1:3306/lost_found_test` | `16380` | `18080` | `15174` |

开发库与测试库共用本机 MySQL 实例，但使用不同数据库和账号；请勿将测试配置指向开发库。端口是配置约定，不代表服务当前已启动。若其他项目占用端口，请先调整其中一个项目的映射。

## 环境准备

需要 JDK 21、Maven 3.9.x、Node.js 22.x/npm，以及 Docker Desktop（用于项目 Redis）。MySQL 8 需已运行并完成数据库/账号初始化。详细检查和初始化脚本见 `scripts/`；不要对已有数据库重复初始化。

在仓库根目录用 PowerShell 执行：

```powershell
.\scripts\dev.ps1 doctor
.\scripts\dev.ps1 verify
.\scripts\dev.ps1 redis-up
.\scripts\dev.ps1 backend -DatabaseChecked -UseLocalCredentials
```

`-DatabaseChecked` 表示你已核对 MySQL 实例、目标库、备份和账号权限；启动后 Flyway 会执行待处理迁移。当前开发库曾处于 V1，启动前务必先备份并核对迁移影响，不要以自动 baseline 绕过校验。

另开终端启动前端：

```powershell
cd frontend
npm ci
npm run dev
```

访问 `http://127.0.0.1:5174`。前端 `/api` 代理到后端 `8080`。停止 Redis 使用 `.\scripts\dev.ps1 redis-stop`；不会删除数据卷。

## 常用验证

```powershell
# 后端测试与打包
.\scripts\dev.ps1 verify
# 前端检查与测试
cd frontend
npm run verify
```

真实数据库/HTTP/浏览器联调需要显式启动 integration 环境；相关脚本有确认参数，只对测试库和固定本机端口运行。请遵守脚本提示，不要清理或复用未知数据。

## 独立演示

答辩演示工具使用独立数据库端口 `13308`、后端 `18080`、前端 `15174`，需先构建后端 JAR 和前端 `dist`，且与 integration 环境端口互斥：

```powershell
.\scripts\Start-Demo.ps1 -StartDemo
.\scripts\Start-Demo.ps1 -ShowDemoAccess
.\scripts\Stop-Demo.ps1 -StopDemo
```

演示数据与凭据保存在未跟踪的 `.local/demo`。管理员密码只在明确显示时输出，不要录屏或写入日志。详细演示验收见 [`docs/graduation-delivery-20261003.md`](docs/graduation-delivery-20261003.md)。

## 配置与安全边界

- `.env.example` 仅作变量示例，Spring Boot 不会自动加载 `.env`；不要提交密码、Token 或本机凭据。
- AI 默认关闭，仅适配本机 Ollama；启用模型联调须使用专用测试库、Redis 和脚本规定的显式参数。
- 当前测试校园、测试账号和本地端口不适用于真实校园生产部署。生产配置门禁、备份恢复及交付限制见 [`infra/PRODUCTION-CONFIGURATION.md`](infra/PRODUCTION-CONFIGURATION.md) 和 [`infra/RELEASE-CHECKLIST.md`](infra/RELEASE-CHECKLIST.md)。

开发计划、需求与设计文档保存在 `E:\JAVA\校园失物招领智能管理系统\项目流程`。
