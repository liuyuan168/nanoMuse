# nanoMuse 技能包

本目录收录为个人七项目工作流编写的 nanoMuse 技能（SKILL.md 格式，
安装后位于手机 `/var/minis/skills/<id>/SKILL.md`，描述注入系统提示词，按需触发）。

| 技能 | 对应场景 | 依赖 MCP server |
|---|---|---|
| `one-shot-collect` | 一句话收藏、笔记、行动：先展示计划、追问缺口、三处创建、回报链接、重试幂等 | pagevault、larry-todo、memos（可选降级） |
| `unblock-answer` | 任务卡住时手机回答并续办；完成后回报产物/验证/待验收，按需生成复盘草稿 | picoclaw-relay、larry-todo、memos |

配套方案与跨项目分工见
[docs/plans/2026-10-12-seven-project-workflows-nanomuse.md](../docs/plans/2026-10-12-seven-project-workflows-nanomuse.md)。

## 安装

任选其一：

1. **对话内自装（推荐）**：把 SKILL.md 的内容粘贴到 nanoMuse 对话里，说
   「把这个写成技能 one-shot-collect 并启用」。agent 会用 file_write 写入
   `/var/minis/skills/one-shot-collect/SKILL.md`，随后在 Skills 页开关确认已识别。
2. **adb（debug 构建）**：

   ```bash
   adb push skills/one-shot-collect/SKILL.md /data/local/tmp/
   adb shell run-as io.github.nanomuse.app sh -c \
     'mkdir -p files/minis-global/skills/one-shot-collect && \
      cp /data/local/tmp/SKILL.md files/minis-global/skills/one-shot-collect/SKILL.md'
   # 进一次 Skills 页触发重扫（reloadFromDisk）
   ```

3. **zip 导入**：Skills 页的导入入口，选含 SKILL.md 的 zip。

## MCP 依赖注册

两个技能复用已接入的 pagevault（127.0.0.1:8766）、larry-todo（127.0.0.1:8765）、
xinzhi 与 phone-ops；另依赖两个**待对端项目交付**的 server。注册进
`/var/minis/mcp-servers/servers.json`（token 一律走 `$$VAR` 环境变量，不落文件）：

```json
"memos": {
  "url": "<memos 侧 MCP 端点，以其交付文档为准>",
  "headers": { "Authorization": "Bearer $$MEMOS_MCP_TOKEN" }
},
"picoclaw-relay": {
  "url": "<relay 侧 MCP 端点，以其交付文档为准；当前中继为明文 HTTP:7088>",
  "headers": { "Authorization": "Bearer $$PICORELAY_TOKEN" }
}
```

- **memos**：云端单实例（usememos/memos，8084）的 MCP 端点由 memos 侧工作提供；
  上线前 `one-shot-collect` 自动降级（跳过笔记，说明原因）。
- **picoclaw-relay**：中继当前为明文 HTTP 部署。`unblock-answer` 已内置约束：
  密钥、凭证、隐私类回答不提交；中继迁移可信 HTTPS 前不要放开这条。

## 验收清单（真机对话逐条试）

| # | 提示词 | 期望 |
|---|---|---|
| 1 | 分享一篇链接 +「留存这篇，记下我认同的〈某观点〉，周末试一下」 | 先列出三类创建计划，随后存档/笔记/待办各建一条，回报 id 与链接 |
| 2 | 同一链接原样再说一遍 | 不产生重复条目；回报「已在金库/已记过」并复用旧 id |
| 3 | 「记一下这篇的观点」但没说是哪个观点 | 一次追问补全，不擅自编造观点 |
| 4 | 未注册 memos 时做第 1 条 | 存档与待办照常完成，回报里说明笔记未记及原因 |
| 5 | 「现在有什么在等我回答」 | 列出 relay 待答问题卡：任务标识、问题全文、已等多久 |
| 6 | 口述一个选型回答 | 复述要点并确认后提交，回报任务继续/再次卡住 |
| 7 | 含密码的补充信息 | 拒绝提交并提示明文 HTTP 风险 |
| 8 | 任务完成后「复盘一下」 | 展示目标/结果/验收/遗留草稿，验收栏区分「AI 报告」与「用户已验收」；同意后写入 memos 并可转待办 |
