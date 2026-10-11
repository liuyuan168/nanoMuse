# 七项目工作流批次：nanoMuse 侧改动方案（2026-10-12）

> 背景咨询：cloud-infra 仓《mcp-personal-seven-project-workflows》第一条回复，
> 首批选方向 1（收集闭环）、2（手机解阻）、6（结果复盘）。
> 本批分工：PageVault / Memos / LarryTodo / picoclaw-relay 侧改动由其他 agent 分别实施；
> 本文档记录 nanoMuse 侧的边界、落地物与对对端的语义假设。

## nanoMuse 在三个方向中的角色

咨询结论的分工原则：**nanoMuse 负责手机对话与交互，产品数据留在各产品**。
对应到三个方向：

| 方向 | nanoMuse 侧职责 | 主要落地在其他项目侧 |
|---|---|---|
| 1 收集闭环 | 分享一句话 → 展示计划 → 追问 → 三处创建 → 回报链接；重试幂等 | PageVault capture 接口、Memos MCP server、LarryTodo MCP（已有） |
| 2 手机解阻 | 展示问题卡 → 确认回答 → 提交 → 续办 → 回报产物/验证/待验收 | relay 问答接口与任务状态、LarryTodo 任务身份统一 |
| 6 结果复盘 | 复盘草稿的展示与确认交互（并入技能 `unblock-answer` 收尾） | 草稿写入 Memos、下一步转 LarryTodo、relay 任务结束信号 |

第 6 点咨询原文涉及项目为 LarryTodo + Relay + Memos，未列 nanoMuse；但「AI 执行结束
与用户验收完成分开记录」需要用户在手机确认验收，这段交互天然发生在 nanoMuse，
故并入技能 2，不单独建技能。

## 落地物

延续「不改 nanoMuse Kotlin agent 循环 / MCPRepository 核心」的既有决策，
本批全部落在技能与文档层：

- `skills/one-shot-collect/SKILL.md` —— 方向 1
- `skills/unblock-answer/SKILL.md` —— 方向 2 + 6
- `skills/README.md` —— 安装方式、MCP 注册增量、真机验收清单
- `docs/plans/2026-10-11-setup-and-mcp-registration.md` —— servers.json 注册条目补 memos / picoclaw-relay

## 对对端项目的语义假设（供其他 agent 对接）

nanoMuse 技能不硬编码对端工具名（运行时先 `minis-mcp-cli tools <server>` 按描述
对号），但流程依赖以下**语义能力**存在。对端实现时请覆盖；命名可不同，请在
各自交付文档注明，便于回填注册说明。

### memos（MCP server，待建）

1. **创建笔记**：支持草稿/不发布语义；入参含内容文本；返回可定位的 memo id 或链接。
2. **搜索笔记**：关键词检索已有笔记，用于查重防重复创建。

### picoclaw-relay（MCP server，待建）

1. **列出待答问题**：每条含任务标识（任务名/ID）、问题全文、等待开始时间、
   上下文摘要；区分不同电脑会话。
2. **提交回答**：按问题标识回答案，写入对应电脑会话。
3. **查任务状态**：返回 运行中 / 等待回答 / 已完成；已完成时含产物链接、
   agent 自测验证结果、待人工验收事项。

安全前提：中继当前为明文 HTTP（7088）。技能 2 已硬性约束「密钥、凭证、隐私类
回答不提交」；relay 侧完成可信 HTTPS 迁移后，由人工评估放宽。

### LarryTodo（已有 MCP，无需改动）

方向 2 的「统一任务身份与状态」由 relay 侧在问题卡中携带任务标识实现；
nanoMuse 只按标识回填待办备注（`update_todo` 全量语义，传参带全原字段）。

## 验收与状态

- 代码/文档验证：本批为 Markdown 资产，无构建面；SKILL.md frontmatter 与
  `SkillRepository.parseSkillMd`（name/version/description 必填，description ≤200 字符）
  已核对。
- 真机验收：`skills/README.md` 验收清单 8 条，需待 memos / picoclaw-relay 侧
  MCP server 上线后才能全部通过；1~4 条仅依赖现有 server，可先行验收
  （第 4 条验证 memos 未接入时的降级路径）。
- 部署：技能安装是手机端动作（对话自装 / adb / zip 导入），不涉及 APK 构建与发版。
