# QQAgent

个人玩具级 QQ 聊天机器人：走 **QQ 官方开放平台 API v2**，纯 Java（Spring Boot 3.5 / JDK 21），自己实现 WebSocket 网关客户端。设计取舍与已核实的协议常量见 [`docs/PLAN.md`](docs/PLAN.md)。

## 怎么启动

### IDEA（推荐，不碰命令行）

1. 用 IDEA 打开本目录。若右侧 Maven 面板是空的：右键 `pom.xml` → **Maven → Reload Project**。
2. 打开 `src/main/java/com/qqagent/QqAgentApplication.java`，点 `main` 方法旁的绿色箭头 → **Run**（或 Debug，能打断点看消息进来）。
3. 日志看到这两行就成了：
   ```
   access_token 已刷新，有效期 xxxx 秒
   鉴权成功 session_id=... 订阅 intents=33554432
   ```

IDEA 的 Run 默认工作目录就是项目根，所以能读到 `qq-local.properties`。要是启动时打出「未配置 qq.app-id / qq.client-secret，跳过网关连接」，就是工作目录被改过——在 Run/Debug Configurations → Configuration → Working directory 改回项目根。

### 命令行

```bash
mvn spring-boot:run
```

两种起法完全等价，配置和凭据来源都一样。

## 凭据放哪

在 **`qq-local.properties`**（项目根，已被 `.gitignore` 忽略，不要提交、不要贴给别人）：

```properties
qq.app-id=你的AppID
qq.client-secret=你的AppSecret
```

`application.yml` 里这行把它读进来，文件不存在时自动跳过、应用照常起：

```yaml
spring: config: import: optional:file:./qq-local.properties
```

改完要**重启应用**才生效，配置只在启动时读一次。

> 早先还往用户环境变量里写过 `QQ_APP_ID` / `QQ_CLIENT_SECRET`，现在程序已经不读它们了。想清掉：
> `reg delete HKCU\Environment /v QQ_CLIENT_SECRET /f`（AppID 那条同理，留着也无害）

## 拿凭据的步骤（一次性）

注册入口 **https://q.qq.com**（QQ 开放平台，纯 JS 页面，要浏览器打开），应用列表 `https://q.qq.com/#/apps`，文档 `https://bot.q.qq.com/wiki`。

邮箱注册 → 姓名/身份证号/手机号 → 扫码绑一个现有 QQ → 创建机器人 → 打开服务开关 → 把 AppID/AppSecret 填进 `qq-local.properties` → 管理端「**开发设置 → 开发体验用户**」加上你的主号（不加收不到私聊事件）。

## 开关（都在 `application.yml`）

| 配置 | 作用 |
|---|---|
| `qq.dry-run` | `true` 只打日志不真发消息。为 `true` 时 `/debug/c2c` 调试门才开放，可以没有 QQ 也能测链路 |
| `qq.allowed-open-ids` | 回复白名单。**空 = 放行所有人**；填了则只回这些人。留空跑一次，从日志 `进来 [...] openid=xxx` 抄出自己的那串再填回去 |
| `server.address` | 现绑 `127.0.0.1`，局域网连不进来 |
| `qq.gateway-path` | 取 wss 地址的路径，已实测 `/gateway` 正确 |
| `qq.intents` | `33554432`（1<<25，群聊与单聊事件），已实测无需额外申请 |

## 大脑（模型）

`llm.enabled` 一个开关切换，不用改代码：`false` → `EchoBrain`（**零花费**，只回"收到了: xxx"）；`true` → `LlmBrain`（真调模型）。key 放 `qq-local.properties` 的 `llm.api-key`。

```properties
llm.model=deepseek-flash       # 或 deepseek-v4-pro（贵一档：输出 13.5~27 vs 4~8 元/百万）
llm.max-output-tokens=800      # 别调小：推理型模型，额度被思维链吃光会回一条空消息
llm.reasoning-effort=low       # 生效字段是 reasoning_effort，effort 会被服务端忽略
llm.daily-reply-limit=200      # 账单硬闸门，约等于一天 1 元
```

实测一条「你是谁？能做什么？」：输入 60 / 输出 139 token ≈ **0.0012 元**，1.5 秒返回。
DeepSeek 模型名是 `deepseek-flash` / `deepseek-v4-pro`（网上教程里的 `deepseek-chat` 已过时）。余额：`GET https://api.deepseek.com/user/balance`。

## 代码结构

```
gateway/AccessTokenService   token 获取与缓存（提前 5 分钟刷新，401 自动失效重取）
gateway/GatewayClient        拨 wss 长连接、op2 鉴权、op1 心跳、op6 断线恢复、退避重连
inbound/EventDispatcher      按事件的 t 字段分发；handle() 里是主控逻辑
policy/ReplyPolicy           白名单
brain/{Brain,EchoBrain,LlmBrain} 回什么内容 —— 想改机器人行为就改这里
brain/{ConversationStore,BudgetGuard}  上下文轮数 + 账单熔断
outbound/QqMessageSender     POST /v2/users/{openid}/messages
debug/DebugController        本地灌假消息，验证链路用
```

## 官方接口的硬约束（决定写法）

- 单聊被动回复：**收到消息后 60 分钟内、最多 4 次**。长回答合并成 1~2 条发。
- 报文里有**两个同名 `id`**：外层是事件 id，`d.id`（`ROBOT1.0_` 开头）才是回复要带的 `msg_id`。
- 全程只有**我们主动出站**这一种连接方向：腾讯访问不到本机，收消息靠的是它在我们已建立的长连接上回写。
- 官方 SDK 只有 Go / Python / Node.js，**没有 Java**，所以网关层在本仓库里自己实现。
- `go-cqhttp` 早已删库，教程仍在推；OP 码也别按 Discord 那套记（QQ 是 10=Hello、1=Heartbeat、11=ACK）。
