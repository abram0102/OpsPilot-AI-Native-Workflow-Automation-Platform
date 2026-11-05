# 用户操作指南（中文版）——把 OpsPilot 交给 Codex

这份交付物是**详细开发说明书**，不是已开发的软件。不要误以为下载 ZIP 后产品就能直接运行。Codex 将逐项实现。

## 第一步：准备本地环境（只需要做一次）

1. 在 macOS 上安装 Docker Desktop、Git、Node.js LTS、pnpm、Java 21、Python 3.12、Codex（或 Codex 可访问的开发环境）。
2. 打开 Terminal 验证：
   ```bash
   git --version
   docker compose version
   node -v
   pnpm -v
   java -version
   python3 --version
   ```
3. 在 GitHub 创建新的 **private** repository `opspilot`。不要把 API keys、`.env` 或 AWS 凭据提交到仓库。
4. 在电脑上解压本 ZIP，将整个文件夹中的文件复制到空的仓库根目录。尤其注意根目录的 `AGENTS.md`，它是给 Codex 的协作规范。
5. 通过 Git 或你熟悉的编辑器提交规划文件。Codex 可以指导命令，但创建账号和批准外部操作需要你本人完成。

## 第二步：首次打开 Codex

1. 打开包含 `AGENTS.md` 的仓库根目录，不是 `docs` 子目录。
2. 打开 `docs/CODEX_OPERATOR_PROMPT.md`，复制从 `You are the implementation engineer...` 到 `CURRENT TICKET: T00.` 的整段内容。
3. 将这段提示词发给 Codex。Codex 只应当完成 T00（目录与项目规范），随后停下并列出修改文件和测试结果。
4. 确认没有删改原有文件、没有 `TODO` 假实现、没有编造通过的测试。完成后再让它执行 T01。
5. 下一条指令可以直接用：
   ```text
   Implement T01 in docs/TASKS.md. Follow AGENTS.md. Run the listed acceptance checks and update docs/PROGRESS.md. Stop after T01. Do not start T02.
   ```
6. 重复把 T01 换成 T02、T03 ……直到 T36。Codex 每个 Ticket 必须停下，报告测试、实际修改、未完成事项；你可以先 Review 再 Commit。

## 第三步：什么时候必须自己操作？

| 阶段 | 你负责什么 | Codex 能负责什么 |
|---|---|---|
| T00–T01 | 确认 GitHub Repo / Docker 已安装；允许本地容器启动 | 创建仓库结构、Compose、脚本、健康检查 |
| T08 | Auth0 注册、创建 App/API、设置 Callback / Audience、把密钥存入本地 `.env` | 编写 OIDC 登录、前后端验证、自动化测试 |
| T20 | LLM 服务账号、API key 与账单上限 | 构建模型适配器、模拟器、测试/评估脚本 |
| T31 | AWS 账号、预算警报、审核部署费用与权限 | 生成 Terraform/CDK 和部署配置 |
| T32–T33 | 批准 Cloud 部署、DNS、密钥、必要的付费资源 | CI/CD、镜像、监控、健康检查、故障演练脚本 |
| T34–T36 | 决定展示网址、审查真实性、批准公开仓库 | 性能报告生成、文档、演示材料 |

**数据库相关：** 本地 PostgreSQL 数据库可以由 Docker Compose 自动初始化，Flyway 自动建表，你不必在 GUI 里手动执行几十条 SQL。AWS 托管数据库可由基础设施代码描述和部署，但需要你亲自登录 AWS、授权并审核费用。

## 第四步：每完成一个阶段，就检查一次

- Phase 0：前端、Java API、Python worker 都能启动/编译；本地 Postgres、Redis 通过健康检查。
- Phase 1：登录后能创建组织，两个组织的数据彼此隔离。
- Phase 2：用户能真实创建工单、上传文档，并从数据库读取，而不是前端写死数据。
- Phase 3：AI 可以读取工单、检索该组织文档、生成带来源的草稿，运行记录保存在数据库。
- Phase 4：草稿需要审批；重复审批或 worker 崩溃恢复后**不会**重复发布内容。
- Phase 5：跨组织攻击、错误 JWT、越权操作与 prompt injection 测试被拒绝；UI 和 CI 完整。
- Phase 6：你批准 AWS 费用后，才能部署 Staging，检查 TLS、私有数据库、告警和备份恢复。
- Phase 7：把真实 benchmark、AI evaluation、架构图、演示截图放进 README，整理简历要点。

## 第五步：Codex 做错了怎么办？

千万不要直接让它"继续往下做"来掩盖错误。把失败命令和错误贴给 Codex：

```text
Stay on the current ticket. Reproduce the following failure exactly:
[paste command and error]
Identify the root cause, implement the smallest correct fix, add a regression test,
rerun the acceptance checks, and stop. Do not weaken or skip any tests.
```

如果 Codex 使用了过时 SDK 或把原本要求 Java 的核心 API 改成全 Node，要求它解释差异和回到 `docs/PRODUCT_AND_ARCHITECTURE.md`，不能无理由改动整体架构。

## 第六步：如何保证项目对求职有实际价值？

- 不要把网站"能点开"当作完成。每个关键功能要有自动化测试和可复现的工程指标。
- 重点保留至少一段真实可解释的安全设计、一次 worker 崩溃恢复测试、一次数据库查询优化、一次 AI Agent 评估。
- 写简历时只使用自己真正跑出来的数值。没有上线前不要写生产用户数量；没有压测前不要写 10k 并发。
- GitHub README 应突出为什么这样设计，而不只是堆积一个很长的技术栈。
