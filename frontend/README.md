# 校园拾光 · 前端开发基线

这是实际 Vue 前端基础工程，不是此前的离线 HTML 原型。仅供本机开发联调；人工在校身份审核、发布/详情/认领/管理页面尚未实现，不能将当前版本对真实校园开放。

## 环境与命令

已固定 Node.js `22.18.0`、npm `10.9.3`（见 `.nvmrc`、`package.json`）。直接依赖使用精确版本，提交 `package-lock.json`；依赖仅安装在本目录，不全局升级工具。IDE 建议使用 Vue - Official 扩展。

在本目录执行：

```powershell
npm ci
npm run verify
npm run dev
```

- 开发地址：`http://127.0.0.1:5174`。仅监听回环地址；端口已被占用即失败，不自动切到其他端口。
- `/api` 开发代理到 `http://127.0.0.1:8080`，保留 `/api` 前缀；后端未启动时仍可打开登录/注册页，但真实请求会显示失败。
- `npm run type-check`：Vue/TypeScript 类型检查。
- `npm run lint`：ESLint 检查，警告也视为失败。
- `npm run test:unit`：请求、会话、路由测试，不连接数据库或真实后端。
- `npm run build`：类型检查与静态构建，输出到 `dist/`。
- `npm run preview`：仅用于本地检查构建产物，地址 `http://127.0.0.1:4174`，继承上述 `/api` 代理；不是正式部署方式。生产部署需要独立配置同源 `/api` 反向代理和 SPA 回退，并优先匹配 API 规则，不能把 API 404 回退为 HTML。

## 当前对接范围

| 页面 / 动作 | 实际后端接口 | 说明 |
| --- | --- | --- |
| 注册 | `POST /api/auth/register` | username/password/nickname；成功后回登录，不自动认证 |
| 登录 | `POST /api/auth/login` | 读取 token/userId/username/nickname/role |
| 退出 | `POST /api/auth/logout` | 成功或失败均清理本机凭证；失败明确说明服务端注销未确认 |
| 大厅查询 | `GET /api/items?keyword=...&type=...` | 当前列表接口未分页，不冒用设计稿的 `/page` |

无 `/me` 接口，因此刷新后的本地会话仅用于导航提示；每次业务请求仍由后端鉴权。不能把本地 Token、缓存昵称或 role 当作当前在校身份或权限证明。下一阶段接入本人资料与人工校园认证后，应按正式需求收紧业务路由。

## 会话与错误约定

- 通用请求位于 `src/lib/request.ts`，使用 `X-Token`，仅向本站 `/api/` 路径发送凭证，禁止跟随 HTTP 重定向；同时检查 HTTP 状态和 `ApiResponse.code === 0`。
- HTTP 401 / 明确的业务 401 清理会话并回登录；403 显示无权访问，不反复登录绕过。
- 凭证只保存在 Pinia 内存和带项目命名空间的 `sessionStorage`；不存密码，不用 `localStorage`，不把 Token 放到 URL。浏览器脚本能访问 sessionStorage，生产阶段仍需要 XSS 防护与 HTTPS。
- 请求 10 秒超时，不自动重试写操作；写超时提示结果待确认。账号切换后丢弃旧响应，列表重新查询时取消旧请求，退出会卸载并清空大厅。
- `returnTo` 只允许已经存在的 `/items` 本地路由；其余返回地址统一降级到大厅。
- 加载、空数据、服务故障分开显示；页面没有伪造统计、示例物品或默认测试密码。

## 文档依据

依照此前 06 页面设计的暖白/深绿布局，并核对 [Vue 官方入门](https://vuejs.org/guide/quick-start.html)、[Vite 服务选项](https://vite.dev/config/server-options.html)、[Vue Router 导航守卫](https://router.vuejs.org/guide/advanced/navigation-guards.html) 与 [Pinia 组件外使用](https://pinia.vuejs.org/core-concepts/outside-component-usage.html)。当前实现范围以仓库已有接口为准，设计稿中的未来接口并未因此实现。
