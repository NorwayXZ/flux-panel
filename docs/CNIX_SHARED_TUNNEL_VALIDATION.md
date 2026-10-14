# CNIX 共享隧道验证记录

开发基线：`07b7d4f`。工作树：`/Users/norwayx/Documents/New project/cnix-shared-development`，分支 `codex/cnix-shared-tunnel`。原工作区的私有代理功能修改没有复制、覆盖或提交。

本次最终结果：后端 `mvn package` 通过，387 项测试、零失败/错误/跳过；前端 34 项测试通过、生产构建通过；下述 GOST 包的 race 测试通过，另对所有 handler 及 relay/TLS connector、dialer、listener 做了兼容测试。实际 Agent 主程序 `go build` 通过。本机没有 Docker CLI，新表的真实 MySQL 5.7/8.0 容器验收未执行。

本地日志（临时文件可能被系统清理）：`/tmp/cnix-java-tests.log`、`/tmp/cnix-go-tests.log`、`/tmp/cnix-runtime-final.log`、`/tmp/cnix-persistence-final.log`、`/tmp/cnix-gost-compatibility.log`、`/tmp/cnix-frontend-tests.log`、`/tmp/cnix-frontend-build.log`。本地编译的 Agent 为 `/tmp/cloudnest-cnix-agent`，用于构建验证，不是已发布安装包。

## 自动化验证

运行命令：

```sh
cd go-gost/x
go test -race ./handler/relay ./socket ./chain ./config/parsing/service ./internal/util/tls ./limiter/traffic -count=1

cd ../../springboot-backend
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home mvn package

cd ../vite-frontend
npm test
npm run build
```

重点测试：

| 验收项 | 对应证据 |
|---|---|
| 三个独立目标同时双向传输 | `handler/relay/shared_runtime_test.go`：三个目标分别返回 A/B/C 标识，每个上传 64 KiB 并完整返回，入口计数精确独立 |
| 共享映射端口与本机端口不同 | 同一测试使用真实 TCP 透明代理 X→Y，断言 X≠Y，所有业务只配置 X，出口仅一个服务 |
| B 暂停/删除不影响 A/C | 同一测试保留 A 的长连接，移除 B 授权后 A 长连接和 C 新连接继续传输 |
| 运行进程分别重启恢复 | 同一测试分别终止并重启入口、出口独立进程，从保存的 GOST JSON 加载后重新验证 A/C |
| 不串目标、并发创建不覆盖 | 三目标数据验证及 `CnixSharedRelayServiceTests` 的实际 SQL 并发写入、完整授权快照验证 |
| 匿名、错密、撤销、跨目标被拒绝 | relay 实际请求及 TLS 连接测试，不只检查配置字符串 |
| 仅允许 TCP CONNECT | 实际发送 BIND、UDP 和不带合法命令的请求，确认拒绝 |
| TLS 信任与身份 | 实际信任根连接、错误身份和无信任根拒绝；新 Agent 能力检查防止旧 Agent 创建共享服务 |
| CNIX 故障关闭 | 关闭透明映射后入口无法转发；没有直接连接出口或落地的配置 |
| 出口侧 DNS、内网保护 | `landing.test` 只能在出口测试解析器识别，仍成功传输；回环、内网、元数据、混合公私网 DNS 答案拒绝 |
| 独立流量和限速 | 入口精确计数，B 实际限速而 A/C 不受影响；同一限速规则的两个业务使用独立速率桶并跟随规则修改 |
| 配额/授权暂停 | FlowController 的共享暂停调用验证、现有配额回归测试、实际 SQL 授权主体筛选和 relay 到期断开测试 |
| 保存失败恢复 | Agent `CnixGrants` 故意制造磁盘写入失败，验证旧配置恢复、文件权限 0600；后台模拟下发失败，验证旧业务目标与快照恢复 |
| 迁移幂等 | H2 MySQL 模式运行新表初始化及 SQL 文件两次；已有表数据和老隧道不改变 |
| 旧功能兼容 | 后端完整测试、Go socket 回归（含 nftables、普通服务、UDP、NAT）、前端测试和构建 |

## 测试边界

三目标测试运行的是生产 GOST 加载器、handler、connector、TLS、chain、listener、统计与限速代码的独立测试进程。测试公开目标地址在出口的最后一次解析/拨号由测试夹具映射到本地 echo 服务，以便验证公网目标授权和完整数据，同时避免修改本机路由或使用真实用户的落地。替换解析/拨号实现只发生在 `_test.go`；正常 Agent 使用系统 IPv4 解析和已经校验的公网 IP 拨号，不包含测试映射。Agent 的生产配置保存命令另有专门测试；实际 Agent 主程序已经编译。

SQL 测试使用嵌入式 H2 的 MySQL 模式，Agent RPC 在后台单元测试中由受控响应替代。这里没有实际运行新增功能的 MySQL 5.7/8.0 面板 HTTP 全流程，也没有将“模拟 RPC 成功”当作真实业务联网证据。真实数据传输证据来自前述 GOST 运行测试。

真实 CNIX、真实云服务器两端 Agent、云防火墙和供应商白名单均未部署/验证。当前 17148 旧线路没有改动。

## 将来的受控上线验收

需要另行授权部署，准备新的专用 CNIX 映射和独立出口端口，安装本分支的两端 Agent 与面板，确认云防火墙和 CNIX 白名单。随后通过实际面板创建一次共享隧道及 A/B/C，使用三个受控落地验证协议认证、双向数据和端口路径，再验证删除 B、两端 Agent 分别重启和计费不翻倍。

使用原 VLESS/REALITY 客户端验证时保留原协议认证信息，只替换国内入口地址、端口。不得将现有 17148、443/12001 或旧出口转发 87 改为共享服务。真实 CNIX 验收未完成之前，本次交付结论仅是可审查代码及本地验证通过。
