# nanoMuse 统一 AI 入口：装机与 MCP 接入指引

> 配套方案：`docs/plans/2026-10-11-unified-ai-entry-mcp-plan.md`
> 本文档是 Phase 0/1/2/3 装机后的手机端配置步骤与验收清单。

## 0. 装机（PC 侧）

```bash
adb install -r E:\workspace\nanoMuse\android\src\android\app\build\outputs\apk\debug\app-debug.apk
adb install -r D:\work\larry-todo\android\app\build\outputs\apk\debug\app-debug.apk
```

## 1. 手机端一次性授权（手动，共 3 步）

1. **OpenAI 兼容端点**：nanoMuse → Settings → Providers → 添加 OpenAI 类型，
   填 customBaseURL（如 `https://open.bigmodel.cn/api/paas/v4`）+ apiKey + 模型名
2. **Shizuku**：装/开 Shizuku app → 无线调试配对启动（每次重启手机要重新激活）；
   首次调用 `android-shizuku-cli` 时弹授权框点允许
3. **无障碍**：系统设置 → 无障碍 → nanoMuse 开启（受限设置需先「允许受限设置」）

## 2. 注册 MCP servers（nanoMuse 的 MCP 管理 UI 或文件浏览器编辑 servers.json）

servers.json 路径（nanoMuse 文件浏览器）：`/var/minis/mcp-servers/servers.json`

```json
{
  "mcpServers": {
    "xinzhi": {
      "url": "https://111.231.171.174:7082/api/mcp",
      "headers": { "Authorization": "Bearer $$XINZHI_TOKEN" }
    },
    "larry-todo": {
      "url": "http://127.0.0.1:8765/mcp",
      "headers": { "Authorization": "Bearer $$LARRYTODO_MCP_TOKEN" }
    },
    "pagevault": {
      "url": "http://127.0.0.1:8766/mcp",
      "headers": { "Authorization": "Bearer $$PAGEVAULT_MCP_TOKEN" }
    },
    "phone-ops": {
      "command": "phone-ops-mcp",
      "args": []
    }
  }
}
```

- `$$NAME` 引用 nanoMuse 的 App 环境变量（Settings → Environment Variables 里配），
  token 不落 servers.json
- `XINZHI_TOKEN` = 腾讯云 `/opt/xinzhi/.env` 的 `XINZHI_API_TOKEN`
- `LARRYTODO_MCP_TOKEN` = larry-todo 设置页「MCP 服务」区生成的 token
- larry-todo 的 MCP 开关在：larry-todo → 数据源配置 → 底部「MCP 服务（手机助手接入）」
- `PAGEVAULT_MCP_TOKEN` = pagevault 经 adb 广关注入的 token（见下）

### pagevault MCP 的开启方式（adb，无 UI）

```bash
# 开启（先启动一次 app 解除 stopped 状态；澎湃OS 拦隐式广播，必须显式 -n 指到组件）
adb shell am start -n com.smail.pagevault/.MainActivity
adb shell am broadcast -n com.smail.pagevault/.McpControlReceiver \
  -a com.smail.pagevault.mcp.ENABLE --es token <你的token>

# 关闭
adb shell am broadcast -n com.smail.pagevault/.McpControlReceiver \
  -a com.smail.pagevault.mcp.DISABLE
```

开启后 token 持久化（SharedPreferences），app 每次启动自动拉起 MCP 前台服务。
工具：search_archives / read_archive / capture_url / vault_stats / delete_archive /
backup_status。

## 3. 验收清单（在 nanoMuse 对话里逐条试）

| # | 提示词 | 期望链路 |
|---|--------|---------|
| 1 | 「跑一下 android-shizuku-cli activity top」 | shell_execute → offload → 前台应用 |
| 2 | 「android-a11y-cli ui screenshot 截个图看看」 | 无障碍截屏回图 |
| 3 | 「用 minis-mcp-cli tools xinzhi 看看有什么工具」（xinzhi 部署后） | HTTP MCP → 5 工具列表 |
| 4 | 「最近 docker 部署有什么值得看的」 | ask_xinzhi RAG 带引用回答 |
| 5 | 「帮我加一条明天上午买咖啡的待办」 | larry-todo MCP add_todo → 飞书/服务端 |
| 6 | 「打开抖音刷十条」 | phone-ops open_app + scroll_screen 序列 |
| 7 | 「搜一下金库里存的 docker 文章」 | pagevault search_archives FTS5+snippet |
| 8 | 「把 https://… 这篇存进金库」 | pagevault capture_url SingleFile 入库 |

## 4. xinzhi 端点部署（Phase 1 上线）

```bash
# 腾讯云（111.231.171.174）
cd /opt/xinzhi && git pull && go build -o xinzhi-hub ./cmd/xinzhi-hub && systemctl restart xinzhi
# 或按 deploy/ 现有流程；nginx /api 前缀规则无需改动（端点挂 /api/mcp）
```

## 5. 已知边界

- larry-todo / phone-ops 的 MCP 能力依赖 app 进程/沙箱存活；larry-todo 有前台
  服务通知保活，被系统杀后打开一次 app 即恢复
- Shizuku 每次手机重启需重新激活（无 root 固有限制）
- freeform 小窗启动（DouyinDuoKai 的 --windowingMode 5 场景）phone-ops v1 未内置
  坐标换算，可先 `settings_global` 打开 `enable_freeform_support` 再用 shell 组合
- minis-mcp-cli 对无状态单 POST JSON 端点完全兼容（已核对其 transport/http.py 实现）；
  GET /api/mcp 显式 405 = 无服务器推送流，属预期
