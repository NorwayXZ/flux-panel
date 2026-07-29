## 2.27.1 Dynamic DNS Zone selector fix

- Gives every Cloudflare Zone a unique selector key so multiple domains registered under the same Cloudflare account can be selected independently in a Dynamic DNS rule.
- Preserves all existing Dynamic DNS rules, provider credentials, DNS records, nodes, tunnels, forwards, Home Access routes, and port reservations.
- Changes no database schema and keeps Agent `2.26.4` as the current target. No Agent update or restart is required.

### Upgrade and rollback impact

- This is a panel-only compatibility fix. Updating does not call any DNS provider API until an operator saves or manually runs a Dynamic DNS rule.
- Roll back with `sudo /usr/local/sbin/flux-panel-manager rollback` if needed. Existing DNS rules and records remain intact.

## 2.27.0 Home Access tunnel egress

- Adds a tunnel egress option to Home Access. A route can now follow `company -> home broadband -> transit VPS -> landing VPS -> Internet`, with the final tunnel node acting as the public egress IP.
- Lists eligible 2-level and longer tunnels by level and full node path, previews the topology before creation, and shows every path node with online state on the route card.
- Allocates one isolated authenticated SOCKS5 gateway port from every path node's configured port range. All gateway ports enter the global port ledger and cannot collide with forwards, tunnel hops, port pools, grants, internal mappings, private proxies, or other Home Access routes.
- Enforces user tunnel quotas and every shared path node's forward quota. A Home Access route counts once against its tunnel and once against each server it actually uses.
- Protects tunnels used by Home Access from deletion and performs all-path cleanup on failed creation or route deletion. Existing single-VPS Home Access routes remain compatible.
- Keeps Agent `2.26.4` as the current target. Existing Home Access-capable Agents already support the ordered GOST chain used by this panel release and do not need an Agent restart.

### Upgrade and rollback impact

- Adds nullable `home_proxy_route.egress_mode` and `home_proxy_route.egress_tunnel_id`, and the independent `home_proxy_gateway` table. Existing records default to `single`; no existing node, tunnel, forward, pool, lease, DNS, certificate, or Agent configuration is rewritten.
- Tunnel egress does not require a Port Resource pool on every path node. It automatically selects free ports inside each node's configured port range while respecting the global ledger.
- If a path node is offline, outdated, out of ports, or fails provisioning, creation is aborted and the route-specific gateways and reservations are removed.
- Before rolling back, delete Home Access routes using tunnel egress and wait for their cards to disappear, then run `sudo /usr/local/sbin/flux-panel-manager rollback`. Older panels ignore the new schema but cannot display or clean up tunnel-egress runtime left on Agents.

## 2.26.6 home IPv4 direct access and DDNS binding

- Adds IPv4 direct mode for Home Access: clients can connect to a real home public IPv4 and the home Agent then exits through the selected VPS.
- Dynamic DNS rules can now use either a server node or a home connector as the public-IP detection source. Home Access can bind connector-source `A` records for IPv4 direct mode and `AAAA` records for IPv6 direct mode.
- Direct home proxy cards now show the bound domain when available, expose one "check address" action for IPv4/IPv6, and include separate OpenWrt guidance for IPv6 firewall rules and IPv4 port forwarding.
- Authenticated SOCKS5 IPv6 guidance now recommends leaving the OpenWrt target IPv6 blank when authentication is enabled, avoiding breakage from temporary IPv6 address changes.
- Keeps Agent `2.26.4` as the current target. This is a panel and database-schema release; existing nodes and connectors do not need an Agent restart.

### Upgrade and rollback impact

- Adds nullable `dynamic_dns_rule.source_type`, `dynamic_dns_rule.connector_id`, `home_proxy_route.direct_ipv4`, `home_proxy_route.ip_checked_at`, `home_proxy_route.dynamic_dns_rule_id`, and `home_proxy_route.public_domain`, plus lookup indexes.
- Existing tunnels, forwards, port pools, published services, DNS providers, DNS records, certificates, and relay-mode home proxies are preserved.
- IPv4 direct requires a real public IPv4 and manual router port forwarding. CGNAT/private WAN addresses still need IPv6 direct or public relay.
- To roll back, delete any IPv4/IPv6 direct Home Access proxies created with this release first, then run `sudo /usr/local/sbin/flux-panel-manager rollback`. Older panels ignore the new columns but cannot manage direct services left running on Agents.

## 2.26.5 IPv6 home-access guide and independent Agent versioning

- Adds a contextual public-access guide to every direct IPv6 home proxy, including the exact OpenWrt traffic rule, platform-specific listener checks, and an external `nc -6` test command.
- Explains `succeeded`, `Connection refused`, and timeout results in place, and warns when a publicly exposed SOCKS5 proxy has no authentication.
- Keeps Agent `2.26.4` as the current target. Panel-only releases no longer force an unnecessary Agent upgrade or restart.
- Changes no database schema, proxy runtime, port lease, tunnel, forward, DNS, or certificate configuration. Roll back to `2.26.4` with `sudo /usr/local/sbin/flux-panel-manager rollback` if needed.

## 2.26.0 managed HTTPS routing and certificate operations

- Adds a dedicated HTTPS certificate view with domain, DNS account, route and ingress usage, expiry, issuance state, concise errors, and manual retry.
- Extends managed HTTPS entries from exact-host routing to host-and-path routing. One domain can send `/`, `/api`, and other path prefixes to different internal mappings; the longest matching path wins.
- Reuses one Let's Encrypt certificate and one DNS record across all path rules for the same domain, while keeping multiple domains on one public `443` listener through SNI.
- Preserves TLS passthrough behavior. Path routing is available only when the panel terminates HTTPS because encrypted passthrough traffic does not expose the HTTP path.

### Upgrade and rollback impact

- Adds `domain_route.path_prefix` with a default of `/` and replaces the old domain-only unique index with a domain-and-path unique index. Existing domain routes remain equivalent to a `/` rule.
- No Agent update, new container, or extra permanent listener is required. Managed HTTPS still requires Agent `2.17.0` or newer on the public entry node.
- Certificate keys remain encrypted at rest and are never returned by the certificate list API. To roll back, delete any additional same-domain path rules first, then run `sudo /usr/local/sbin/flux-panel-manager rollback`.

## 2.22.7 Aliyun carrier DNS no-op update handling

- Confirms the existing carrier record's line, address, and TTL when Aliyun returns `DomainRecordDuplicate` for an unchanged update.
- Treats the response as success only when the provider record already matches the requested state; real conflicts still remain visible.
- Changes no schema, Agent, tunnel, forward, port, or health-check policy. Roll back to `2.22.6` with `sudo /usr/local/sbin/flux-panel-manager rollback` if needed.

## 2.22.6 idempotent Aliyun carrier DNS sync

- Identifies existing Aliyun carrier records by `LineCode`, including records whose display line is localized.
- Recovers from `DomainRecordDuplicate` by discovering and updating the existing record instead of leaving the strategy in an error state.
- Serializes strategy saves with scheduled health checks so both paths cannot recreate the same carrier records concurrently.
- Changes no schema, Agent, tunnel, forward, port, or health-check policy. Roll back to `2.22.5` with `sudo /usr/local/sbin/flux-panel-manager rollback` if needed.

## 2.22.5 Aliyun DNS request encoding fix

- Sends the pre-encoded Aliyun RPC query as a `URI`, preventing Spring from encoding `%3A` into `%253A`.
- Fixes the remaining `InvalidTimeStamp.Format` response after fractional seconds were removed.
- Changes no schema, Agent, tunnel, forward, port, or health-check behavior. Roll back to `2.22.4` with `sudo /usr/local/sbin/flux-panel-manager rollback` if needed.

## 2.22.4 Aliyun DNS signature timestamp fix

- Formats Aliyun DNS API timestamps without fractional seconds, as required by the 2015-01-09 API.
- Fixes `InvalidTimeStamp.Format` when carrier-line records are created or updated.
- Changes no schema, Agent, tunnel, forward, port, or health-check behavior. Roll back to `2.22.3` with `sudo /usr/local/sbin/flux-panel-manager rollback` if needed.

## 2.22.3 Aliyun carrier DNS diagnostics

- Uses Aliyun DNS's 600-second minimum TTL when creating or updating carrier-line records.
- Preserves Aliyun's API error code and short message so credential, permission, signature, domain, and record-limit failures can be diagnosed from the panel.
- Changes no schema, Agent, tunnel, forward, port, or health-check behavior. Roll back to `2.22.2` with `sudo /usr/local/sbin/flux-panel-manager rollback` if needed.

## 2.22.2 DNS credential autofill protection

- Prevents browser password managers from treating DNS configuration fields as panel login username/password fields.
- Gives Cloudflare, DNSPod, and Aliyun credential inputs distinct field names and marks secrets as new credentials in both DNS settings surfaces.
- Adds no schema, Agent, port, container, or DNS behavior changes. Roll back to `2.22.1` with `sudo /usr/local/sbin/flux-panel-manager rollback` if needed.

## 2.22.1 unified DNS provider settings

- Adds DNSPod and Aliyun DNS credential management directly to **DNS 与域名**, alongside the existing Cloudflare account and Zone management.
- Opens the carrier-DNS credential dialog directly when **入口接入** has no compatible provider, instead of sending administrators to an unrelated default tab.
- Reuses the existing encrypted provider records and APIs, so credentials already added under Dynamic DNS appear automatically and are not duplicated.
- This is a frontend workflow correction. It adds no database columns, Agent workload, listening ports, containers, or DNS changes by itself.

### Upgrade and rollback impact

- Existing Cloudflare, DNSPod, Aliyun DNS, Dynamic DNS, and carrier-entry configurations are preserved.
- Back up before updating as usual. To return to `2.22.0`, run `sudo /usr/local/sbin/flux-panel-manager rollback`.

## 2.22.0 carrier-aware entry routing

- Adds an administrator-only **入口接入** page that publishes one business hostname through default, China Telecom, China Unicom, and China Mobile DNS lines.
- Reuses encrypted DNSPod or Aliyun DNS credentials from Dynamic DNS; Cloudflare authoritative DNS is intentionally excluded because it does not provide mainland-China carrier line records.
- Requires all selected forwards to expose the same public port, while enforcing distinct entry forwards and entry nodes for meaningful carrier routing.
- Adds bounded Agent-online and TCP-port health checks. An unhealthy carrier entry falls back to a healthy default or alternate entry, and returns only after the configured recovery confirmations.
- Records concise switch and recovery history without reading or storing client IP addresses. DNS changes affect new connections only; established TCP connections are not migrated.

### Upgrade and rollback impact

- Adds three independent tables: `smart_entry_group`, `smart_entry_route`, and `smart_entry_event`. Existing nodes, tunnels, forwards, port allocations, domains, users, and Agent configurations are not rewritten.
- No Agent update, new container, listening port, or permanent probe host is required. Health checks run from the panel at a minimum five-second interval and are bounded to configured entry ports.
- Records created by Flux are deleted when a strategy is removed. Pre-existing carrier records are restored to their original address and TTL instead of being deleted.
- Back up MySQL before updating. To return to `2.21.0`, run `sudo /usr/local/sbin/flux-panel-manager rollback`; the previous panel safely ignores the additive tables and Flux restores managed DNS records when a strategy is deleted before rollback.

## 2.21.0 server assets and dynamic DNS

- Adds an administrator-only server asset center for provider, region, hardware, IP/ASN, network line, traffic plan, purchase and expiry dates, tags, notes, and per-currency monthly cost totals.
- Adds configurable Telegram expiry reminders. Each configured reminder day is delivered once for that asset and expiry date.
- Adds dynamic DNS rules for Cloudflare, DNSPod, and Aliyun DNS with IPv4/IPv6 detection, configurable polling intervals, update history, alert-center incidents, and one-shot Telegram failure/recovery notices.
- Reuses Cloudflare accounts and zones already stored under DNS settings. Additional DNS credentials are encrypted at rest and are never returned by list APIs or sent to Agents.
- Adds Agent `PublicIpQuery`; the Agent returns only its detected public address, while the panel performs all DNS API updates.

### Upgrade and rollback impact

- Adds four independent tables and two Telegram preference columns. Existing nodes, tunnels, forwards, port allocations, users, private proxies, internal mappings, domains, and certificates are not rewritten.
- Server assets add no Agent workload. Dynamic DNS performs one short address lookup per enabled rule at its configured interval, with a minimum of 30 seconds and a default of 60 seconds.
- Dynamic DNS requires Agent `2.21.0` on the selected detection node. Existing panel features continue working with older Agents.
- Back up MySQL before updating. To return to the previous successful panel release, run `sudo /usr/local/sbin/flux-panel-manager rollback`. The previous panel ignores the additive tables; dynamic DNS stops running and the last successfully written DNS record remains at the provider.

## 2.19.1 private proxy compatibility fix

- Generates private-proxy service and admission identifiers before the first database insert, fixing creation on strict MySQL 5.7 and MySQL 8 installations.
- Keeps Agent `2.19.0` as the minimum version; this panel-only fix does not require another Agent restart.

## 2.19.0 private proxy, diagnostics, and panel rollback

- Adds authenticated SOCKS5 and HTTP private proxies on owned or shared nodes, with optional source CIDR allowlists and permanent or timed leases.
- Integrates proxy listeners into the global port ledger and user forward quotas. Proxy traffic is charged to the user and the relevant owned/shared node quota; exhausted quotas pause only the affected proxy.
- Keeps ports reserved when an offline Agent cannot remove a proxy, then retries cleanup after that node reconnects.
- Adds an administrator-only network toolbox for bounded Ping, TCP, DNS, and traceroute checks executed by a selected Agent without accepting arbitrary shell commands.
- Encrypts proxy passwords at rest and never returns them through list APIs.
- Adds `flux-panel-manager rollback`, which switches to the previous successful panel image and restores the current image automatically if the rollback target fails health checks.

### Upgrade and rollback impact

- Adds only the `private_proxy` table. Existing nodes, tunnels, forwards, internal mappings, domains, users, and port allocations are not rewritten.
- Existing features continue working with older Agents; private proxies and network diagnostics require Agent 2.19.0 on the selected node.
- No new panel container or persistent probe is added. Proxy traffic consumes resources on its selected node, while diagnostics run only when requested and have bounded time and output.
- Back up MySQL before updating. To return to the previous successful panel release, run `sudo /usr/local/sbin/flux-panel-manager rollback`. Version 2.18.0 safely ignores the additive table, but remove active private proxies before rollback so the older panel is not left unable to manage their Agent-side listeners.

## 2.18.0 fast Agent recovery

- Starts Linux Agents after the basic network stack is available instead of waiting for `network-online.target` to finish.
- Retries panel connections every 1-1.5 seconds during a bounded 30-second recovery window, then returns to a jittered 5-6 second interval.
- Reduces the WebSocket connection handshake timeout from 10 seconds to 3 seconds so an unreachable panel address does not block the next attempt.
- Uses unlimited service restart attempts with a one-second delay on systemd and non-blocking network dependencies on Alpine/OpenRC.
- Repairs legacy Flux-managed systemd and OpenRC service definitions automatically after an Agent upgrade, without overwriting unrelated custom services.

## 2.17.2 display fix

- Gives the full-chain topology canvas an explicit responsive height so percentage sizing resolves correctly in desktop and mobile flex layouts.
- Runs automatic framing after the graph nodes are rendered.
- Lowers the overview zoom floor for large installations so the complete graph remains reachable before the operator zooms into a chain.

## Managed HTTPS

- Adds Cloudflare DNS-01 certificate issuance and automatic renewal through the centralized DNS credential store.
- Creates DNS-only A/AAAA records and temporary ACME TXT records without requiring public port 80.
- Encrypts ACME account keys, private keys, and certificate chains at rest and deploys them through the encrypted Agent channel.
- Supports multiple exact SNI certificates on one public HTTPS listener and routes HTTP/1.1 requests to existing internal mappings.
- Reports certificate expiry, issuance failures, renewal failures, and deployment failures through the alert center and Telegram notifications.

## Full-chain topology

- Visualizes users, domains, public entries, forwards, tunnels, nodes, connectors, and internal targets in one interactive graph.
- Colors failed components and links from live Agent state and configuration health.
- Includes a focused abnormal view, automatic layout, zoom controls, minimap, and navigation to each resource page.
- Applies role-aware filtering so ordinary users only see their own business chains.

## Upgrade impact

- Creates one certificate table and adds nullable fields to existing domain routes; it does not rewrite nodes, tunnels, forwards, port allocations, or users.
- Existing features and TLS passthrough continue to work with older Agents. Managed HTTPS requires Agent 2.17.0 on the selected public entry node.
- Certificate maintenance uses bounded scheduled retries and does not add a new container or permanent probing process.
- Upgrade the standby entry first when using production failover because restarting an Agent can interrupt connections on that node.

## 2.20.0 Shadowsocks and VLESS+REALITY private proxies

- Adds one-click Shadowsocks services with AES-128-GCM, AES-256-GCM, or ChaCha20-IETF-Poly1305 and TCP+UDP on one public port.
- Adds one-click VLESS+REALITY services with Agent-generated UUID, X25519 keys, Short ID, and import URI.
- Downloads Xray `v26.3.27` on demand from the official XTLS release, verifies its published SHA-256, binds Xray to localhost, and restores configured instances after Agent restart.
- Keeps client secrets out of list APIs and decrypts connection details only for the owning user or administrator when requested.
- Extends the global port ledger, pause/resume/delete lifecycle, traffic accounting, quota enforcement, offline cleanup, and source CIDR allowlists to both protocols.

### Upgrade and rollback impact

- Adds one nullable encrypted client-configuration column to `private_proxy`; no existing node, tunnel, forward, mapping, domain, user, or port-allocation row is rewritten.
- Shadowsocks adds no new runtime. Each REALITY instance adds one Xray process, and the first instance on a node caches roughly 20-30 MB of runtime files.
- Existing services continue working on older Agents. VLESS+REALITY specifically requires Agent `2.20.0`.
- Back up MySQL before updating. A failed panel deployment can roll back directly to 2.19.1. After creating a Shadowsocks or VLESS+REALITY instance, delete it before rollback when possible, then run `sudo /usr/local/sbin/flux-panel-manager rollback`; the previous panel safely ignores the additive column but cannot manage a 2.20.0-only Agent runtime.

## 2.20.1 Mobile protocol selector layout

- Keeps the full Shadowsocks and VLESS+REALITY labels on desktop while using compact `SS` and `VLESS` labels on narrow screens.
- Removes horizontal overflow from the private-proxy creation dialog without changing the 2.20.0 Agent protocol or database format.

## 2.20.2 VLESS+REALITY schema compatibility

- Expands the existing `private_proxy.proxy_type` column from 12 to 32 characters so upgraded installations can store the `vless_reality` protocol identifier.
- The migration is additive and preserves all existing private proxy records. Startup only alters older columns that are shorter than 32 characters.
## 2.26.1 IPv6 直连验证修复

- 修正家庭 IPv6 直连创建时的公网验证逻辑。
- 当所选出口 VPS 没有 IPv6 路由时，不再误报家庭防火墙故障并撤销已启动的家庭监听。
- 这类状态会保留运行配置并标记为“公网验证未完成”；实际 IPv6 客户端、家庭路由器和系统防火墙仍需允许对应 TCP 端口。
- 建议公网 SOCKS5 代理启用用户名密码认证。
## 2.26.2 IPv6 验证节点能力检查

- IPv6 家庭代理创建前，先检查出口验证节点自身是否具备公网 IPv6。
- 出口节点没有 IPv6 时保留家庭监听并标记为“公网验证未完成”，不再依赖模糊的 TCP 探测错误判断。
- 出口节点有 IPv6但家庭端口不通时，仍严格回滚并提示检查路由器及系统防火墙。
## 2.26.3 家庭代理卡片修复

- 无认证家庭代理不再显示多余的数字 `0`。

## 2.26.4 Agent 升级与延迟清理修复

- 修正面板发布标签与 Agent 二进制内置版本不一致，导致在线升级和手动升级在版本校验阶段失败的问题。
- 面板版本、Agent 版本、下载标签和安装脚本统一为 `2.26.4`。
- 发布流水线会执行新构建的 Agent 并核对 `--agent-version`，版本不一致时阻止发布。
- 家庭代理删除后立即从页面和统计中移除；离线家庭接入端中的残留配置仍由后台任务在其恢复连接后清理。
- 本次变更不修改现有隧道、转发、端口分配或节点配置；Agent 升级会短暂重启对应节点服务。
