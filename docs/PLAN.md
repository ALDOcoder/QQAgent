# QQAgent 项目计划书（v2 · 路线已改为官方开放平台）

> 定位：**个人玩具**。一个挂在 QQ 上的机器人，我私聊它，它回我。不做产品，不上简历，不追高可用。
> 当前状态：**echo 闭环已实测跑通（P0–P3 完成）**，下一步 P4 接大模型，卡在他提供模型 key / 档位 / 单价。

---

## 1. 目标与非目标

**要做**：用主号私聊机器人号，它能收到并回复；第一版 echo 跑通链路，第二版换成大模型。

**明确不做**：不引 Python 中间层、不做微服务、不上注册中心/网关；不做管理后台、多账号、用户体系；不公开发布、不加陌生人、不群发；不建数据库表。

---

## 2. 路线：为什么是官方，而不是 NapCat

**这条是被实测数据逼出来的，不是偏好。** 2026-10-31 在他的 ECS 上取证：

```
Mem: total 1608 / used 956 / available 458 / Swap: 0
mysqld 366M · fwupd 141M · minio 127M ... 没有 java 进程在跑
```

NapCat 是 Electron 客户端，**常驻 1G 起**。458M available + 0 swap 意味着塞它进去的结果是 OOM killer 直接杀进程，最坏杀掉 MySQL 连累线上服务。**→ NapCat 上 ECS 物理不可行。**

于是走 **官方 QQ 开放平台 API v2 + 纯 Java**，官方路线新增负载只有一个可以限堆的 JVM 进程，且**全是出站连接，安全组一个端口都不用开**。同时不需要专用小号、不存在封号、不用扫码养 token。

代价：官方 SDK 只有 Go / Python / Node.js，**没有 Java**，所以 WS 网关协议层要自己实现（已完成，约 300 行）。

### 运行位置：本机（β 方案）

这台 1.6G 的机器装不下两个 Java 应用——458M 是 wechat-ai-bot 停着时测的。官方路线不需要 ECS 上任何组件，所以 **bot 就在 Windows 本机跑**，代码一行不改，将来真要 7x24 再上云。

---

## 3. 已核实的官方协议常量（写代码就靠这些，别凭记忆）

| 项 | 值 |
|---|---|
| access_token | `POST https://api.bot.qq.com/app/getAppAccessToken`，body `{appId, clientSecret}`，响应 `{access_token, expires_in(秒)}` |
| API 域名 | 正式 `https://api.bot.qq.com` |
| 鉴权头 | `Authorization: QQBot {access_token}` |
| 单聊发送 | `POST /v2/users/{user_openid}/messages`，body `{content, msg_id, msg_type}` |
| **OP 码** | **0 Dispatch · 1 Heartbeat · 2 Identify · 6 Resume · 7 Reconnect · 9 Invalid Session · 10 Hello(带 heartbeat_interval) · 11 Heartbeat ACK** |
| intents | `1<<25 = 33554432` = `GROUP_AND_C2C_EVENT`，含 `C2C_MESSAGE_CREATE` 与 `GROUP_AT_MESSAGE_CREATE` |
| 心跳 | 间隔取自 Hello 帧 `d.heartbeat_interval`（毫秒），包 `{op:1, d:seq}` |
| 消息节奏 | **单聊被动回复：60 分钟内、最多 4 次**；主动消息：单聊 1000 条/用户/日 |

> 注意：网上大量教程还在推 `go-cqhttp`（2023 已删库），以及把 OP 码按 Discord 那套写成 11=Hello。**上面这组是从官方文档原文抄的。**

**两个只有真凭据才能验证的点**（已做成配置项，改一行即可）：`gateway-path`（现填 `/gateway`）和 `intents` 是否需要在管理端额外开通。真连第一次就会暴露。

---

## 4. 架构

```
QQ 服务器
  ↑↓ 官方私有协议（我们只讲它的 OpenAPI）
AccessTokenService   token 缓存 + 提前 5 分钟刷新 + 401 失效重取
GatewayClient        取 wss 地址 → op2 鉴权 → 按 Hello 间隔心跳 → op6 恢复 → 退避重连
  ↓ op0 DISPATCH (C2C_MESSAGE_CREATE)
EventDispatcher      解析 openid / msg id / content
  ↓
ReplyPolicy          白名单（空=放行全部，便于先跑起来抄 openid）
  ↓
Brain (接口)         EchoBrain 现在用；LlmBrain 之后加实现即可
  ↓
QqMessageSender      POST /v2/users/{openid}/messages，带 msg_id 做被动回复
                     dry-run=true 时只打日志，不发请求
DebugController      POST /debug/c2c —— 没凭据也能把假消息灌进同一条链路
```

接收与发送在同一进程内分离成两个组件，不跨语言。

---

## 5. 阶段

| 阶段 | 内容 | 状态 |
|---|---|---|
| P0 | ECS 资源取证 | **完成**（结论：NapCat 出局，改官方 + 本机跑） |
| P0.5 | Spring Boot 骨架 + 本地链路实测 | **完成** |
| P1 | 注册官方机器人、拿 AppID/ClientSecret、配置开发体验用户 | **完成**（由真实事件到达反证） |
| P2 | 真连网关，收到第一条真实 `C2C_MESSAGE_CREATE` | **完成** |
| P3 | 关 `dry-run`，真实回复闭环 | **完成**（2026-10-31 12:34，`POST /v2/users/{openid}/messages` 返回 HTTP 200，往返 1.3 秒） |
| P4 | 接大模型 + 每日条数熔断 | **完成**（2026-11-02，DeepSeek `deepseek-flash`，实测一条 in=60/out=139 ≈ 0.0012 元，`daily-reply-limit=200` ≈ 一天 1 元上限） |

## P4 的模型事实（实测，别按旧印象改回去）

- 模型名现在是 **`deepseek-flash` / `deepseek-v4-pro`**，`deepseek-chat`/`deepseek-reasoner` 已过时；`base_url=https://api.deepseek.com`，`Authorization: Bearer <key>`。
- **两个模型都是推理型**（响应含 `reasoning_content`）。**`max_tokens` 给小了会得到空 `content` + `finish_reason=length`**——思维链吃光额度，表现为"机器人回了条空消息"。`max-output-tokens=800` 且对空正文有兜底文案。
- 生效字段是 **`reasoning_effort`**（实测 low 时 reasoning_tokens=9，而 `effort` 被服务端静默忽略、仍是 20）。
- 单价（元/百万 token，分高峰与空闲）：flash 输入未命中 1~2、输出 4~8；v4-pro 输入 4.5~9、输出 13.5~27。配置按高峰档填 2/8。无免费额度，余额 ¥9.95。
- 切换零成本回退：`llm.enabled=false` 立刻回到 EchoBrain，一行配置。

## P2/P3 实测落定的事实（不再是未知项）

- `GET https://api.bot.qq.com/gateway` → 返回 `wss://api.sgroup.qq.com/websocket`。**`/gateway` 正确。**
- `intents=33554432`（1<<25）**服务端直接接受，不需要额外申请**。
- 握手实测：`op10 Hello (heartbeat_interval=41250ms)` → `op2 Identify` → `op0 READY(带 session_id)`；心跳 `op1 → op11 ACK`；断线后 `op6 RESUME → t=RESUMED` 恢复成功。
- **报文里有两个同名 `id`**：外层是事件 id（`C2C_MESSAGE_CREATE:xxx`），**`d.id`（`ROBOT1.0_` 开头）才是被动回复要回传的 `msg_id`**。取错就回不了话。
- 中文经 WebSocket 到 Java 完好无乱码（`你是谁呀？` 实测）。


---

## 6. 风险登记

| 风险 | 应对 |
|---|---|
| `gateway-path` / intents 开通状态与文档转述有出入 | 已抽成配置；第一次真连的原始帧会全量打日志，按实测调 |
| 被动回复窗口 60 分钟 / 4 次 | **长回答必须合并成 1~2 条消息，不能拆多条刷屏** |
| API 账单 | `BudgetGuard`（P4 做）：单条 max_tokens 上限 + 每日条数熔断 |
| 调试门被局域网利用 | `server.address: 127.0.0.1` 只绑回环；且 `dry-run=false` 时该端点直接 403 |
| 个人主体不能公开上架 | 玩具不需要 |

---

## 7. 预留扩展点（现在不做）

`Brain` 加实现即可换模型或接 RAG；媒体处理（语音/OCR）真要加时，Python 作为**被 Java 调用**的算法服务进来，不做入口转发层；群场景在 `EventDispatcher` 里补 `GROUP_AT_MESSAGE_CREATE` 分支。
