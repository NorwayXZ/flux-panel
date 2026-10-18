# AI 运维

面板“系统管理 → AI 运维”支持按节点执行自然语言定时任务。模型由管理员配置：API Base URL（通常以 `/v1` 结尾）、模型名称、API Key。接口须兼容 OpenAI Chat Completions 并支持 `tools` / `tool_calls`。密钥使用面板 JWT_SECRET 加密保存，接口不回显。更换 JWT_SECRET 前需备份，否则需要重新填写模型密钥。

## 使用

1. 面板与需要维护的 Linux 节点升级到 `2.84.0-beta.3` 或更高版本。
2. 填写模型配置。模型为空时，手动执行明确报错；到期的定时任务记录为“模型未配置”。
3. 新建任务，填写要求、节点、时区与六段式 Cron（包含秒）。`0 0 3 * * *` 表示所选时区每天03:00。
4. 先使用“只读检查”手动执行，查看运行记录，再按需要授权维护和启用定时任务。

示例要求：每天检查所有选定节点的磁盘和 inode 使用率。如果超过85%，先分析 journal 和 /var/log 占用；只在日志占用是主要原因时按任务保留限制清理，再检查磁盘。无法处理时说明原因。

## 工具和权限

- 检查磁盘、inode、/var/log 子目录大小和 journal 占用。
- 检查任务白名单中的 systemd 服务。
- 允许维护时，可 rotate/vacuum journal，并验证使用量；不会修改 journald 的永久配置。
- 允许维护时，可重启白名单服务并检查是否 active。重启可能中断连接；建议先用应用服务测试。
- 旧日志清理需要额外勾选授权。只删除 `/var/log` 下超过保留天数、符合轮转命名模式的普通文件（如 `app.log.1.gz`、`syslog.2`）。跳过当前日志、符号链接和不符合模式的文件。每次最多500个文件或1GiB，触及上限会返回部分执行结果和错误。

模型不能运行任意 shell、改变授权节点、修改服务白名单或降低日志保留限制。节点端再次验证权限，后台要求磁盘检查后才能清理、检查指定服务后才能重启。无法通过这些工具处理的任务会报告限制。

## 调度与记录

调度器每30秒检查一次到期任务；不追补停机期间每一次错过的运行。同一任务不会重叠，面板同时最多执行两项任务，满载时到期任务等待下一轮。面板应以单实例连接该数据库。重启时未完成的运行标为“执行中断”，不自动重放已执行的维护动作。

每项任务限制约10分钟、12轮模型回复、30次工具调用，单次模型回复上限2048 tokens。工具输出、响应体和上下文均有限制。节点离线、工具失败、模型漏查节点会被记录为失败或部分失败；模型没有执行任何检查不能记为完成。记录保存90天。

如勾选任务通知且系统已启用 Telegram，修复完成、首次异常或异常状态变化时会发送通知。健康检查保持安静，连续相同状态问题不重复通知。详情以面板工具记录为准，模型总结可能出错。

日志和节点检查结果会发送到管理员配置的模型服务。普通运行不会测试真实模型；发布验证使用本地兼容 API 测试服务。实际模型连通和模型判断质量需配置后验证。新建任务默认不启用调度、不授权旧日志清理、不发送通知。

## 实时对话

“AI 运维”默认打开实时对话。它复用同一份模型配置，支持流式回复、连续追问、历史会话、停止回复以及工具执行状态。需要模型接口支持 Chat Completions SSE；兼容接口返回普通 JSON 时会以整段回复显示。

不选节点可以直接咨询运维问题；选择节点后才开放节点工具。每轮发送时以侧边栏的节点和权限为准，默认为只读。允许维护时可额外授权日志清理和白名单服务重启。上一轮的检查不会被当作本轮维护的前提，执行前必须重新检查。

点击停止会取消当前模型请求并停止后续工具调用，已执行的工具不会撤销；正在节点执行的命令受超时限制。会话仅对创建它的管理员可见，刷新后可从历史会话继续。每位管理员最多一轮对话并发，面板同时最多两轮对话；聊天记录保留90天。聊天不会自行创建或改变定时任务，仍需在“定时任务”页保存计划。

### 旧版节点和 Agent 升级

聊天可调用 `inspect_agent_upgrade` 查询节点版本、面板升级任务和安装日志；旧 Agent 不支持 OpsCapabilities 时，磁盘检查可回退到固定的只读终端探针。探针只运行面板内置的磁盘、inode、日志用量及升级诊断命令，不接收模型生成的 shell。旧节点连终端协议也不支持时，会报告通道不可用，需先恢复受支持的管理通道；AI 不能凭空访问服务器。

要让聊天执行官方 Agent 升级，选择节点，把权限改成“允许维护”，并单独开启“允许升级所选节点 Agent”。后端要求同一轮先检查升级记录，只能升级已选节点，调用面板已有升级流程并记录操作者。提交成功代表异步升级已排队，不代表升级完成，须查询任务和重新上线版本验证。升级可能短暂中断代理连接。定时任务不提供自动升级工具。

## Panel context and scheduled terminal maintenance

`inspect_panel` is available only with `allowPanelRead`. It provides a controller-derived
API reference, selected-node metadata, tunnel/forward relationships and global monitoring
metadata (at most 200 entries). It does not expose node secrets or turn API paths into callable
operations. Regenerate the API reference with `python3 scripts/generate-ai-panel-catalog.py`
after controller changes. The embedded guidance explains ownership and health semantics;
it cannot replace live evidence or guarantee model understanding of every feature.

Scheduled tasks may explicitly save `allowTerminal=true` with maintenance mode. They then use
the same selected-node terminal tool as chat, without per-run confirmation. User requirements
must describe thresholds, permitted cleanup and retention. Do not delete unknown files, backups,
credentials, databases or live logs. Verify free space and affected services after repair.
Each run remains bounded by the existing 10-minute / 30-call limits; more than 50 nodes need
multiple tasks. Global alert metadata is visible only after opting into panel reads. Disabled
or read-only tasks do not gain terminal execution.

Current limits: control-plane mutation tools for DNS, exit switching, private proxies and
aggregation are not implemented by this context feature. The assistant must report this
boundary rather than modifying panel SQL or claiming universal automatic repair. A catalog
of an API is documentation, not evidence that the assistant can invoke it. No production
schedule is automatically created: the user defines its cadence, scope and requirements.
