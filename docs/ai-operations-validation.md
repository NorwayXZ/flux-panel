# Realtime chat live validation — 2026-10-18

Published and installed panel release: `2.84.0-beta.6`; Agent requirement remains `2.84.0-beta.3`.

- [Release workflow 39092992959](https://github.com/NorwayXZ/flux-panel/actions/runs/39092992959) passed backend tests/package, MySQL 8/5.7 startup smoke tests, frontend tests/build, Go/race tests, platform builds, runtime image checks and publication of 16 release assets.
- beta.5 introduced realtime chat. Live testing found that its SSE String conversion corrupted Chinese characters while saved history remained correct. beta.6 writes explicit UTF-8 bytes and preserves reasoning context required by thinking-model tool calls. The first unpublished beta.6 build was cancelled to include both fixes before publication.
- Local frontend suite: 15 files / 52 tests passed. Backend chat tests cover split UTF-8 and tool arguments, explicit non-UTF-8 String converters, real HTTP streaming before completion, tool/owner permissions, model-key redaction, stop/release of concurrency slots, and reasoning-context tool round trips. Scheduled-operation regressions also passed.
- A 390px mobile preview showed document width 390px, no initial page scroll and collapsed permission settings. The composer and send action were visible.

Production verification:

- Host updater returned `success` / `Update completed` at 2026-10-18 11:30:44 Asia/Shanghai. The rendered panel reports beta.6 and defaults to realtime chat.
- Corrected the configured DeepSeek display-name string to the official API identifier `deepseek-flash`, keeping the existing endpoint and encrypted key. The previous identifier returned HTTP 400; the corrected identifier completed successfully.
- A real Chinese greeting streamed through the production proxy with multiple browser reads and readable Chinese text. A follow-up correctly recalled the verification code from the previous turn.
- A third turn selected only node 8 (NNC日本) in read-only mode. The model called `inspect_disk` successfully, received its actual result and generated the final Chinese response. All three turns persisted as `success` and could be read back from history.
- No production cleanup, restart or other mutation was requested. Only the selected node's disk, inode and log usage were inspected. Temporary validation conversations were removed afterwards; unrelated conversations were preserved.

Limits: production maintenance actions remain untested in this release validation. Chat permissions apply per turn and model summaries can contain mistaken interpretations; inspect the recorded tool result for authoritative measurements. Chat cannot create schedules; save schedules in the task form.

---

# AI operations beta validation — 2026-10-18

Published release: `2.84.0-beta.3`.

- GitHub release workflow [39087962740](https://github.com/NorwayXZ/flux-panel/actions/runs/39087962740) completed successfully. It checks backend tests, MySQL 8 / 5.7 runtime startup, frontend tests/build, Go tests/race checks, cross-platform Agent binaries and runtime images.
- The release contains 16 assets and is marked prerelease. The earlier beta.2 build was cancelled before publication when the upgrade comparator issue was found; beta.3 includes that fix.
- Local tests cover a real local Chat Completions HTTP server with tool-call round trips, model settings encryption/redaction, absent or damaged credentials, offline nodes, task overlap, node/service/mutation policy, retention arguments and bounded model responses. Node execution is replaced by a test double for these model-loop tests.
- Go tests cover service policy, bounded output, read-only rejection and cleanup of temporary rotated logs while protecting active files, symlinks and files outside the log root. Desktop and 390px mobile forms were inspected using test data.

Production panel verification:

- Host updater reported `success` / `Update completed` after installing beta.3. The rendered panel footer reports beta.3.
- Authenticated `/api/v1/ai-ops/overview` returned code 0. Settings remained empty and no tasks were enabled.
- A temporary disabled read-only task was saved successfully. Running it returned `AI 模型未配置，请先配置模型地址、名称和密钥`; no run was created. The temporary task was deleted afterwards.
- The node upgrade status now identifies existing beta.1 Agents as needing beta.3 instead of reporting them up to date.
- The production browser was left on AI operations → Model configuration for the administrator.

Remaining operational prerequisites: configure a real compatible model provider, upgrade the selected Linux Agents to beta.3 and run a read-only task before granting maintenance permissions. Production Agents were not upgraded as part of this panel deployment; no maintenance command or real-model request was executed against production nodes. Real model connectivity, output quality and production journal/service maintenance remain to be validated after configuration.
