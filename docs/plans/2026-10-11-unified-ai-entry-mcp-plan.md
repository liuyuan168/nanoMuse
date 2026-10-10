# nanoMuse 统一 AI 入口改造方案（2026-10-11 批准）

> 目标：手机上以 nanoMuse 为唯一 AI 入口——配置 OpenAI 兼容端点对话，经 MCP 调用
> 自研 app（xinzhi / larry-todo / pagevault）与第三方 app（Shizuku/无障碍）。
> 架构决策：自研 app 走 HTTP MCP，第三方 app 走 Shizuku/无障碍 MCP server。

## 现状盘点（探索结论）

`E:\workspace\nanoMuse`（OpenMinis 1.13 fork，干净快照，origin = github.com/liuyuan168/nanoMuse）：

- Kotlin 宿主 `com.openminis.app`（Gradle，applicationId `io.github.nanomuse.app`，minSdk 26 / targetSdk 35）
- agent 循环：`tools/AgentTools.kt`（shell_execute / file_read / file_write / file_edit / read_image / browser_use / memory_write / memory_get）
- `provider/openai/OpenAIProvider.kt`（3714 行）已支持 `customBaseURL`（OpenAI 兼容网关：ollama / LM Studio / LiteLLM 等）
- PRoot Alpine 沙箱（`sandbox/`）：shell_execute 在隔离 Linux 环境执行
- MCP host：`data/repository/MCPRepository.kt`（487 行）管理 `/var/minis/mcp-servers/servers.json`（Claude-Desktop 格式；HTTP/SSE + STDIO + OAuth 配置模型；`$$VARNAME` 环境变量占位符）；实际调用由沙箱内 Python `minis-mcp-cli` 完成（`tools <server>` / `call <server> <tool> [args]`），模型经 shell_execute 驱动
- Shizuku：`sandbox/offload/ShizukuOffloadHandler.kt` = `android-shizuku-cli`（12 组子命令：package/permission/activity/display/settings/user/network/input/notification/file/device/service；uid 2000/0）
- 无障碍：`accessibility/MinisAccessibilityService.kt`（手势注入/事件环/NodeRegistry）backing `android-a11y-cli`（tap/swipe/screenshot/nodes/events/click/type）；`OffloadGate` 权限闸门；`AccessibilityRecoveryManager` 自恢复
- manifest：前台服务 mediaPlayback 类型（规避 Android 14+ 6h cap）、WAKE_LOCK、SYSTEM_ALERT_WINDOW、RECORD_AUDIO、POST_NOTIFICATIONS

结论：需求约 80% 已存在，主线是「跑起来 + 自研 app 接成 HTTP MCP server + 操控能力统一 MCP 形态」。

## 目标架构

```
┌──────────────────────Redmi Turbo 5 Max──────────────────────┐
│  nanoMuse (Kotlin宿主+PRoot沙箱)                             │
│  ├─ 对话UI → agent循环 → OpenAI兼容端点(customBaseURL)        │
│  └─ minis-mcp-cli (沙箱内Python, MCP客户端)                  │
│      ├─→ xinzhi        https://111.231.171.174:7082/api/mcp   │ 公网HTTPS
│      ├─→ larry-todo    http://127.0.0.1:8765/mcp  (NanoHTTPD) │ loopback+token
│      ├─→ pagevault     http://127.0.0.1:8766/mcp  (NanoHTTPD) │ loopback+token
│      └─→ phone-ops-mcp stdio (沙箱内Python server)            │
│            ├─ android-shizuku-cli (pm/am/input/settings/wm)   │ 操控第三方app
│            └─ android-a11y-cli   (tap/swipe/screenshot/nodes) │ UI自动化
└──────────────────────────────────────────────────────────────┘
```

PRoot 不隔离网络栈：沙箱内 `127.0.0.1` 直通手机本机；Python 不受 Android
`network_security_config` 约束，loopback 明文 HTTP server 可直接访问。

## Phase 0：装机与打通基线

1. `E:\workspace\nanoMuse\android\src\android` 下 gradle 构建 APK，adb install
2. 手机配置 OpenAI 兼容端点（customBaseURL + apiKey）
3. 激活 Shizuku（无线调试配对）+ 开启 nanoMuse 无障碍服务
4. 验收：对话跑 `android-shizuku-cli activity top`、`android-a11y-cli screenshot`

## Phase 1：xinzhi 公网 HTTP MCP 端点（试点）

1. 新建 `D:\work\xinzhi\internal\api\handlers_mcp.go`：移植 `tools/xinzhi-mcp.py` 的
   dispatch（initialize / tools/list / tools/call）；protocolVersion `2025-03-26` +
   `Mcp-Session-Id`；工具：search_xinzhi / recent_items / ask_xinzhi / latest_digest /
   sources_status
2. `internal/api/server.go` 注册：`mux.Handle("/api/mcp", s.auth(http.HandlerFunc(s.handleMcp)))`
   —— `/api` 前缀零改动复用 nginx；`s.auth()`（Bearer + `?token=`）、`s.logRequests` 复用；
   SSE 范式参考 `internal/api/sse.go`
3. nanoMuse 注册 url `https://111.231.171.174:7082/api/mcp`，token 走 `$$XINZHI_TOKEN`
4. 验收：对话「搜一下最近值得看的 docker 内容」→ ask_xinzhi 带引用回答

## Phase 2：larry-todo 嵌 loopback MCP server

1. 依赖 `org.nanohttpd:nanohttpd:2.3.1`
2. 新增 `mcp/McpServer.kt`（NanoHTTPD 子类，bind 127.0.0.1:8765，`POST /mcp`
   JSON-RPC；Bearer token；runBlocking 调 repository）
3. 工具面映射 `TodoRepository`：list_todos / add_todo / complete_todo / reopen_todo /
   delete_todo / update_todo（渠道中立，Feishu/Server 跟随 config）
4. `McpService` 前台 Service 承载；`ConfigStore` 加 mcpToken；ConfigScreen 展示/重生成
5. nanoMuse 注册 `http://127.0.0.1:8765/mcp`
6. 验收：对话「加一条明天上午买咖啡的待办」→ 飞书/服务端可见

## Phase 3：phone-ops-mcp（Shizuku/无障碍统一 MCP 形态）

1. 沙箱 rootfs 内放 `phone-ops-mcp` Python stdio server（stdlib JSON-RPC，
   模板 `D:\work\xinzhi\tools\xinzhi-mcp.py`），工具实现 = subprocess 调 offload CLI：
   - open_app(pkg)（含 freeform 小窗变体）/ stop_app / tap / swipe / key / text
   - screenshot / ui_nodes / ui_events / app_list / app_info
2. servers.json 注册 stdio server；工具描述写明「MCP 调用失败可先 open_app 拉起再重试」
3. 验收：对话「打开抖音刷十分钟」→ open_app + swipe 序列
4. 移植 DouyinDuoKai 的 `dumpsys input` 真实 frame 修正（澎湃OS 小窗 0.7 缩放坑）；
   保活三件套与 a11y 自恢复已由宿主覆盖，不重复搬

## Phase 4：pagevault 嵌 MCP server

1. 原生 Java 层 NanoHTTPD，bind 127.0.0.1:8766，token 鉴权
2. 工具面：search_archives（FTS5+bm25+snippet）/ read_archive / capture_url
   （CAPTURE_POOL 排队）/ vault_stats / delete_archive / backup_status
3. `PageVaultPlugin.load()` 起服务 + 新前台 Service 保进程
4. 验收：对话「存这篇文章并搜之前的 docker 文章」

## Phase 5（可选治理）

- 敏感工具审批票据（confirmed HMAC-SHA256，参考 nanoMuse `bridge/mcp_server.py`）
- 工具结果字符预算截断（参考 pagevault 桌面版 `tools.ts`）
- MCP server 状态查询

## 设计决策

- 传输层：自研 app 统一 streamable HTTP（loopback 明文 + Bearer token）；phone-ops 走
  stdio（同沙箱零网络）；token 经 `$$VAR` 环境变量注入 servers.json
- 不新建独立 phone-ops app：Shizuku/无障碍已由宿主持有，另建 app 要重复解决授权与保活
- 试点顺序 = 风险递增：xinzhi（公网 Go）→ larry-todo（loopback Kotlin）→ phone-ops
  （沙箱 Python）→ pagevault（Java+Capacitor 桥最厚）
- nanoMuse 改动放独立分支，方便跟 upstream

## 明确不做

- 不改 nanoMuse Kotlin agent 循环 / MCPRepository 核心
- 不迁移 DouyinDuoKai UI/定时器编排（价值 = shell 命令层 + 实测坑位记录）
- 不动 aihot / picoclaw 等无关项目
