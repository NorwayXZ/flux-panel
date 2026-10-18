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
