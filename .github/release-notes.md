## 2.77.1 A cleaner, more professional look

- Page headers are a bold title with its actions on the same line. The explanation that used to sit under each title now opens from a help icon beside it.
- 转发管理, 节点管理, 隧道管理 and 用户管理 show the item count beside the title and use full-size buttons with icons; the main action is a solid "新建" button.
- Menu items and page titles use short, consistent names: 仪表盘, 调度总览, 节点管理, 隧道管理, Docker 应用, 质量实验室, 带宽测试, 本机测速, 链路拓扑, 系统自检, 内网组网, IP 质量检测.
- Cards use larger type and more spacing, and grids show at most three cards per row (four on very wide screens).
- Long explanations inside pages (来源 IP 分流, 内网组网, 三网优化) are folded into a "使用说明" section that opens on demand.
- Release checks follow the new page names (2.77.0 was built but not published because of them).
- Panel-only update; Agents stay at 2.76.0. No database change. 2.76.0 remains available for rollback.

## 2.76.0 Custom exits and per-domain routing for private proxies (Agent 2.76.0)

- 出口管理 (formerly 家宽出口池) has two tabs: residential exits, and a new custom exits tab. Paste share links (vless://, vmess://, trojan://, ss://, hysteria2://, tuic://, anytls://, socks5://, http(s)://, several at once) or Clash proxies; the panel shows what it recognised before saving. Each exit can be self-tested from a node, which reports the exit IP, country and latency. Custom exits are available to every user for their own proxies.
- Private proxies get "出口与分流": a default exit (the node's own IP, a residential country, or a custom exit) and rules matched top to bottom by built-in group (ChatGPT/OpenAI, Claude, Gemini, Google, YouTube, Netflix, Disney+, Spotify, Telegram, X, Meta, TikTok, GitHub, Microsoft, Apple, Steam, Discord, common Chinese sites), domain suffix, keyword, full domain or IP range. Each rule sends traffic to the node's IP, a residential country, a custom exit, or rejects it. For example, only ChatGPT can go through a US residential exit while everything else leaves directly.
- On the node the proxy hands its traffic to a loopback sing-box router that sniffs the domain and picks the exit, so routing works the same for sing-box and Xray protocols. Traffic for an exit that is down is dropped rather than sent from the node's own address. The exit pool keeps the countries used by rules connected.
- Routing needs Agent 2.76.0 on the proxy's node and is available for VLESS, VMess, Trojan, Hysteria2, TUIC, AnyTLS, Shadowsocks 2022, ShadowTLS and VLESS REALITY/XHTTP. SOCKS5, HTTP, mixed and classic Shadowsocks proxies keep a single exit.
- Stat cards that open another page (dashboard, routing overview, residential countries) respond to clicks again.
- Includes 2.75.0 (tunnel link diagram and pre-create checks), whose build was interrupted on GitHub, and a fix for a timing-sensitive OpenWrt DNS smoke test.
- Database: `private_proxy` gains `exit_custom_id`, `route_rules`, `route_countries`, `route_exit_ports`, and a new `proxy_exit` table; added automatically on startup (`migrations/20261016_proxy_custom_exit_routing.sql` for manual upgrades). Upgrade Agents on nodes that should route traffic. 2.74.0 remains available for rollback.

## 2.75.0 Tunnel link diagram with checks before creating

- The tunnel form has a live link diagram on the right: user device → entry → relays → exit → internet. A moment after the nodes, hops or protocol change, the panel checks the path and colours each node and hop green, amber or red with the reason, so a broken hop shows before the tunnel is saved.
- Hops are tested on the real port: the next node opens a temporary listener for a few seconds on the port the next forward would get from its range, and the previous node connects to it. A timeout is reported as a security group, firewall or port mapping that does not allow the node's port range; a port taken by another program and a full port range are reported too. Both Agents need 2.44.1 or later; older ones still get online checks.
- When the check fails the create button turns into "仍然创建" rather than blocking, since some setups open the port afterwards.
- Tunnel cards show the same diagram in compact form, with the result of the last diagnosis on each hop; the diagnosis dialog shows the full diagram above the per-hop list.
- Diagnosis of a tunnel without any forward now probes the real hop port with a temporary listener instead of port 22. The existing result list is unchanged for the MCP tool and older clients.
- New endpoint `POST /api/v1/tunnel/precheck`. Panel-only update; Agents stay at 2.66.0. No database change. 2.74.0 remains available for rollback.

## 2.74.0 Consistent pages, new routing overview and a speedtest-style client speed test

- Page headers: every page now opens with a bold title and a one-line description, as on the dashboard. The small grey category line above titles and the divider under headers are gone; 转发管理, 节点管理, 隧道组建 and 用户管理 gain titles too.
- 本机测速 is redesigned after speedtest.net: a large start button, then an animated speedometer with a needle and a non-linear scale up to 10 Gbps, live ping, jitter, download and upload readouts, a live speed curve, and your network and the Cloudflare test location underneath. Settings move into a dialog; measurement logic is unchanged.
- 线路调度中心: module counts are stat cards, the configuration check moves to the top, and each module is one card combining its role, capability facts (Agent, relay traffic, DNS, UDP, user grants) and boundary, instead of the same modules listed twice.
- 三网优化: carrier entries are compact three-line tiles and strategies sit two or three per row, so each strategy card is about half as tall.
- 真实带宽测试, IP 质量与解锁检测 and UDP / QUIC 诊断 show tasks and nodes as standard resource cards with stat cards on top.
- 告警中心, 网络质量实验室, 全系统自检 and 网络诊断 drop their own grey boxes for the panel's cards and stat cards; empty states across the panel use the same dashed card.
- Panel-only update; Agents stay at 2.66.0. No database change. 2.73.0 remains available for rollback.

## 2.73.0 Dashboard in the NodePassDash layout

- The dashboard is rebuilt after NodePassDash: a title with a live clock and node, tunnel and forward counts; four status tiles (running, paused, error, offline nodes); a 24-hour traffic chart with gradient areas beside a node list; this week's traffic, today's traffic and quick actions; and a recent-activity table of open alerts and route switches. 异常与待处理, 转发流量排行 and 额度与到期风险 stay below in the same card style.
- Traffic is now recorded per direction. Charts show inbound and outbound separately; hours recorded before this update show as one grey "未区分方向" share until they age out.
- Hourly traffic is kept for eight days instead of 48 hours, which feeds the seven-day chart. The weekly chart fills in over the first week after upgrading.
- The running, paused and error tiles open 转发管理 filtered to that status, in either the card or table view; a chip in the page header clears the filter.
- Database: `statistics_flow` gains `in_flow`, `out_flow`, `total_in_flow`, `total_out_flow` and an index on `created_time`, added automatically on startup (`migrations/20261016_statistics_flow_direction.sql` for manual upgrades). Agents stay at 2.66.0. 2.72.0 remains available for rollback.

## 2.72.0 Unified cards, batch 3: resource center, DNS, assets, failover and smart entry

- 家庭设备, 动态解析, 端口资源 (port pools and user grants), 域名管理 (Cloudflare and carrier DNS accounts), AWS 账号 and 服务器资产 now show each item as a standard card with address blocks, fact grid, status line and action buttons. Their summary figures use the dashboard stat cards.
- 入口容灾, 三网优化 and 私有组网 keep their detailed group panels, now in the same card frame, page width and stat cards as the rest of the panel.
- Panel-only update; Agents stay at 2.66.0. No database change. 2.71.0 remains available for rollback.

## 2.71.0 Unified cards, batch 2: exit pool, home access, service publishing, Docker and authorized entry

- 家宽出口池: country tiles use the dashboard stat cards and open the country list; exit groups are standard cards with the candidate exits, their switches and block buttons inside.
- 家庭设备访问: each route is a card with its public address and egress blocks, access and health facts, the smart-NAT status with path history, and copy, detect and delete buttons.
- 服务发布: services, domain routes, certificates and connectors are cards. Domain routes stay grouped by domain, and the duplicate list of all routes below the groups is gone.
- Docker 应用中心: nodes and apps are cards. App cards show access and container blocks, node and update time, the latest error or backup, and upgrade, backup, rollback and delete buttons; start/stop, manual command and events sit in the card header.
- 授权入口: grants are cards with port and traffic quota, reset day, expiry, a usage bar and each landing port with its own edit and delete buttons; add landing, edit, pause/resume and revoke are the card's buttons.
- Panel-only update; Agents stay at 2.66.0. No database change. 2.70.0 remains available for rollback.

## 2.70.0 Unified cards, batch 1: nftables, source IP, multi-line and private proxies

- A shared resource card, modelled on the forward card, now shows list items across the panel: title and subtitle, an on/off switch and status chip, boxed address blocks, a two-column fact grid, a status line, traffic chips and edit/action/delete buttons.
- nftables 端口转发: each rule is a card with its public entry and target, listen address, source limits, node and Agent, the latest error or rule state with the event log, and a pause switch; summary figures use the dashboard stat cards.
- 来源 IP 分流: groups are cards with the shared entry, up to four rules and their backends, default line, Agent and last sync; they move above the explanations. The carrier and ASN databases are cards too.
- 多线路并发调度: groups are cards with the entry, every line with health, weight, bandwidth, latency and loss, capacity and weighting facts, and the last error or schedule state; weight recalculation and line repair sit in the card header.
- 私人代理: proxies stay grouped by node, each as a card with its address and residential exit, access or quota facts, expiry, the self-test result with a re-test button, exit switching, and a pause switch.
- Panel-only update; Agents stay at 2.66.0. No database change. 2.69.1 remains available for rollback. More pages follow.

## 2.69.1 Forwards and nodes open as cards again

- 转发管理 and 添加节点 open in the card view again by default. The table view stays available from the switch in the page header and is remembered per browser once chosen.
- Panel-only update; Agents stay at 2.66.0. No database change. 2.69.0 remains available for rollback.

## 2.69.0 New look, part 3: consistent cards across pages

- Cards on the tunnel, forward, node, user, profile and settings pages drop their grey borders and heavy shadows for NodePassDash's card look: a soft shadow in light mode and a thin border in dark mode.
- Edit, diagnose and delete buttons on tunnel and forward cards shrink to fit narrow columns instead of making the card scroll sideways.
- The user table header and the settings page use the theme's own colours, and the profile page no longer repeats the footer.
- Panel-only update; Agents stay at 2.66.0. No database change. 2.68.0 remains available for rollback.

## 2.68.0 New look, part 2: dashboard stat cards and list tables

- Dashboard stat cards follow NodePassDash: a soft gradient in the card's colour, the value in that colour, a rounded icon tile and a slight lift on hover. Node, tunnel and forward cards open their pages when clicked, and their colour turns to warning or danger when something needs attention. Cards can still be reordered by their handle.
- Forwards and nodes get a table view in NodePassDash's style, now the default; a table/card switch in the page header brings back the cards, remembered per browser.
  - Forward table: status, name with tunnel and owner, entry and landing addresses, protocol and target strategy, upload and download, and icon actions (start/stop, edit, diagnose, failover history, delete). A search box and status filters (all, running, stopped, abnormal) sit above it, with paging of 10 to 100 rows.
  - Node table: online state, name with owner badge, server and entry address, port range, Agent version, CPU and memory bars, live speed, total traffic, and icon actions (terminal, service discovery, upgrade or install command, edit, delete).
- Panel-only update; Agents stay at 2.66.0. No database change. 2.67.0 remains available for rollback.

## 2.67.0 New look, part 1: NodePass-style theme, sidebar and login

- The panel adopts the visual system of NodePassDash: a blue primary colour, the Inter typeface (bundled with the panel, no external font requests), plain light and dark backgrounds and thin scrollbars.
- New sidebar: frosted background, rounded menu items with the current page shown in reverse colour, group headings kept, and a collapse button that shrinks it to an icon rail with tooltips (remembered per browser). Appearance, account and logout moved from the top bar to the bottom of the sidebar; the desktop top bar is gone and phones keep a slim header.
- Appearance can be set to follow the system, light or dark (previously always the system), remembered per browser.
- Pages fade in when opened, and a footer shows the version. Page width and spacing follow NodePassDash.
- The login page uses NodePassDash's card, entrance animation, field icons and a show-password toggle.
- Panel-only update; Agents stay at 2.66.0. No database change. 2.66.0 remains available for rollback. Page contents (cards, tables, dashboard) are restyled in the next release.
- Design adapted from NodePassDash under its BSD-3-Clause licence; see THIRD_PARTY_NOTICES.md.

## 2.66.0 Connection pool, API tokens, real-time events and MCP

- TLS tunnels can keep a pool of 1-8 pre-handshaken connections to the exit (隧道 → 连接池预热). A new client connection takes one and skips the TCP and TLS round trips. The pool only stays warm while the tunnel carried traffic in the last minute, replaces entries before the exit's 15-second idle limit, and lets them expire when the tunnel goes quiet. It applies to the first hop of TLS tunnels; multiplexed protocols (MTLS, MWSS) already reuse one connection.
- Personal API tokens (个人中心 → API 令牌): send "Authorization: Bearer cn_..." or "X-API-Key: cn_..." to call any panel endpoint as the token's owner, with that account's permissions. Tokens are shown once, stored as SHA-256 hashes, can expire after 30/90/365 days or never, and can be revoked. A token cannot create tokens or change the account password.
- Real-time events: GET /api/v1/events streams Server-Sent Events for node online/offline, forward created/updated/deleted/paused/resumed and route switches. Administrators receive all events; other users only events about their own forwards.
- MCP endpoint at /api/v1/mcp (Streamable HTTP) for AI assistants, authenticated with an API token. Tools: list nodes, tunnels and forwards; diagnose a forward or a tunnel; pause or resume a forward; read a forward's route switch history. Every tool runs as the token's owner.
- Update the panel and the entry Agents to 2.66.0 for the connection pool; tokens, events and MCP are panel-only. The database gains the api_token table and tunnel.pool_size (default 0, off). 2.65.0 remains available for rollback.
- Verified by backend tests (404) including token authentication, expiry, revocation, the request filter, event delivery rules and the MCP protocol, Agent tests with the race detector for the pool (served from the pool, refills, stops when unused, replaces stale entries) and sequential plus concurrent connections through a pooled relay/TLS tunnel, and frontend tests and build.

## 2.65.0 Lowest-latency targets, forward advanced options and QUIC tunnels

- New target strategy 最低延迟: for a forward with several landing addresses, the panel's per-minute health check puts the fastest healthy landing first and the exit uses it. The current landing stays first unless another one is faster by more than 15 ms, so small fluctuations do not rebuild the exit service. Unhealthy landings are still removed and return automatically when they recover.
- New 高级选项 on each forward, applied to the TCP entry port:
  - PROXY protocol v1 or v2: the entry writes the client's real address at the start of the connection, so it reaches the landing through any tunnel. The landing service must accept PROXY protocol.
  - Block SOCKS, HTTP and/or TLS: connections that open with a blocked protocol are closed before any client data reaches the landing. Protocols in which the server speaks first (SSH, SMTP, MySQL) are not delayed.
  - Concurrent connection limit: new connections beyond the limit are closed.
  These options are kept on every rebuild of the entry service, including edits, route switches, load balancing and recovery.
- Tunnels can use the QUIC transport. QUIC runs over UDP; allow the exit's tunnel port for UDP in the cloud firewall.
- Update the panel and the entry Agents to 2.65.0 for the advanced options; QUIC tunnels need 2.65.0 on both ends. The latency strategy is panel-only. The database gains three columns on forward (proxy_protocol, block_protocols, max_connections) with defaults that keep existing forwards unchanged. 2.64.5 remains available for rollback.
- Verified by backend tests (395), frontend tests and build, and Agent tests with the race detector, including real TCP services for SOCKS/HTTP blocking, server-first banners, PROXY v1 headers and the connection limit, and a TCP forward through a QUIC relay tunnel. Not yet run against production nodes.

## 2.64.5 CNIX shared tunnel card matches the other tunnel cards

- The CNIX shared tunnel card no longer shows a large blue information box. It now has the same layout and height as the other tunnel cards: the subtitle reads CNIX 共享通道已验证 or 待验证, and the hop line between entry and exit shows CNIX · <accelerated entry> in the same gray as 公网 · <address>.
- Hovering the subtitle or the hop line shows the full details: TCP / relay+TLS, the accelerated entry and the exit mapping target (exit IP and shared service port).
- A tunnel whose CNIX entry has not been filled in shows CNIX · 待补录加速入口 in orange; a pending tunnel with an entry shows its last error in one orange line. A verified tunnel shows no extra lines.
- Panel-only update: Agents stay at 2.64.3. No database change. 2.64.4 remains available for rollback.
- Verified in a local preview with sample tunnels and by frontend type check, tests and production build.

## 2.64.4 Fix nftables rules failing on nodes with several open-source rules

- Fixes nftables port forwarding on a node with two or more rules that allow any source. The panel sent the second and later rules' empty source allowlist as a JSON reference object instead of an empty list, so the Agent rejected the whole node update with "cannot unmarshal object into Go struct field nftForwardRule.rules.sourceCidrs". New rules on such a node showed 异常 with "Agent 返回的实际规则不完整", and deleting a rule on that node failed and kept its port reserved.
- Messages to Agents are now always written as plain JSON without fastjson reference objects, so a value repeated within one command can no longer break any other Agent command either.
- After updating, the panel re-syncs affected nodes on its own within about a minute; rules pending deletion are then removed and their ports released. Clicking a rule's sync or delete again also works.
- Panel-only update: Agents stay at 2.64.3, no Agent upgrade is needed. No database change. 2.64.3 remains available for rollback.
- Verified by backend tests (392), including a new test that a message with three rules sharing an empty allowlist is written without reference objects.

## 2.64.3 CNIX shared tunnels survive an offline exit and customer renewals

- An offline exit node no longer blocks removing shared businesses, pausing them, removing a user's tunnel permission, deleting a user or deleting a shared tunnel. The database change applies at once; the exit carries no traffic while offline, and the periodic sync (about 30 seconds) pushes the current grants when it is back. A shared tunnel deleted while its exit is offline keeps its exit port reserved until the exit returns and the shared listener is removed, or until the exit node is deleted from the panel. An exit that is online but rejects a change still fails the operation.
- Renewing a customer or changing their expiry no longer disconnects their live shared-tunnel sessions. The exit Agent closes sessions at the new expiry, and immediately if the expiry is moved into the past. Changed credentials or targets still close the affected sessions.
- A business whose own landing is unreachable no longer marks the whole shared tunnel as pending with that business's error. The shared channel state changes to verified when a business check succeeds; failures stay on the business's health status. Use the tunnel card's 诊断 to check the shared channel itself.
- Update the panel and both CNIX Agents (domestic ingress and exit) to 2.64.3; the renewal fix runs on the exit Agent. Ordinary Agents also report 2.64.3. No database change. 2.64.2 remains available for rollback.
- Verified by backend tests (391), Go tests with the race detector for the relay, socket and limiter packages, and an Agent build. New tests cover deleting businesses, users and the tunnel with the exit offline followed by automatic cleanup, renewal past the old expiry, and a failing business probe. Not verified against a real CNIX mapping.

## 2.64.2 One-click Clash and sing-box import for private proxies

- The connection dialog of each private proxy now offers 一键导入 for Clash / mihomo and sing-box. Clicking it opens the installed client (Clash Verge, FlClash, Clash Meta for Android, Stash, SFA/SFI/SFM) and adds a subscription that contains only this proxy. Copy-link and QR code are offered for clients that do not react to the button.
- The raw Clash configuration text is folded away and shown only on request, for people editing a config file by hand.
- The 订阅链接 dialog for all proxies also gets 一键导入 buttons for Clash and sing-box.
- The subscription link accepts an optional id to return a single proxy. It uses the owner's existing subscription token, so resetting the token also invalidates these links; an ordinary user can only import their own proxies, an administrator any proxy. The profile name in the client is the proxy name.
- Panel-only update: Agents stay at 2.64.1 and do not need upgrading. No database change. 2.64.1 remains available for rollback.
- Verified by type check, frontend tests and backend private-proxy tests. Not yet tried against a live Clash or sing-box client.

## 2.64.1 Diagnose the actual CNIX shared path

- Fixes the tunnel-card diagnostic for CNIX shared mode. It no longer tests the exit server's SSH port 22 or Google 443 and presents those results as CNIX performance. The dialog and path card display the configured CNIX external address and actual shared exit service port.
- Adds a standalone channel check that works before any business forwards exist. The domestic Agent connects through the real CNIX endpoint, validates the dedicated exit TLS identity, and requires relay to reject an unissued credential. The exit Agent separately checks its local shared listener. The check neither grants a destination nor creates a temporary forward.
- Reports channel readiness, pending mapping, offline nodes, incomplete results and outdated Agents explicitly. The displayed TLS/relay handshake duration is not labeled as business latency, packet loss or throughput. Business destination and client-protocol validation remains under forward diagnostics.
- Update the panel and domestic ingress Agent to 2.64.1 to use the new channel check. The existing 2.64.0 shared exit remains compatible; all normal Agent builds also report 2.64.1. Existing mappings, business targets, credentials and the old 17148 line are not migrated by this update.
- Adds real TLS/relay response tests for protected/open relays, bad identities and unavailable endpoints, backend routing/no-business/old-Agent regression coverage, and frontend checks preventing SSH/Google and fabricated total-latency output. 2.64.0 remains available for rollback.

## 2.64.0 CNIX shared relay tunnels

- Adds CNIX shared mode under tunnel creation. Create one relay/TLS exit listener and one dedicated CNIX mapping, then add A/B/C forwards with independent domestic ports and destination addresses. The external CNIX port and the exit listener port can differ. First release supports two nodes and TCP business traffic.
- Each business carries its own authenticated destination over the existing GOST relay protocol. Target allowlists, expiry and revocation are scoped per business; private-network and metadata destinations are blocked after exit-side DNS resolution. TLS uses an explicit per-tunnel trust certificate, and missing CNIX paths fail closed instead of connecting directly.
- Business pause/delete, quota enforcement and authorized rate limits retain independent ingress accounting and leave other businesses running. A paused business receives its relay grant immediately when resumed. Agent configuration is saved atomically with private permissions and restores after restart.
- Panel and both Agents must be updated to 2.64.0. Old Agents are detected by a capability check. Existing ordinary tunnels, fixed forwards and the existing CNIX 17148 mapping are not automatically migrated or modified. Create a new dedicated CNIX mapping and allow the new exit port in cloud firewalls.
- Adds two idempotent CNIX tables. Database credentials and authentication seeds use the existing JWT_SECRET encryption key. The previous 2.63.0 release and rollback/pre-cnix-shared-20261015 tag remain available. Before downgrading, migrate/delete shared businesses and delete their shared tunnels; the panel manager blocks ordinary rollback while shared resources still exist.
- Validation includes three distinct targets through a transparent X-to-Y TCP mapping with X != Y, simultaneous bidirectional payloads and independent counters, B pause/delete while A/C continue, separate ingress/exit process restarts, rate isolation, denied credentials/targets/commands, TLS trust and configuration-write rollback. Release checks also run the CNIX schema on MySQL 5.7 and 8.0. These controlled tests do not verify an actual provider CNIX mapping, its cloud firewall or live customer protocol authentication.

## 2.63.0 Pick a working, fast REALITY camouflage site automatically

- REALITY camouflage sites and ShadowTLS handshake sites can now be chosen automatically, and this is the default. When the proxy is created, the node checks a list of large sites and picks the one that works with the fastest TLS handshake. A site works when the node reaches its port 443 and it offers TLS 1.3 with X25519 and HTTP/2 with a certificate for its own name. Every REALITY connection repeats the handshake with the site, so a slow site slows every connection.
- The list leaves out sites blocked in mainland China such as Google and YouTube. The camouflage name travels in the device's connection, so a blocked name gets the connection dropped before it reaches the node. The old Google preset is removed; this is the most likely reason a link stopped working until the site was changed.
- A site typed in by hand is checked before the proxy is created and refused with the reason (for example no TLS 1.3, name does not resolve on the node, certificate for another name).
- A 检测 button shows, for every candidate, whether it works from the chosen node and its handshake time; clicking one selects it.
- The camouflage site of an existing REALITY proxy can be changed under 修改. The UUID and keys are kept; clients only need the new SNI, which subscriptions pick up on their next update.
- Agent 2.63.0 is required for checking and changing sites. On older Agents the automatic choice falls back to the previous default without a check. No database change.
- Verified on a test panel with a Linux VM node in Hong Kong: handshake times ranged from 14 ms (www.apple.com) to over 500 ms (www.microsoft.com); automatic choice picked the fastest site for REALITY, XHTTP and ShadowTLS and the self-test passed; www.baidu.com and www.qq.com were refused for lacking TLS 1.3; an unknown name was refused as unresolvable; changing an existing proxy's site kept its UUID and key and the self-test passed. Whether a site is blocked from the user's own network cannot be measured from the node, which is why the candidate list excludes known blocked sites.
- 2.62.0 remains available for rollback.

## 2.62.0 More private proxy protocols, self-test, editing and subscriptions

- Six new protocols, carried by the node's sing-box runtime unless noted: Shadowsocks 2022 (key generated by the panel), VLESS + XHTTP + REALITY (xray), VLESS and VMess over WebSocket, gRPC, HTTPUpgrade or plain TCP with optional TLS (usable behind a CDN such as Cloudflare), AnyTLS, ShadowTLS v3 wrapping Shadowsocks 2022, and SOCKS5+HTTP on one port (gost). Hysteria2 can enable salamander obfuscation. All of them can use a residential exit.
- Self-test. The node runs a client for the proxy for a moment and fetches a page through it, reporting whether it works, the exit IP and the time taken. It runs after creating or changing a proxy, on demand, and every 10 minutes for active proxies except WireGuard (testing a WireGuard key briefly takes over its endpoint and would interrupt a device using it). After two failed checks in a row the panel re-applies the saved settings on the node, adding back anything missing; if that changes keys or certificates the proxy says so. Proxies that keep failing appear on the monitoring page and in Telegram alerts. The test runs on the node itself, so it does not prove the port is open in a cloud firewall.
- Editing. Name, connect address (a domain pointing at the node), listen port and source allowlist can be changed, self-created proxies can be renewed, and passwords or keys can be regenerated. Changes that rebuild the proxy restore the previous settings if they fail.
- Subscriptions. Each user gets a subscription link with all active proxies in three formats: base64 share links, Clash/mihomo and sing-box, chosen by the client's User-Agent or `format=`. The link can be reset. Connection details and subscriptions have QR codes.
- Certificate pinning. Nodes use self-signed certificates. Clash and sing-box profiles and Hysteria2 links now carry the certificate fingerprint so clients accept only that certificate. Other share links still skip verification, and the panel says so.
- Source allowlists now work for the sing-box protocols too (not WireGuard). Loopback stays allowed so the node can test itself.
- Traffic history per hour, shown as a chart over 24 hours, 7 days or 30 days, plus the last time a proxy was used. Self-created proxies on sing-box runtimes are now counted, including toward node quotas; they were not counted before. The first reading after upgrading only records a starting point.
- Fixes: TUIC did not announce the h3 ALPN, so Clash/mihomo clients could not connect (existing TUIC proxies pick this up when the Agent restarts after upgrading); upload and download were swapped in the proxy list; residential exit alerts were never sent to Telegram because no repeat limit applied to them.
- Agent 2.62.0 is required for the new protocols, self-test, editing port/allowlist, resetting keys and sing-box allowlists. Existing proxies keep working on older Agents. The Agent release uses the panel version number because a release named 2.59.0 already exists.
- Database: adds eight nullable or defaulted columns to `private_proxy` and the `private_proxy_traffic` and `private_proxy_subscription` tables.
- Verified on a test panel with a Linux VM node: all 20 test proxies covering every protocol and transport passed the self-test; the Clash profile connected through every proxy with mihomo 1.19.32 and the sing-box profile with sing-box 1.13.15 (VLESS + XHTTP is in neither format and was tested through xray); allowlists refused a non-listed address; blocking a port made the check fail; deleting a runtime on the node was repaired by the scheduled check; port changes, resets and renewals were exercised. Base64 share links were checked for format but not imported into Shadowrocket or v2rayN.
- Rollback: delete proxies that use the new protocols first, then roll back to 2.61.0.

## 2.61.0 Daily backups, a forward fix and exit risk controls

- Fixes tunnel forwards rebuilding their out-node services every minute. The forward health check pushed the relay service of every tunnel route to its out node on every run, about once a minute, even when nothing had changed. It now pushes only when a route's set of healthy targets changes. Reported from node logs showing the same `UpdateService` arriving every minute.
- Daily database backups. The panel manager gains `flux-panel-manager backup`, and installing or updating the panel schedules it daily around 04:30. Each backup is a gzip of `mysqldump` plus a copy of `flux-panel.env` in `/var/backups/flux-panel`; incomplete exports are discarded and the newest 14 are kept. `FLUX_PANEL_BACKUP_REMOTE` copies each backup to another host with scp; `FLUX_PANEL_BACKUP_KEEP`, `FLUX_PANEL_BACKUP_DIR` and `FLUX_PANEL_BACKUP_DISABLED` can be set in the environment or in `flux-panel.env`. The timer appears after the panel is updated to this release.
- Purity now has three levels once risk scores exist: 干净 (fraud score under 30, abuse score under 25 and not flagged as a proxy), 一般, and 风险高 (fraud 75 or more, or abuse 50 or more). Previously a fraud score of 65 was shown as clean.
- Low-risk-only exits. An option on the exit pool page restricts automatic selection, failover and manual switches to exits whose IPQualityScore fraud score is known and at or below a chosen limit. Exits in use that exceed it are replaced at once. New exits without a score are checked once when they connect. It requires an IPQualityScore key and is refused without one. Country tiles show how many candidates meet the limit.
- 全部检测 in a country's candidate list checks every home candidate without a score in the background, one every 1.5 seconds, up to 60 at a time.
- Customers can switch the exit of their own proxies. The 换一个出口 button and a 切换链接 button that copies the remote switch link now appear for the owner of a proxy with a residential exit, not only for administrators. Creating proxies with a residential exit stays with administrators, including when granting a proxy to a customer.
- Each exit group on the pool page shows the accumulated traffic of the proxies using it. All traffic of a proxy with a residential exit goes through that exit.
- Panel-only change. Agents stay at 2.58.2. Database: adds the `exit_pool_setting` table.
- The forward fix and the purity and low-risk rules are covered by unit tests. The backup command was tested with a mocked Docker for rotation, incomplete exports, permissions and remote copy, not yet on a live panel host. Low-risk selection was checked on a test panel with simulated scores; real IPQualityScore lookups were not exercised.
- 2.60.1 remains available for rollback.

## 2.60.1 Faster exit switching that prefers low latency

- Each exit in use now keeps two standbys instead of one. A manual switch therefore still leaves a standby to switch to next, and there is a choice to rank. When standbys are missing, two more candidates are tried at once.
- 换一个出口, the remote link and the node's own failover go to the ready standby with the lowest measured latency. Latency is the time for a connection from the node through the exit to a public address, so it reflects both the node-to-exit path and the exit's own line.
- New candidates are tried in a better order: exits that connected from this node before come first, lowest latency first, then untried ones.
- While exits are being found or standbys replenished, the panel re-checks every two seconds instead of five.
- Agent 2.58.2 measures an exit's latency as soon as it connects, instead of at the first periodic check ten seconds later, and gives up on an unreachable candidate after eight seconds instead of ten. Panel 2.60.1 works with Agent 2.58.1; a new standby's latency is then unknown for its first ten seconds and it ranks last during that time.
- One more OpenVPN connection per exit in use. No database change.
- Verified in a Linux VM against live exits: with two standbys ready, a switch went to the lower-latency one and completed in under a tenth of a second, and a second switch followed without waiting. How quickly standbys are replaced depends on how many candidates are reachable from the node.
- Agents move to 2.58.2. 2.60.0 remains available for rollback.

## 2.60.0 Browse every candidate IP of a country with a purity rating

- On the 家宽出口池 page, clicking a country now opens the full list of that country's current candidates: address, provider, purity, risk scores, how many people share it and how long it has been online. Addresses that are not home broadband (data centre, mobile, VPN Gate's own servers, wrong country) are listed too, greyed out, so the gap between the list total and the usable count is visible.
- Purity is a plain reading of the available signals: 较干净 when the address is home broadband and not flagged as a proxy by the IP database; 已被标记 when it is flagged; 干净 or 风险高 once risk scores exist. The list is ordered with unflagged, less shared addresses first.
- 深度检测 queries IPQualityScore and AbuseIPDB for one address, using the keys already stored on the IP 质量检测 page, and keeps the scores. It runs only when clicked because each query uses provider quota, and only for addresses in the exit pool. Without a stored key it explains where to add one.
- A candidate can be chosen from this list directly when an exit of that country is in use; with several nodes using the country there is one button per node. The existing 指定出口 dialog also shows the scores.
- Panel-only change. Agents stay at 2.58.1. Database: adds four nullable columns to `exit_ip_info`.
- The list, ordering, the no-key message and the restriction to pool addresses were checked on a test panel with live data. The scoring path with a real provider key was not exercised here; it reuses the lookups the IP 质量检测 page already performs.
- 2.59.0 remains available for rollback.

## 2.59.0 Switch, choose and remotely change a residential exit

- Switch on demand. Proxy rows and the 家宽出口池 page have a 换一个出口 button that moves to the standby exit immediately. It is available only while a standby is ready, so a manual switch never leaves the proxy without an exit. The exit that was left is kept out of rotation for ten minutes so the next automatic choice does not go straight back to it, but it is reused at once if no other exit is left. Switches are limited to one per ten seconds per exit.
- Choose an exit. On the pool page, a standby exit can be selected directly, and 指定出口 lists every current home candidate of that country. A chosen candidate is connected and checked first; traffic moves to it only after its real exit IP is confirmed as a home address in that country. If it cannot connect, is rejected, or fails later, the choice is cleared and automatic selection resumes. The list shows a candidate's entry address; the exit IP is known only after it connects.
- Remote switch link. Each proxy with a residential exit can show a link that switches to the standby exit when opened and replies with the new exit IP as plain text (`&format=json` for JSON). No login is needed, so it works as a browser bookmark or a phone shortcut. The link's token can only trigger this one action and can be reset, which invalidates the old link. Tokens are not included in proxy lists.
- Panel-only change. Agents stay at 2.58.1. Database: adds `exit_group.preferred_candidate_id` and `private_proxy.exit_switch_token`, both nullable.
- Verified in a Linux VM against live exits: the device-visible IP after a button switch and after opening the link, the ten-second limit, an invalid and a reset token, choosing a candidate that works, choosing one that cannot connect, and recovery when the exit switched to fails with no standby.
- 2.58.1 remains available for rollback.

## 2.58.1 Residential exits resolve host names through the exit

- Fixes proxies with a residential exit failing on nodes whose own DNS returns addresses that only work from the node's IP, such as regional DNS unlocking services. The Agent resolved destination names with the node's resolver and then connected through the exit, so the connection went to an address the exit could not reach and timed out. Reported on a node where `www.google.com` resolved to such a relay.
- Names requested through an exit are now resolved with DNS queries that leave through the same exit (to 1.1.1.1, then 8.8.8.8), cached for 60 seconds per exit. The node's resolver and hosts file are no longer consulted for this traffic, and answers now reflect the exit's location. A name that cannot be resolved through the exit fails; there is no fallback to the node's resolver.
- This replaces the limit stated in 2.57.0 that host names are resolved by the node. Destinations given as IPv6 addresses are still not carried.
- Applies to every protocol that supports exits. Agent-only change: upgrade the Agent on nodes that use exits to 2.58.1. The panel is rebuilt with the new version number only; no database change.
- Verified in a Linux VM by making the node resolve a test site to an unreachable address: the released 2.58.0 Agent failed with the same error as the report, and 2.58.1 reached the site through the exit for SOCKS5, VLESS + REALITY, Hysteria2 and TUIC.
- Agents move to 2.58.1. 2.58.0 remains available for rollback.

## 2.58.0 Residential exits for VLESS + REALITY, Trojan, Hysteria2 and TUIC

- The residential exit option introduced in 2.57.0 now also works for VLESS + REALITY, Trojan, Hysteria2 and TUIC private proxies. WireGuard remains unsupported.
- These protocols run in a separate program on the node (xray or sing-box), which previously always connected to destinations directly. With an exit selected, the panel creates a SOCKS5 service on the node that listens only on 127.0.0.1 and is bound to the exit group, and the runtime uses it as its only outbound for TCP and UDP. Because there is no direct outbound left, these proxies fail closed in the same way: without a usable exit their connections fail instead of leaving from the node's own address.
- Changing the exit of an existing proxy, including turning it on or off, keeps its keys, UUID and certificate. Devices continue to work with the configuration they already have. The runtime restarts, so open connections reconnect.
- Requirements. Agent 2.58.0 on the node for these four protocols. SOCKS5, HTTP and Shadowsocks proxies with an exit keep working on Agent 2.57.0.
- Database. One nullable column, `exit_route_port`, on `private_proxy`.
- Rollback. Panel 2.57.0 and 2.56.5 run on the upgraded database. Switch these proxies back to the node's own IP before rolling back the panel or the Agent; on an older Agent a proxy still bound to an exit refuses connections rather than using the node's address.
- Verified in a Linux VM against live exits with real clients for each protocol: the site-visible IP over TCP and UDP, enabling an exit on an existing VLESS proxy without changing the client configuration, returning to the node IP, changing country, loss of every exit, Agent restart, and rolling the Agent back to 2.56.0 and forward again.
- Agents move to 2.58.0. 2.57.0 remains available for rollback.

## 2.57.0 Residential exits by country for private proxies

- A SOCKS5, HTTP or Shadowsocks private proxy can now leave through a home-broadband IP of a chosen country instead of the node's own address. The address and port a device connects to stay the same. Candidates come from the public VPN Gate list; the panel selects, checks and replaces them without manual work.
- Selection. The panel reads the list every ten minutes while the feature is in use, keeps only entries whose address is in a home network of the listed country (data-centre, mobile and VPN Gate's own servers are excluded) and tells each node which ones to connect. A node keeps one exit in use and one on standby. After a node reports the IP an exit really leaves from, the panel checks that IP again; only approved IPs may carry traffic.
- Failover. The node moves to the standby by itself when the exit in use stops responding (0.1 s in testing) and the panel then supplies a new standby. With no exit left the panel tries six candidates in parallel and re-checks every five seconds.
- Fail closed. A proxy bound to an exit never falls back to the node's own address. While no exit is usable its connections fail and the panel shows why. This also holds if the node's Agent is rolled back to 2.56.0.
- Node safety. Configuration files from the list are never sent to a node: the panel extracts the address, port, protocol, cipher, digest and certificates, validates each, and the Agent writes its own OpenVPN configuration. The Agent does not execute addresses, routes or DNS settings pushed by an exit; it assigns the tunnel address itself and leaves the node's routing table and rules unchanged, so other forwards are unaffected.
- Interface. The create form has an exit option with the number of usable candidates per country. Proxy rows show the current exit IP, its provider, how long it has been stable and today's switch count. Existing proxies can change exit or return to the node's own IP. A new page, 家宽出口池, lists every exit with its state, failure reason and switch history and allows excluding one by hand. The alert centre gains the resource type 家宽出口, raised after five minutes without a usable exit or when a node cannot run exits. The tutorial has a new entry.
- Requirements. A Linux node with TUN support and Agent 2.57.0. OpenVPN 2.5 or newer is needed on that node and is installed through its package manager on first use. Only nodes that use the feature need the new Agent.
- Limits. Available countries depend on volunteers (mostly Japan and Korea). Exits are shared, vary in speed, and part of them are already flagged as proxies by IP databases. Changing exit interrupts connections for a few seconds and changes the IP. Host names are resolved by the node, not through the exit, and IPv6 destinations are not carried. Volunteers can see unencrypted traffic and keep connection logs.
- External services. The panel server contacts `www.vpngate.net` for the list and `ip-api.com` to classify addresses. The free ip-api.com endpoint is limited to non-commercial use and 45 requests per minute; set `EXIT_POOL_IP_LOOKUP_URL` to use another endpoint of the same format, and `EXIT_POOL_VPNGATE_URL` for a mirror of the list. Neither service is contacted unless a proxy uses a residential exit or an administrator opens the exit options or the exit pool page.
- Database. Adds five tables (`exit_candidate`, `exit_group`, `exit_member`, `exit_event`, `exit_ip_info`) and two columns with defaults on `private_proxy`. Nothing existing is altered.
- Rollback. Panel 2.56.5 runs on the upgraded database and ignores the additions. It does not manage exits, so switch proxies back to the node's own IP before rolling the panel back; otherwise their current exits keep working on the node until they fail and the proxies then stop. Agent 2.56.0 can be reinstalled on a node; proxies still bound to an exit will refuse connections there until they are switched back.
- Verified in a Linux VM against live VPN Gate servers, with strict reverse-path filtering enabled: automatic OpenVPN installation, exit selection and approval, the site-visible IP for SOCKS5 and for Shadowsocks over TCP and UDP, unchanged routing table and rules, failover, loss of every exit, changing country, returning to the node IP, Agent restart, Agent rollback and re-upgrade, panel rollback and re-upgrade, and a node that cannot run OpenVPN. The pipeline runs unit tests for the same rules; the live test is kept behind the `exitlive` build tag.
- Agents move to 2.57.0, the first Agent change since 2.56.0. 2.56.5 remains available for rollback.

## 2.56.5 Code cleanup

- Removes frontend modules nothing imported, an unreferenced design-token stylesheet, ten API helpers without callers, stale planning documents and eleven unused packages. `@heroui/tooltip` and `@internationalized/date`, which pages import but which were only installed transitively, are now declared.
- Removes the backend code generator, two unreferenced classes and the generator-only dependencies (`mybatis-plus-generator`, `freemarker`).
- Timestamp, byte-count and API-flag formatting used by 20 pages now comes from one shared module instead of per-page copies. Empty-value wording per page is unchanged. Two visible differences: the private-network page formats times like every other page, and the authorized-entry page shows whole bytes as "5 B" instead of "5.00 B".
- Thirteen startup schema initializers share one helper for adding missing columns and indexes. The generated statements are equivalent; two initializers whose tests pin the previous query shape are left as they were.
- No feature or database schema change. Agents remain at 2.56.0; 2.56.4 remains available for rollback.

## 2.56.4 Upgrade caching, public configuration and dashboard accuracy

- The panel entry document is now served with `Cache-Control: no-cache`. Previously it had no cache policy, so a browser could keep loading the previous release's page files after an upgrade and show mixed versions. An open tab that requests a page file removed by an upgrade now reloads once automatically, with a one-minute guard against reload loops.
- `/api/v1/config/get` needs no login and returned any stored setting by name, including the Agent communication address. It now returns only the site name and the two captcha settings used by the login page. The authenticated configuration list likewise gives non-administrators only those settings. Administrators are unaffected.
- Hourly traffic sampling no longer records an account's lifetime total as one hour of traffic when the account has no earlier sample. Counter resets still record usage since the reset.
- The 24-hour traffic figure and chart select samples by when they were recorded instead of taking the latest 24 rows, so hours missed during downtime stay empty. Administrators now get every account combined, matching the card's label; other users still see only their own traffic. Buckets carry their start time and the chart labels them in the browser's time zone.
- The unread alert count now counts only alerts that are still open. Recovered alerts no longer inflate the badge. Alert history and the per-alert read markers are unchanged.
- Samples already stored with an inflated first-hour value are not rewritten; they leave the 24-hour window within a day and are deleted after 48 hours as before.
- Adds tests for configuration visibility, stale page-file detection, hourly increments, 24-hour bucketing and the unread query. No database schema change. Agents remain at 2.56.0; 2.56.3 remains available for rollback.

## 2.56.3 OpenWrt local routing no longer offered

- New Smart Entry strategies are always public carrier DNS. The routing-mode selector is removed from the create form and from public strategies.
- Existing local OpenWrt strategies keep running unchanged. Their edit form still shows the selector so they can stay local or move to public DNS, and they can be deleted as before. Adding new routers from the strategy form or the router tab is removed; install, uninstall, repair and record removal for existing routers remain.
- The router tab is labelled "OpenWrt 路由器（旧）" and is hidden when there are no local strategies and no registered routers.
- The Smart Entry tutorial now describes the public DNS flow.
- Frontend-only release. Backend APIs, the database and installed router Agents are untouched, so the option can be restored by reverting this release. Agents remain at 2.56.0; 2.56.2 remains available for rollback.

## 2.56.2 Simpler Smart Entry page

- Strategy cards now show the address and port a client must enter, each with a copy button, instead of a small `domain:port` line.
- Carrier DNS verification moves onto the card. It runs automatically once a saved public strategy has been published, can be rerun with a labelled button, and lists what telecom, unicom, mobile and other networks resolve to. The previous diagnosis dialog remains available as the detail view. Results are samples through public DNS with carrier client subnets; they do not prove a customer's device or proxy session.
- Status wording is reduced to "正常", "生效中（约 1 分钟）" and "需要处理：<reason>". The separate "配置待生效" chip and the "服务商已同步 · 600s" route chip are replaced by "生效中" / "已生效".
- TTL is explained as its consequence ("切换网络后最长约 10 分钟生效") in the form and on the card, with the provider plan note. The stored TTL value and its validation are unchanged.
- Entry selectors only list forwards on the public port already chosen by another carrier. Backup order no longer repeats an entry shared by several carriers or the route's own entry.
- New strategies ask for the business domain first and select the DNS account and zone hosting it automatically when exactly one saved account matches. Switching a local strategy to public DNS does the same.
- Page copy describes public DNS first. The routing-mode selector moves into advanced settings for public strategies and stays at the top for local ones. Health probing offers 灵敏 / 标准 / 保守 presets; the six raw values remain under "自定义检测参数" and existing custom values are preserved.
- Fixes the zone, DNS account and default-entry selectors being cleared by clicking the selected item again.
- Frontend-only release. No backend logic, database schema, DNS publication behavior or saved request shape changes; Agents remain at 2.56.0. Release 2.56.1 remains available for rollback.

## 2.56.1 Public carrier DNS without an OpenWrt dependency

- Existing local Smart Entry strategies can switch to AliDNS or DNSPod public carrier DNS in place. Select the provider account and authoritative zone while retaining the business domain, record type, ports, entries, route IDs and traffic history.
- Public-mode saves remove this strategy from router bindings in the same database transaction, including stale selections from older clients. Other bound strategies are preserved. Offline Agents remove their local overrides when they reconnect; no Agent upgrade is needed for public DNS operation.
- Clears local DNS verification and ownership state on migration, schedules public record publication, and retains provider errors and retries. Saving is not reported as proof that public DNS or the customer's proxy connection works. Domain and record-type changes remain blocked, as do account changes for existing public strategies.
- New strategies default to public DNS for remote users. OpenWrt local DNS is explicitly labeled for devices using that router's resolver. The migration form unlocks the DNS account/zone selectors and explains asynchronous publication and router cleanup.
- Adds transactional migration, failed-unbinding rollback and identity-protection tests, plus an isolated MySQL/real-Agent lifecycle check for AliDNS-mode conversion and acknowledged local-rule removal. No production DNS credentials or live customer connections are used by these tests.
- Panel-only release; Agents remain at 2.56.0. The 2.56.0 release and the `codex/rollback-before-public-dns-2561` branch retain the previous code. No database schema migration is required. Reverting code does not undo AliDNS records or strategy settings; public mode is supported by the rollback version.

## 2.56.0 Verified OpenWrt DNS rule activation

- Fixes adding/removing business domains after dnsmasq was already running: a plain reload/SIGHUP does not reread conf-dir files. Domain rule changes now restart dnsmasq, while address-only changes flush caches without a restart. Unchanged working policies do not rewrite files or restart DNS.
- Uses stable UCI dnsmasq instance IDs (`uci -X`) and the actual generated port-53 configuration instead of assuming `@dnsmasq[0]` is a generated filename. Preserves other DNS rules and retains uninstall ownership metadata when repairing an old configuration directory.
- Verifies the real router resolver using a local nonce TXT probe through port 53 to the Agent, then checks each configured business A/AAAA answer against the selected entry. A saved policy revision or successful init script no longer counts as usable DNS.
- Router cards show verified DNS state, actual business answers and concrete errors. Strategy cards distinguish healthy server entries from verified local DNS. The administrator's repair action also checks TCP reachability to the configured entry from the router, without sending authentication or proxy traffic; periodic DNS checks do not repeat TCP probes. Automatic repair stops after three unsuccessful attempts. Failed DNS control operations attempt to restore the previous policy.
- Adds real dnsmasq integration coverage that reproduces the old reload failure, verifies restart repair, checks a second domain and removal, and confirms address changes do not restart DNS. The release workflow runs this test in addition to encrypted backend/Agent tests on MySQL 5.7 and 8.0.
- Synchronizes panel and Agent to `2.56.0`. Existing router DNS Agents must be updated once using their installation command; upgrading only the panel cannot replace an already installed router binary. No manual WAN or domain edits are required afterward. `2.55.0` remains available for rollback.
- Verification proves local DNS answers, not a VLESS/REALITY authenticated session or a client's use of router DNS. Proxy DNS interception is reported when it prevents verification; third-party proxy settings are not silently rewritten. Actual installation on a particular router still requires access to that router.

## 2.55.0 Unified Smart Entry and OpenWrt management

- Merges the OpenWrt DNS Agent management page into Smart Entry. The sidebar and mobile menu now expose one "三网优化" entry with "优化策略" and "OpenWrt 路由器" tabs.
- The router tab keeps installation, uninstall, cleanup, online status, synchronization errors and carrier detection in the same context as the strategies. Each router card lists its bound strategies and links directly to strategy editing.
- The old `/openwrt-dns` URL remains compatible and redirects to the unified router tab. The guide no longer presents OpenWrt as a separate product feature.
- Panel-only release. Panel version is `2.55.0`; Agent and Connector remain `2.54.0`. `2.54.0` remains the rollback baseline.

## 2.54.0 OpenWrt-first Smart Entry

- New Smart Entry strategies default to local OpenWrt DNS decisions. Choose or create a router directly in the strategy form; saving atomically saves the strategy and router bindings, without a second configuration step.
- Local mode accepts a complete business domain hosted with Cloudflare, Aliyun, DNSPod, or any other provider. It does not require provider API credentials and does not create or modify public DNS records. The existing public DNS mode remains available for Aliyun and DNSPod.
- Removes WAN mapping forms and detailed runtime counters from router management. Router cards retain connection, carrier, configuration application and error information. Saving strategy bindings clears previous explicit WAN mappings and uses automatic public egress identification; the panel continues updating carrier databases automatically.
- Reuses health checks, ordered backups and switch cooldown for local routes. Unknown/unhealthy routes are not returned; explicit no-backup choices stop local answers when the preferred entry fails. IPv4 and IPv6 use their own default egress, local DNS TTL is five seconds, and business traffic bypasses the DNS Agent.
- Existing public strategies keep their behavior. Converting one to local mode queues durable cleanup using its original provider identity, preserves forwarding and activity state, and switches local decisions without further public record writes. Router reassignment/deletion preserves other strategies and remains pending until an offline router reconnects.
- Adds transactional binding and conversion regression tests, and extends the isolated encrypted Agent/backend integration flow to create local strategies through HTTP without DNS credentials, verify automatic bindings and mapping removal, and clean up deleted strategies on MySQL 5.7 and 8.0.
- Panel, regular Agent, Connector and DNS Agent report `2.54.0`. Release `2.53.0` remains the rollback baseline. Before downgrading below 2.54.0, delete local strategies and wait for public DNS cleanup and router removal acknowledgments; the manager guards incompatible rollback.
- Clients must use the enrolled router's DNS and normally share its default egress. Remote clients, application DoH/VPN, transparent proxies and per-client/per-destination multi-WAN rules are not automatically classified by this router. Existing application DNS caches and established sessions still require refresh/reconnection. Actual target-router installation must be verified separately; release tests do not operate production routers or customer traffic.

## 2.53.0 OpenWrt DNS decision Agent

- Adds an administrator page for OpenWrt DNS Agents, with installer/uninstaller commands, explicit WAN device-to-carrier mappings, carrier database refresh, selected Smart Entry strategies, IPv4/IPv6 egress status, applied configuration revisions, and policy removal.
- Publishes a separate DNS-only executable for Linux amd64, arm64, ARMv7, MIPS, and MIPS little endian. It listens only on router loopback, receives encrypted policy updates over the existing outbound Connector WebSocket protocol, and never proxies application traffic or enables GOST forwarding commands.
- Recognizes IPv4 and IPv6 default egress separately. An explicit WAN mapping takes precedence; otherwise it matches the current IPv4 public IP or IPv6 route source against the panel's carrier CIDR database. New DNS answers use a five-second local TTL, and WAN changes reload dnsmasq's cache.
- Reuses Smart Entry health and fallback policies, preserves explicit "no fallback" choices, suppresses unmanaged AAAA and HTTPS/SVCB hints for managed business names, and forwards other DNS questions to the router's existing upstream resolvers.
- Saves policies locally so panel outages do not interrupt DNS decision processing. Policy removal remains pending until the Agent acknowledges it; uninstall removes only the Agent's own dnsmasq file and any owned confdir setting. The panel blocks ordinary rollback while local router policies remain active or removal is unacknowledged.
- Release checks cover DNS behavior, policy persistence, role/version authentication, five architecture builds, and real encrypted Agent/backend lifecycle tests on MySQL 5.7 and 8.0. Actual OpenWrt procd/dnsmasq behavior still requires installation verification on the target router; no production router, customer DNS record, or live customer traffic was modified by these tests.
- Clients must use the DNS service of the enrolled OpenWrt. A different router, DoH/DoT, VPN, transparent proxy, or per-client/per-destination multi-WAN routing may bypass or invalidate default-WAN identification. Existing application DNS caches and established TCP connections are not migrated.
- Panel, regular Agent, Connector, and the lightweight DNS Agent report `2.53.0`; `2.52.2` remains available as the rollback baseline. Remove and acknowledge router policies or uninstall the router Agent and delete its record before rolling back.

## 2.52.2 Smart entry reliability and carrier failover controls

- Preserves an existing carrier route ID, health state, DNS target, and connection counters when an administrator edits the strategy. Replacing the physical entry archives the old activity snapshot and starts a fresh telemetry baseline.
- Adds an ordered fallback list for every configured carrier. Unknown and unhealthy entries are never published as a fallback; switch cooldown and recovery stability reduce flapping while still evacuating failed entries immediately.
- Adds TCP, TLS, HTTP, and HTTPS health checks. HTTP checks use a validated relative path, require a 2xx response, do not follow redirects, and never probe an arbitrary host or URL. A successful socket probe is counted even when the application check fails.
- Makes DNS synchronization transactional at the configuration level, records provider errors for retry, detects external DNS replacement before cleanup, and keeps deletion in a visible cleanup state until managed records are safely released.
- Blocks editing or deleting a node, tunnel, forward, or user while a live or retired entry reference still exists. DNS identity changes require creating a new strategy so the old record can be retired safely.
- Shows all four carrier cards, inherited default routes, current DNS target, fallback order, last operation reason, cleanup errors, and archived entry activity. DNS diagnosis distinguishes provider records, ECS samples, and an independent public resolver reference.
- Adds runtime lifecycle coverage for concurrent strategy creation, edits, entry replacement, dependency guards, history preservation, and asynchronous cleanup. The release smoke test runs this flow only against its isolated MySQL and backend containers.
- Panel-only release; Agent and Connector remain `2.52.0`. No production nodes, customer DNS records, or live customer connections were used by tests.
- `2.52.1` remains the rollback baseline. Ordinary rollback checks both entry migration jobs and Smart Entry synchronization/cleanup jobs; `rollback --force` bypasses the guard and may interrupt traffic.

## 2.52.1 Authorized entry source migration

- An administrator can add or remove failover entry nodes with customer grants in place. The panel stages every active customer's hidden forward on a new node before saving the group, and refuses the change if deployment fails. Customer ports, destinations, usage and quotas remain intact.
- Old customer listeners and managed source resources drain for at least five minutes or twice the DNS TTL (up to one day), then a background worker retires them. Failed cleanup is retried; staged resources are registered for cleanup before the group switches.
- If a failed database save has already touched DNS, the panel restores the committed DNS configuration before cleaning up the staged listeners. A failed DNS rollback is persisted and retried every 30 seconds while the staged listeners remain available; the administrator card displays a pending rollback badge.
- Paused, expired and quota-exhausted grants do not open new listeners during source edits. A valid resume provisions missing replicas and respects each port's state before restoring the grant. Newly introduced entry IPs are checked against every existing customer landing to prevent forwarding loops.
- Member enable/disable uses the same synchronization. Hostname, DNS identity and same-node tunnel changes still require a separate address or port migration, because the same listener cannot be staged twice on one node. Existing TCP sessions cannot be moved transparently and may end when the drain window closes.
- Fixes a duplicated table engine option in the authorized-entry schema initializer, and adds the retirement column to existing installations. Extends only failover save/member-toggle request timeouts to 170 seconds; large deployments may still need an operator-maintenance window or a future background migration job.
- Panel-only release. Agent and Connector remain `2.52.0`; no live customer nodes or DNS records were modified by regression tests.
- `2.52.0` remains available as a rollback baseline. The panel manager checks for outstanding DNS rollback and listener-drain jobs before returning to a version without the cleanup worker. Wait for migration cleanup, then run `sudo /usr/local/sbin/flux-panel-manager rollback`. For an emergency only, `rollback --force` bypasses this guard and can interrupt customers. The new database table and column are additive and are left in place by rollback.

## 2.52.0 Frontend caching, virtual lists and authorized-entry operations

- Completes the frontend scaffolding from `bbd7dea`: installs and locks React Query, TanStack Virtual, development-only query tools, skeleton components and lazy-loaded Excel export dependencies; fixes TypeScript errors and invalid example imports.
- Integrates the existing authorized-entry backend workflow into `AuthorizedEntriesPage`: search, refresh, loading/error states, current-result selection, batch resume/pause/revoke and CSV/Excel/JSON export with explicit public-facing columns. Failed batch items retain their selection and error details; revocation requires confirmation.
- Uses a responsive virtual card grid for more than 60 grants. Local browser checks exercised 150 grants, bounded rendering, search, partial batch failure, desktop/mobile layouts and customer permission boundaries using a separate mock API.
- Scopes cached queries to the login session, synchronizes selection after asynchronous updates, prevents automatic replay of writes, fixes sub-640px breakpoint detection and horizontal overflow in virtual grids.
- Registers the shared card, badge, skeleton, batch and export entry points and imports the design tokens. Existing node management and backend entry-failover functionality remain available.
- Synchronizes panel, Agent, Connector, installer URLs and image defaults to `2.52.0`. Frontend regression tests run in the release pipeline. Performance percentage claims in the original scaffolding have not been measured and are not release guarantees.

## 2.51.78 Entry failover lifecycle and quota fixes

- Resets exhausted customer packages at the monthly boundary, retries failed restores, and preserves expired or administrator-paused grants. A failed customer reconciliation no longer blocks other customers.
- Counts UDP bytes alongside TCP bytes for failover-group quotas, retries failed quota pauses, and restores only forwards recorded as running before the quota pause. Expired or administrator-stopped groups remain stopped after reset.
- Protects customer links by rejecting source-group entry/domain/DNS changes or deletion while customer grants remain. Revoke the dependent grants before changing these settings; this release does not automatically migrate customer links.
- Restores source-entry IPv4/IPv6 landing loop prevention, preserves disabled members and their IDs on edit, retains daily usage, and snapshots historical endpoint details.
- Preserves paused automatic switching in TCP latency mode, honors post-switch rejection in all modes, and requires both public DNS resolvers to confirm propagation.
- Existing quota-paused groups from older versions have no reliable pause ownership snapshot. If stopped forwards remain, automatic restoration reports this explicitly and requires administrator confirmation in Forward Management instead of enabling manually stopped forwards. Historical details already lost in previous edits cannot be reconstructed.
- Panel-only release; Agent and Connector remain `2.51.37`. Regression tests and builds do not simulate outages on production nodes or change live DNS.

## 2.51.77 Docker application center card layout

- Reorganizes Docker nodes and deployed applications into balanced, full-width card sections instead of an uneven fixed sidebar and oversized application column.
- Adds clear section counts and separates node capability, application status, access details, and management actions without changing Docker operations.
- Panel-only release; Agent and Connector remain `2.51.37`.

## 2.51.76 Routing overview and aggregation card layouts

- Reorganizes the Routing Center into module summary cards, boundary cards, capability cards, and static finding cards so it reads as an overview rather than a flat table.
- Adds a focused multi-connection explanation and card-based runtime summary to Multi-line Aggregation while keeping dense path metrics available for comparison.
- Panel-only release; Agent and Connector remain `2.51.37`.

## 2.51.75 Source-IP layout and orphan port cleanup

- Reorganizes the Source IP routing page into clear cards for the operating model, rule capabilities, runtime summary, debug tool, IP databases, and routing groups.
- Reorganizes the Smart Entry page into compact overview, workflow, database, and strategy card sections while keeping existing routing controls and diagnostics.
- Cleans orphaned port pools after their server node record has been deleted, including stale leases, grants, and unusable published mappings, while preserving active domain and home-network dependencies.
- Panel-only release; Agent and Connector remain `2.51.37`.

## 2.51.74 Separate switch events from failover resources

- Gives the recent-switch event stream a lighter visual treatment with compact event cards, status accents, and an event-stream label.
- Adds a separate Cross-entry resource section heading so operational failover cards are clearly distinct from historical switch events.
- Panel-only release; Agent and Connector remain `2.51.37`.

## 2.51.73 Uniform recent-switch cards

- Makes the latest Cross-entry Failover event use the same compact two-column card size as every other recent event.
- Keeps the latest event highlighted with a label and accent border without making it span the full content width.
- Panel-only release; Agent and Connector remain `2.51.37`.

## 2.51.72 Recent switch overview

- Highlights the latest Cross-entry Failover event with a focused summary instead of spreading every event across a sparse full-width row.
- Presents other groups in compact responsive event cards with the route change, current route, reason, timestamp, and endpoint address visible without opening history.
- Keeps full event details and the existing history action available without changing failover behavior.
- Panel-only release; Agent and Connector remain `2.51.37`.

## 2.51.71 Cross-entry active route summary

- Adds the active route name, entry address and port, forwarding target, and bearer state directly to the collapsed Cross-entry Failover card summary.
- Removes the empty visual area left between the expiry line and summary metrics while keeping detailed telemetry behind the expand control.
- Panel-only release; Agent and Connector remain `2.51.37`.

## 2.51.70 Cross-entry summary cards

- Simplifies each Cross-entry Failover card to a compact summary with the group name, overall state, current bearer, healthy route count, monthly traffic, and last check time.
- Moves per-route telemetry, quality policy details, fault history, schedules, and probe diagnostics behind an explicit expand/collapse control on each card.
- Keeps all failover controls and business behavior unchanged; the detail state is presentation-only.
- Panel-only release; Agent and Connector remain `2.51.37`.

## 2.51.69 Compact resource cards

- Converts multi-line aggregation groups, DNS provider configurations, dynamic DNS rules, server assets, port pools, user port grants, and Docker nodes/apps into compact responsive resource cards.
- Keeps dense port-ledger comparisons, diagnostic output, event history, and other high-column detail views in their existing table or output layouts.
- Preserves existing actions and data fields while using stable card boundaries, compact spacing, and responsive grids for scanning.
- Panel-only release; Agent and Connector remain `2.51.37`.

## 2.51.68 Cloudflare zone auto-detection

- Allows managed HTTPS domain publishing to find and import the matching Cloudflare Zone from an enabled account when the operator leaves the Zone selector empty.
- Fixes the SQL column aliases used by the auto-detection path, preserves actionable Cloudflare errors, skips only accounts whose stored Token cannot be decrypted, and adds a regression test for matching an existing Zone on a subdomain.
- Includes the responsive service-publishing and smart-entry layout changes that were previously left uncommitted.
- Panel-only release; Agent and Connector remain `2.51.37`.

## 2.51.67 Flux Panel layout system

- Unifies the main business-page container width, padding, spacing, and responsive alignment across the dashboard, resource, authorization, routing, and management views.
- Compresses ordinary empty states so an empty list does not occupy most of the viewport, while preserving larger diagnostic output areas where the extra space is useful.
- Adds a mobile "more" menu and current-page title, keeps frequent actions in the bottom navigation, and prevents the user resource editor's long form from losing its summary or save controls while scrolling.
- Keeps `2.51.66` available as the previous rollback version. Panel-only release; Agent and Connector remain `2.51.37`.

## 2.51.66 Authorized entry pre-authorization state

- Makes authorized-entry grants with zero landing ports display as `待配置`, while grants with active landing ports display as `使用中`.
- Clarifies that pre-created templates and grants do not allocate a tenant forwarding port or generate traffic until the user adds a landing target.
- Keeps revoked grants excluded from both backend responses and user resource summaries.
- Panel-only release. Agent and Connector remain `2.51.37`.

## 2.51.65 User editor deleted-grant cleanup

- Adds a frontend fallback filter to the user resource editor so revoked (`deleted`) authorized-entry grants disappear even when an older cached/API response still contains them.
- Keeps the user resource summary count and aggregate display aligned with the active authorized-entry grants only.
- Panel-only fix release. Agent and Connector remain `2.51.37`.

## 2.51.64 Authorized entry list and card layout

- Removes revoked (`deleted`) authorized-entry grants from both the backend response and frontend rendering, so a deleted grant disappears after refresh instead of remaining as a large inactive card.
- Changes authorized-entry cards to a compact responsive grid similar to the node page, with more cards per row and no equal-height stretching of shorter cards.
- Panel-only fix release. Agent and Connector remain `2.51.37`.

## 2.51.63 User resource authorization overview

- Adds authorized-entry grants to the administrator's user resource editor. Each user now shows assigned authorization packages, visible `domain:port` mappings, landing targets, port allocation, traffic usage/quota, reset day, expiry, and state alongside tunnels, nodes, port pools, and private proxy grants.
- The user editor's aggregate traffic summary includes authorized-entry quotas, and its management action opens the authorized-entry page filtered to that user. Shared entry templates remain managed from the authorized-entry resource page because they are reusable administrator-owned line resources rather than user-owned grants.
- Panel-only release. Agent and Connector remain `2.51.37`.

## 2.51.62 Multiple templates per failover group

- Removes the database constraint that limited each cross-entry failover group to a single authorized-entry template. The same group can now back several templates as long as their port ranges stay disjoint; port allocation already rejects conflicts at assignment time.
- Existing installations drop the legacy unique index automatically at startup and fall back to a normal lookup index.
- Panel-only release. Agent and Connector remain `2.51.37`.

## 2.51.61 Authorized entry forbidden-target clarity

- Splits the combined "platform node / management address / denied CIDR" rejection into exact reasons. The error now names the matching platform node (including nodes whose multi-line entry IP list contains the address) or the exact denied CIDR that the tenant target hit.
- Also fixes node-address matching for nodes whose entry IP field stores multiple addresses, which previously could miss or over-block depending on storage format.
- Panel-only fix release. Agent and Connector remain `2.51.37`.

## 2.51.60 Authorized entry error surfacing

- Converts silent authorization-entry template/grant/port failures into explicit Chinese error messages. Database or failover-group problems now return the concrete cause instead of an opaque timeout after the frontend aborts at 30 seconds.
- Adds the same guard to port creation and grant saving so the panel never leaves the operator guessing about why a save failed.
- Panel-only fix release. Agent and Connector remain `2.51.37`.

## 2.51.59 Authorized entry select echo fix

- Fixes the entry failover group, entry template, recipient, protocol, and billing-direction dropdowns in the authorized-entry forms not showing the selected label after a choice was made.
- Adds the required HeroUI textValue to every SelectItem so the trigger renders the chosen option; dynamic option collections (groups, templates, users) previously rendered an empty trigger even though the value was stored.
- Panel-only fix release. Agent and Connector remain `2.51.37`.

## 2.51.58 Authorized entry grants

- Adds an administrator-owned authorization-entry product on top of an existing cross-entry failover group. A recipient sees only the assigned `domain:port`, own public target, port count, quota, reset day, expiry, and state; entry nodes, tunnels, backup order, DNS, and internal failover remain hidden.
- Administrators can create a private entry template, define the available port range and extra denied CIDRs, grant or edit independent quotas, pause/resume or revoke a grant, and safely clean the hidden forwards before deleting a recipient.
- Tenant targets accept numeric public IPv4/IPv6 only. Loopback, private, CGNAT, link-local, metadata, documentation, benchmark, multicast/reserved addresses, platform node addresses, and the template's VPC/Docker/Kubernetes/admin CIDRs are rejected.
- Each authorization entry is a separate billing entitlement. Its bytes appear in the recipient aggregate alongside tunnels and forwards but internal entry replicas never bill a user/tunnel quota a second time. Reaching the quota pauses only that grant and its ports; the configured Beijing-time reset resumes it. The existing Agent reports traffic periodically, so the cutoff is enforced on the next report rather than promising byte-zero overshoot.
- Panel-only release. Agent and Connector remain `2.51.37`.

## 2.51.57 Remote terminal viewport fit fix

- Fixes the bottom row of the remote terminal being clipped when xterm measured a container that included visual padding.
- Keeps the padding in an outer wrapper and fits xterm to a dedicated, unpadded viewport.
- Panel-only release. Agent and Connector remain `2.51.37`.

## 2.51.56 Cross-entry quota schema compatibility fix

- Ensures monthly failover-link quota columns are initialized before older optional schema upgrades can interrupt startup initialization.
- Removes column-position dependencies from the quota migration so existing MySQL installations can add the fields safely.
- Panel-only repair release. Agent and Connector remain `2.51.37`.

## 2.51.55 Monthly failover-link traffic quota

- Adds one total monthly traffic quota to each cross-entry failover link instead of separate quotas per entry route.
- Supports inbound-only, outbound-only, or bidirectional accounting and a Beijing-time reset day from the 1st through the 28th of each month.
- When the quota is reached, the whole failover group and its underlying forwards are paused; the next monthly cycle resets usage and restores the group automatically.
- Panel-only release. Agent and Connector remain `2.51.37`.

## 2.51.54 Cross-entry usage query fix

- Fixes the entry failover daily-usage query missing its source table, which caused the page to fail to load after `2.51.53`.
- Adds a regression test for the exact daily usage query. Agent and Connector remain `2.51.37`.

## 2.51.53 Confirmed traffic usage time

- Adds per-entry real usage time and highlights the route with the longest confirmed use today.
- Counts only bounded telemetry windows that contain data returned from the entry to the client, confirming that traffic made it beyond the entry listener.
- Idle connections, connection retries, and request-only windows without a response are excluded, so a broken downstream route is not reported as real use.
- Panel-only release. Agent and Connector remain `2.51.37`; iOS App and Agent binaries are unchanged.

## 2.51.52 Daily entry usage duration

- Adds Beijing-time daily active duration to every failover entry and highlights the longest-used entry in each failover group.
- Settles the outgoing active entry on successful switches, group pauses, expiry, and configuration edits; intervals that cross midnight are split between days.
- Active time is computed only for primary/backup failover. Active-active DNS has no single bearer route, so it deliberately does not report misleading per-entry duration.
- Existing groups begin accurate daily accumulation after this upgrade. Panel-only release; Agent and Connector remain `2.51.37`.

## 2.51.51 Failover group master switch

- Adds a visible master switch to every cross-entry failover group.
- Turning it off pauses the group's probes, automatic switching, and DNS scheduling without deleting forwards, tunnels, records, per-entry states, or forcing existing connections closed.
- Turning it on immediately re-probes enabled routes and restores normal scheduling. Panel-only release; Agent and Connector remain `2.51.37`.

## 2.51.50 Per-entry failover switches

- Adds an independent enable/disable switch to every cross-entry failover route.
- Disabled routes are excluded from basic probes, quality probes, failover candidate selection, and active-active DNS records without deleting their forwards, tunnels, ordering, or history.
- Disabling the active route requires a healthy enabled backup and verifies that the switch completed; otherwise the route is automatically re-enabled. The final enabled route and a manually locked active route cannot be disabled.
- Panel-only release. Agent and Connector remain `2.51.37`; iOS App and Agent binaries are unchanged.

## 2.51.49 Release-note size guard

- Publishes only the current release-note section so the GitHub Release body remains below GitHub's size limit as historical notes accumulate.
- Includes the `2.51.48` cross-entry data-path diagnostics, legacy database migration, and runtime upgrade gate.
- Panel-only release. Agent and Connector remain `2.51.37`; iOS App and Agent binaries are unchanged.

## 2.51.48 Cross-entry data-path diagnostics

- Separates accepted entry connections from actual bidirectional traffic and classifies each route as bidirectional traffic, requests without responses, connections without recent traffic, stale telemetry, or idle.
- Stores cumulative bytes received by the entry, bytes returned to clients, and the latest timestamp for each direction using the existing Agent `2.51.37` telemetry; no Agent upgrade is required.
- Distinguishes a current TCP probe failure from a recovered historical incident and adds timestamps. New failures identify handshake timeout, refused port, missing route, or address resolution failure instead of the generic public-entry error.
- Adds an additive legacy-database migration plus a real MySQL runtime upgrade assertion. Existing forwards, tunnels, DNS records, failover order, and fault counters are preserved.
- Panel-only release. Agent and Connector remain `2.51.37`; iOS App and Agent binaries are unchanged.

## 2.51.47 Cross-entry expiry control sizing

- Restores the standard HeroUI input as the expiry field's visual shell so its height, label, spacing, and helper text match neighboring controls.
- Keeps date selection in a separate native control and exposes independent clear and calendar actions, preventing the browser date UI from stretching the grid or producing duplicate icons.
- Panel-only release. Agent and Connector remain `2.51.37`; iOS App and Agent binaries are unchanged.

## 2.51.46 Cross-entry expiry field final alignment

- Makes the expiry control match the neighboring fields in card size, internal label, value typography, spacing, and vertical alignment.
- Keeps the native `datetime-local` picker usable through a transparent interaction layer while presenting one consistent calendar affordance and a separate clear action.
- Prevents browser-native date text and borders from changing the form grid height or creating duplicate calendar icons.
- Panel-only release. Agent and Connector remain `2.51.37`; iOS App and Agent binaries are unchanged.

## 2.51.45 Cross-entry expiry field visual alignment

- Aligns the expiry field with the neighboring form controls by using the same card height, background, internal label, value row, and spacing.
- Removes the browser-native white border appearance while retaining the native date picker, clear action, validation, and Beijing-time semantics.
- Panel-only release. Agent and Connector remain `2.51.37`; iOS App and Agent binaries are unchanged.

## 2.51.44 Cross-entry expiry field layout

- Replaces the HeroUI floating-label wrapper around the native `datetime-local` control with a fixed label and separated description/error rows, preventing the label and selected date from overlapping.
- Reserves space for both the browser calendar affordance and the clear action so the controls remain distinct when an expiry is set.
- Panel-only release. Agent and Connector remain `2.51.37`; iOS App and Agent binaries are unchanged.

## 2.51.43 Reliable release-tag online updates

- The Update page now submits the exact detected Release version to the host updater.
- The updater reads that version from the request, downloads the matching Git tag instead of silently using `main`, and verifies the downloaded `VERSION` before replacing the running source.
- A missing or malformed target version, or a tag/version mismatch, fails before deployment. Existing rollback and database-volume protection remain active.
- The confirmation dialog and fallback command now use a versioned release tag. Panel-only update; Agent and Connector remain at `2.51.37`.

## 2.51.42 Optional cross-entry link expiry

- Adds an optional Beijing-time expiry to each Cross-entry Failover group. Leaving it blank keeps the existing permanent-link behaviour.
- Shows permanent, expiring-soon, remaining-days, and expired states in the editor and group cards; expired groups cannot be manually probed until renewed.
- At expiry, the panel disables the group, stops probes and DNS scheduling, and removes only DNS records managed by that group. Existing hand-created forwards, tunnels, member order, and fault statistics are retained.
- DNS cleanup failures are recorded and retried every 30 seconds. Existing clients that omit the new field retain the stored expiry; explicitly clearing it restores permanent validity.
- Panel-only release. Agent and Connector remain at `2.51.37`; iOS App and Agent binaries are unchanged.

## 2.51.41 Smart-entry live connection diagnostics

- Clarifies that Smart Entry's live count is the instantaneous TCP connection sample, not the same thing as recent traffic or DNS route usage.
- Adds backend activity state and hints for each Smart Entry route so the UI can distinguish active TCP connections, recent traffic with zero sampled TCP connections, idle services, stale telemetry, and waiting-for-Agent states.
- Keeps DNS records, routing decisions, forwards, tunnels, connection totals, and Agent/Connector targets unchanged; Agent and Connector remain at `2.51.37`.

## 2.51.40 Legacy remote-probe accounting compatibility

- Derives successful TCP probe connections from request count and packet loss when an older Agent or Connector omits per-attempt `samples`.
- Prevents low-frequency quality probes from appearing as business connections without requiring an immediate Agent upgrade.
- Keeps the `2.51.39` telemetry-baseline protection; Agent and Connector targets remain at `2.51.37`.

## 2.51.39 Preserve probe deductions across telemetry baseline

- Keeps successful health-probe deductions pending while the first Agent telemetry report establishes its baseline.
- Prevents probes completed during panel startup from appearing as business connections in a later, delayed Agent report.
- Applies the same baseline rule to Cross-entry Failover and Source IP routing telemetry; Agent and Connector stay at `2.51.37`.

## 2.51.38 Business-only failover connection totals

- Excludes Cross-entry Failover's own availability checks, quality probes, and post-switch verification connections from each line's cumulative new-connection total.
- Tracks successful panel and remote Connector/Agent TCP probes until the next service telemetry report, then consumes them before adding business connections.
- Rebuilds only the new connection counters once during upgrade; routing order, DNS, forwards, tunnels, fault history, and quality policy state remain unchanged.
- Panel-only follow-up release. Agent and Connector stay at `2.51.37`, which already provides exact service-instance telemetry.

## 2.51.37 Accurate failover connection telemetry

- Fixes inflated cumulative connection totals when GOST configuration refreshes leave old and new service instances reporting the same forward briefly.
- Adds a service-instance generation to Agent telemetry. The panel rebases on a newer instance and ignores late reports from an older instance; legacy Agents conservatively ignore counter regressions instead of recounting their full history.
- Resets only the inaccurate connection baseline and cumulative values introduced in `2.51.36` during the one-time schema upgrade. Failover order, DNS, forwards, tunnels, quality history, and fault counters are unchanged.
- Updates Panel, Agent, and Connector to `2.51.37`; existing nodes can be upgraded in one batch from the Update page.

## 2.51.36 Cross-entry live connection telemetry

- Shows current connections, cumulative new connections, TCP latency, and telemetry freshness for every line in Cross-entry Failover.
- Reuses the existing five-second Agent service telemetry and only counts the actual entry service, so no extra Agent request or duplicate landing-hop count is introduced.
- Establishes a baseline on the first report, preserves cumulative counts across Agent restarts and failover-group edits, and reports stale current connections as zero after 30 seconds without telemetry.
- Adds additive database columns and an indexed forward/node lookup. Existing failover rules, DNS, forwards, tunnels, fault statistics, and Agent binaries remain unchanged. Agent and Connector stay at `2.51.13`.

## 2.51.35 Managed cross-entry lifecycle safety

- Allows fixed-target Cross-entry Failover groups to add, remove, and reorder entry nodes without changing the fixed landing target or shared public port.
- Retains existing managed forwards and their failure/quality statistics when an entry stays in the group; new entries are port-checked before any managed resource is created.
- Deletes only DNS records, forwards, and tunnels owned by the Cross-entry group. User-created forwards and tunnels are never removed by group deletion.
- Records failed forward/tunnel cleanup in a durable retry queue so an offline Agent does not leave an untracked managed resource forever; the scheduler retries after the node returns.
- Failed saves keep the original database configuration and report the failing stage and field-level reason. Panel-only release; Agent and Connector binaries remain `2.51.13`.

## 2.51.34 Docker application center layout

- Prevents the Docker node column from stretching the deployed-application column to the height of a long node list.
- Makes a single deployed application use the full available application area; multiple applications continue to use a responsive two-column layout.
- Keeps Docker discovery, deployment, upgrade, backup, rollback, command, event, and removal behavior unchanged.
- Panel-only release; Agent and Connector binaries remain `2.51.13`.

## 2.51.33 Scheduled entry control alignment

- Aligns all scheduled-entry controls to the top of the rule row so descriptions with different heights cannot move the start and end inputs onto different vertical baselines.
- Gives both time columns enough width for `HH:mm, can fill 24:00` without wrapping and matches the header padding to the rule card content.
- Keeps the existing schedule data, mutual-exclusion rules, mobile stacking, and failover behavior unchanged.
- Panel-only release; Agent and Connector binaries remain `2.51.13`.

## 2.51.32 Scheduled entry layout alignment

- Aligns the scheduled entry preference header and rule rows to the same five-column grid: weekday, start, end, preferred entry, and actions.
- Combines the enable switch and delete action in one stable action column so the controls no longer shift the time and route columns out of alignment.
- Keeps the mobile single-column layout and all scheduling behavior unchanged.
- Panel-only release; Agent and Connector binaries remain `2.51.13`.

## 2.51.31 Beijing-time scheduled entry preference

- Adds optional Beijing-time (`Asia/Shanghai`) preference windows to Cross-entry Failover, with weekday selection, ordinary windows, and overnight windows such as `23:00-02:00`.
- Keeps hard outage protection, quality degradation, cooldown, preheat isolation, post-switch verification, and failed-switch blacklist active. If the scheduled target is unhealthy, the existing failover rules choose the next eligible entry; after the window ends, the default primary is restored after health, cooldown, and residency checks.
- Enforces mutual exclusion: scheduled preference cannot be combined with active-active DNS or TCP latency auto-selection; manual pause and manual lock always take priority. Overlapping windows and targets outside the current entry group are rejected with field-level errors.
- Removes scheduled rules together with their Cross-entry Failover group, preventing deleted configurations from affecting future groups.
- Adds policy coverage for schedule precedence, fallback, manual controls, strict quality targets, and empty active-entry TCP selection.
- Panel-only release; Agent and Connector binaries remain `2.51.13`.

## 2.51.30 Database and failover diagnostics

- Synchronizes the legacy `gost.sql` template with the 500-character monitoring detail columns.
- Returns the Cross-entry Failover save stage, concrete field errors, and underlying reason; the editor now marks the relevant Zone, domain, entry node, public port, managed target, or existing-forward field.
- Adds a real MySQL integration path that applies the monitoring migration, verifies startup expansion of legacy 255-character columns, and confirms the scheduled monitoring service persists an alert without detail overflow.
- Keeps Agent and Connector binaries at `2.51.13`; this is a panel-only release.

## 2.51.29 Monitoring detail bounds

- Fixes monitoring scans failing when certificate, DDNS, or forwarding diagnostics produce a `detail` value longer than the database column.
- Expands monitoring `detail` columns to 500 characters for new and existing installs, and bounds every monitoring state detail before writing current status, history, alerts, and notifications.
- Keeps Agent and Connector binaries at `2.51.13`; this is a panel-only backend stability release.

## 2.51.28 Managed cross-entry forward default strategy

- Fixes managed Cross-entry Failover creation failing when the generated forward does not provide a route balance strategy.
- Defaults missing forward balance strategy values to `round` in the forwarding service and explicitly sets the managed generated forward strategy to `round`.
- Keeps the detailed creation-failure diagnostics from `2.51.27`; Agent and Connector binaries remain `2.51.13`.

## 2.51.27 Cross-entry managed create error fallback

- Fixes managed Cross-entry Failover creation returning a blank failure reason when the underlying forward creation path throws or returns an empty result.
- Adds node name, managed public port, target address, and the underlying reason to managed-forward creation failures.
- Makes uncaught API exceptions return a readable fallback containing the exception type and request ID instead of an empty `msg`. Agent and Connector binaries remain `2.51.13`.

## 2.51.26 Cross-entry failover creation diagnostics

- Surfaces backend `msg/message/error/detail` responses in the frontend instead of falling back to a generic creation failure.
- Adds per-node managed public-port diagnostics for Cross-entry Failover: ledger conflicts, real Agent port-check failures, port number, node name, and Agent no-response cases are now included in the error.
- Stops automatic port scanning immediately when the Agent system port check itself fails, so operators get the failing node instead of waiting through a large port range. Agent and Connector binaries remain `2.51.13`.

## 2.51.25 Managed cross-entry failover cleanup safety

- Fixes the managed cross-entry failover creation failure path so automatically created forwards and direct tunnels are cleaned up before the error result is committed.
- Prevents a database rollback from restoring managed forwarding records after the real GOST resources have already been removed.
- Keeps the existing-forward mode unchanged and keeps Agent and Connector binaries at `2.51.13`; this is a panel-only release.

## 2.51.19 Cross-entry failover layout cleanup

- Reflows the cross-entry failover editor into clearer cards and collapsible rule groups so the page reads like a configuration panel instead of a long note wall.
- Separates the base settings, member order, detection presets, quality failover, and summary guidance into cleaner visual blocks without changing existing strategy behavior.
- Panel-only release; Agent and Connector binaries remain `2.51.13`.

## 2.51.18 Cross-entry failover cooling and lock control

- Adds the backup-line cooling pool, post-switch reject blacklist, and expiring manual lock controls to Cross-entry Failover.
- Surfaces healthy, observing, cooling, blacklisted, and disabled member states directly in the failover page and keeps the rule summary aligned with the runtime selector.
- Panel-only release; Agent and Connector binaries remain `2.51.13`.

## 2.51.16 Expanded in-panel usage guide

- Expands the built-in `/guide` page with detailed tutorials for source-IP entry routing, multi-line concurrent scheduling, AWS resources, Docker App Center, real bandwidth tests, local single-thread speed tests, UDP/QUIC diagnostics, IP quality checks, system self-check, and account settings.
- Each guide entry now explains why the feature exists, what to prepare before using it, the exact setup flow, the expected result, and the operational caveats.
- Keeps Agent `2.51.13`; this is a panel documentation and UX release only.

## 2.51.15 Node deletion cleanup

- Cleans associated private proxy records and Agent runtimes when deleting a node.
- Prevents online node deletion when a private proxy runtime cannot be removed safely.
- Automatically hides and converges historical private proxies whose node was already deleted.

## 2.51.14 Remove protocol probe center

- Removes the protocol probe center frontend, API, backend service, schema initializer, and test harness.
- Keeps local single-thread speed testing and other network diagnostics intact.

## 2.51.13 Protocol probe page compatibility

- Fixes the `/protocol-probe` frontend regression where rendering could crash on `targetType` when the backend payload is flat instead of nested.
- Keeps protocol probe items compatible with both the new flat payload and older nested responses.

## 2.51.12 Protocol client probe center

- Moves protocol measurements out of the Agent and into a panel-side independent SOCKS5/HTTP client.
- Measures the actual protocol path from the panel server to the protocol endpoint before requesting Cloudflare download/upload data.
- Lists all CloudNest-created protocols and adds encrypted saved external SOCKS5/HTTP targets for friends' protocols.
- Removes the obsolete Agent `ProtocolProbe` command and keeps unsupported VLESS+REALITY, Shadowsocks, Trojan, Hysteria2, TUIC v5, and WireGuard visibly pending until their independent client engines are integrated.
- Keeps protocol history with probe source, client engine, HTTP status, handshake latency, first response latency, throughput, and error details.

## 2.51.11 Protocol probe center and automatic panel updates

- Adds an independent Protocol Probe Center instead of mixing protocol-node measurements into the browser/Cloudflare local speed page.
- Adds Agent-side availability, handshake latency, first-response latency, download throughput, upload throughput, HTTP status, error reporting, and persistent history for SOCKS5, HTTP, and VLESS+REALITY.
- Keeps Shadowsocks, Trojan, Hysteria2, TUIC v5, and WireGuard visibly pending until reliable Agent client probes exist; no protocol reports fabricated throughput.
- Adds a systemd timer that checks the latest GitHub Release every 15 minutes and queues the existing guarded update worker. Health checks, rollback, database-volume preservation, manual updates, and systems without systemd remain supported.

## 2.51.10 Client speed display fix

- Fixes the local speed center summary so `HTTP failure rate` now renders `0%` instead of `-` when no requests fail.
- Changes the packet-loss card to show an explicit HTTP-approximation label when TURN is not configured, instead of looking like a missing value.
- Keeps the oversized single-thread speed test and Cloudflare quality metrics from `2.51.9`.

## 2.51.9 Cloudflare-style local speed quality

- Upgrades the local browser speed test into a CloudNest native speed center with oversized single-thread download profiles up to 64 GB.
- Adds the official `@cloudflare/speedtest` engine for Cloudflare-style quality metrics, including latency, jitter, loaded latency, download/upload measurement points and AIM experience scores.
- Shows Cloudflare edge metadata from the speed endpoint, including public IP, IP version, ASN when exposed, edge colo, country/city and protocol details.
- Adds HTTP request failure-rate probing as a browser-safe packet-loss approximation while clearly separating it from strict UDP/TURN packet loss.

## 2.51.8 本机单线程测速

- 新增“本机单线程测速”页面，浏览器直连 Cloudflare 测速端点。
- 单请求计速，详细显示 Mbps / Gbps、下载量、耗时和历史结果。
- 适合查看本地电脑到边缘节点的单线程吞吐，避免大于 1Gbps 时只看到笼统结果。

## 2.51.7 Source-IP ASN prefix automation

- Adds automatic ASN prefix expansion to Source-IP Entry rules. ASN rules now fetch current announced prefixes from RIPEstat, cache them locally, and save the expanded CIDR snapshot for Agent runtime matching.
- Adds an ASN prefix cache table, manual refresh endpoint, and scheduled refresh. Refreshing updates enabled ASN rules and re-syncs affected Source-IP Entry groups to the ingress Agent.
- Shows ASN cache status in the Source-IP Entry page and lets administrators refresh the ASN library from the UI. CIDR input is no longer required for ASN rules.
- Panel-only release; Agent and Connector target binaries remain unchanged because runtime selection still uses the existing CIDR-based Source-IP selector.

## 2.51.4 Release warning cleanup

- Removes the remaining Lombok equals/hashCode warning from the release workflow after the `2.51.3` dependency maintenance run.
- Keeps Agent and Connector target binaries at `2.51.3`; this panel release reuses the security-updated Agent assets from the current release flow.

## 2.51.3 Dependency and workflow maintenance

- Clears current frontend dependency audit findings by updating `react-router`/`react-router-dom`, `postcss`, `js-yaml`, and scoped `brace-expansion` overrides while keeping the existing React 18 application surface unchanged.
- Updates Agent Go dependencies that triggered Dependabot alerts, including `golang.org/x/net`, `golang.org/x/crypto`, `github.com/pion/dtls/v3`, and `github.com/pion/stun/v3`.
- Refreshes the GitHub Actions release workflow to current major versions for checkout, Java, Node, Go, QEMU, Buildx, registry login, and Docker build/push actions.
- Rebuilds Agent and Connector binaries as `2.51.3` so nodes can receive the security-updated Go dependency set through the normal Agent upgrade page.

## 2.51.2 Access boundary and load cleanup

- Tightens administrator-only routes so topology, scheduler, diagnostics, Docker apps, resource management, monitoring, user management, and update pages no longer rely on stale browser role flags.
- Keeps ordinary-user pages focused on usable address, port, traffic quota, reset day, remaining flow, and expiry information while hiding owner names, backend nodes, internal route paths, DNS targets, and other administrative topology details.
- Makes the Routing Dispatch Center and Resource Center tolerate partial API failures and refreshes without replacing the whole page with a spinner when existing data is already available.
- Keeps the local App/WebView panel-address page available while keeping website settings/admin navigation protected.
- Panel-only release; Agent and Connector binaries remain `2.50.4`. iOS App changes and local Agent binaries are not included in this release.

## 2.51.1 Panel cleanup and scheduler ownership protection

- Keeps the existing `/virtual-lan` compatibility redirect while removing the unused duplicate page; separates the WebView/App panel-address screen from the administrator website settings page.
- Shows multi-line concurrent dispatch ownership in the global port ledger instead of presenting its managed listener as an unrelated ordinary forward.
- Adds bidirectional scheduler conflict checks across Smart Entry, Cross-entry Failover, Source-IP Entry, and Multi-line Concurrent Dispatch, including the underlying tunnel set rather than only forward IDs.
- Extends the Routing Dispatch Center to detect overlapping underlying tunnel sets and clarifies that legacy DDNS credentials remain compatible while new DNS credentials are maintained from Domain Management.
- Panel-only release; Agent remains `2.50.4`. iOS App changes and local Agent binaries are not included in this release.

## 2.51.0 Panel organization and scheduling guard rails

- Adds a **Routing Dispatch Center** that explains the relationship between Smart Entry, Source-IP Entry, Cross-entry Failover, and Multi-line Concurrent Dispatch, and flags DNS, port-ledger, and forward-set ownership overlaps.
- Adds scheduler conflict protection so the same DNS record or exact same forward set cannot be silently managed by multiple dispatch strategies.
- Reorganizes the resource entry points: DNS, DDNS, AWS accounts, home devices, and port resources now sit under Resource Center; Virtual LAN is folded into **Private Network and Exit** while keeping the old `/virtual-lan` route as a redirect.
- Renames **Multi-line Aggregation** to **Multi-line Concurrent Dispatch** in the UI to avoid implying guaranteed bandwidth multiplication, while keeping existing APIs and database names compatible.
- Adds a feature capability matrix covering required Agent version, DNS dependency, UDP support, middlebox traffic, and ordinary-user authorization support.
- Panel-only release; Agent and Connector binaries remain `2.50.4`. Existing nodes, tunnels, forwards, ports, DNS records, certificates, private proxies, Docker apps, and running Agent services are not restarted or rewritten during upgrade.

## 2.50.9 Cross-entry escalating quality penalty

- Adds an escalating quality penalty guard for Cross-entry Failover: repeated quality degradation episodes now progress through L1-L5 protection instead of always using one fixed window.
- Keeps unstable entries out of automatic failback during recovery observation until configured healthy samples are reached, reducing 10ms/200ms route flapping.
- Shows penalty level, penalty event count, suppression reason, and recovery observation state on the Cross-entry Failover page, with configurable recurrence memory and observation duration.
- Panel-only release; Agent and Connector binaries remain `2.50.4`.

## 2.50.8 Cross-entry TCP selection layout fix

- Refines the Cross-entry Failover editor layout for primary-preferred TCP latency selection. The switch, numeric fields, and explanatory text are separated into clearer rows so the description no longer wraps into a narrow vertical column.
- Keeps the 2.50.7 strategy behavior unchanged. Panel-only release; Agent and Connector binaries remain `2.50.4`.

## 2.50.7 Primary-preferred TCP latency selection

- Changes lowest-TCP-latency selection into a primary-preferred strategy. The primary remains selected while it is within a configurable latency tolerance of the fastest healthy entry; only a larger gap permits switching away.
- Keeps a separate minimum gain for backup-to-backup changes and the existing minimum residency and cooldown protections, preventing small latency fluctuations from moving traffic repeatedly between backup entries.
- Gives TCP selection its own panel, Agent, or Connector probe source and multi-sample average. If the selected remote probe source is unavailable, latency-based switching pauses instead of silently measuring from the panel server.
- Makes TCP selection mutually exclusive with quality failover, fixed latency targets, smart selection, flap protection, preheat, normal auto-failback, and entry locking. The frontend clears and disables conflicts, while backend normalization and runtime precedence protect old clients and legacy records.
- Hard-outage failover, recovery confirmation, post-switch verification, DNS verification, manual pause, cooldown, and minimum residency remain active. Panel-only release; Agent and Connector binaries remain `2.50.4`.

## 2.50.6 Optional lowest-TCP-latency entry selection

- Adds a manual “自动选择 TCP 延迟最低线路” switch to Cross-entry Failover for groups whose entries have different network paths, including groups made entirely of US entries.
- Selects only healthy, stable, non-degraded, non-suppressed entries with enough recovery confirmations; the measured TCP connect latency is the primary sort key.
- Uses the selected quality probe source's TCP latency when quality probing is enabled, otherwise uses the panel server's TCP probe; keeps cooldown, minimum residency, post-switch verification, DNS verification, and emergency failover protections.
- Adds a configurable minimum latency gain, defaulting to 5 ms, to avoid switching on measurement noise. Existing groups keep their previous priority-based behaviour because the switch is disabled by default.
- Panel-only release. Agent and Connector binaries remain `2.50.4`.

## 2.50.5 Cross-entry fault statistics

- Adds persistent per-entry fault episode totals and separate connection, latency, P95, jitter, packet-loss, flap-protection, and switch-trigger counters.
- Counts confirmed fault episodes instead of every probe tick, records the latest fault time and reason, and avoids double-counting the same connection outage in health and quality probes.
- Preserves accumulated statistics and current health-learning state when administrators reorder primary and backup entries. The failover page now shows these statistics responsively for later route-priority decisions.
- Panel-only release. Agent and Connector binaries remain `2.50.4`.

## 2.50.4 Cross-entry primary-priority failback

- Changes Cross-entry Failover primary failback to use the primary entry's own recovery state instead of comparing its latency with the active backup.
- A recovered primary now fails back after stability, cooldown, residency, and quality checks even when a backup has lower raw latency, matching primary-as-preferred-line behavior for premium routes.
- Removes the misleading primary failback latency tolerance controls from the panel; backup-to-backup quality switching still stays conservative.

## 2.50.3 Cross-entry primary failback tolerance

- Changes Cross-entry Failover primary failback so a recovered primary can return after stability and cooldown when it is equal to, faster than, or only slightly slower than the active backup.
- Keeps backup-to-backup switching conservative: backups still need a meaningful quality benefit before replacing the current active entry.
- Lowers the default primary failback tolerance to `5 ms` or `15%` and updates existing untouched defaults from the older `10 ms` / `20%` values.

## 2.50.2 Cross-entry topology-isolated preheat

- Tightens Cross-entry Failover topology avoidance so `8.218.x.x`-style same large IPv4 ranges are treated as the same risk group instead of only comparing `/24` subnets.
- Adds strict backup preheat isolation. When enabled, preheat will not mark same-provider, same-ASN, same IPv4 `/16`, same IPv6 `/48`, or same-node entries just to fill the requested backup count.
- Adds a panel switch for strict preheat isolation and clearer copy explaining that preheated backups prefer different node, cloud/provider, ASN, and large network ranges.

## 2.50.1 Cross-entry quality probe hotfix

- Fixes a production edge case where a remote quality probe reported success without usable latency samples, causing Cross-entry Failover group checks to loop with a P95 calculation error.
- Empty or malformed Agent quality metrics are now treated as an invalid probe result instead of crashing the scheduler; the member records a clear probe error and the next backup can still be evaluated.
- Keeps the 2.50.0 failover behavior unchanged: backup preheat, DNS provider confirmation, post-switch verification with rollback, P95/jitter checks, flap guard, and topology/fault avoidance remain active.

## 2.50.0 Cross-entry failover verification and preheat

- Adds P95 TCP latency and jitter into Cross-entry Failover quality decisions so short spikes can trigger quality failover even when average latency still looks acceptable.
- Keeps all backup entries continuously probed and marks up to three different-node/different-subnet healthy backups as preheated candidates for smarter quality switching.
- Verifies the target entry after a DNS switch and automatically rolls DNS back when the switched-to entry cannot be reached.
- Confirms Cloudflare managed DNS record content after each switch and records public DNS propagation observations without treating resolver cache lag as a hard failure.
- Agent and Connector binaries are rebuilt as `2.50.0` so remote TCP probes can return samples, P95, and jitter while older agents remain compatible through average-latency fallback.

## 2.49.9 Cross-entry smart quality failover

- Adds smart selection rules to Cross-entry Failover quality mode. The panel now keeps probing every candidate entry, avoids backups with the same current fault when another clean choice exists, avoids same-node or same-address-group entries when possible, and falls back to the best degraded entry only when every line is poor.
- Adds minimum entry residency, failback gain thresholds, manual pause, and manual lock controls so a primary that jumps between good and bad latency does not cause rapid DNS switching.
- Keeps hard outage failover compatible with existing priority order: primary, backup 1, backup 2, and so on. The smarter ranking is only applied when quality probing is active. Agent and Connector binaries remain `2.49.1`.

## 2.49.8 OpenWrt connector service startup fix

- Fixes OpenWrt/iStoreOS Connector service startup by passing the Agent config path explicitly to every Linux service definition. The Agent now enters the Connector install directory before loading runtime state, matching the manual `cd /etc/flux-connector && ./gost -C ./gost.json` startup path.
- Extends installer coverage for systemd, OpenRC, and procd service definitions so future releases keep `config.json` and `gost.json` anchored to the install directory. Agent and Connector binaries remain `2.49.1`.

## 2.49.7 Cross-entry quality flap guard

- Adds quality flap protection for Cross-entry Failover. If an entry repeatedly transitions into quality degradation inside a configurable time window, the panel temporarily suppresses it as an automatic switch or failback target.
- Adds admin controls for flap window, trigger count, and suppression duration, with a conservative default of 3 degradation transitions within 15 minutes causing a 30-minute suppression.
- Shows suppressed entries directly in the failover page so operators can see why a recovered primary is not immediately selected again. This is a panel-only release; Agent and Connector remain `2.49.1`.

## 2.49.6 OpenWrt connector installer support

- Adds OpenWrt/iStoreOS `procd` service support to the Linux Agent/Connector installer, so home devices can be installed as `/etc/init.d/flux-connector` and managed with the router's native service system.
- Updates panel-generated node and home-device install commands to download the current panel release installer instead of the older pinned installer script.
- Agent and Connector binaries remain `2.49.1`; this release fixes the installer wrapper only and does not rebuild or change existing running services until an administrator installs or updates them.

## 2.49.5 Fixed latency target for cross-entry quality failover

- Adds an optional fixed latency target for Cross-entry Failover quality mode. Low-latency groups can now treat sustained latency above a configured target, such as 20 ms, as quality degradation even when baseline-relative rules would still allow it.
- Adds a strict target mode that only switches to backup entries whose latest quality probe is at or below the configured target latency.
- Keeps the feature disabled by default so existing mixed-region groups continue using baseline-first quality evaluation unchanged. This is a panel-only release; Agent and Connector remain `2.49.1`.

## 2.49.4 Cross-entry quality baseline tuning

- Changes quality failover to evaluate latency primarily against each entry member's learned baseline. A high-latency route such as US West to Hong Kong no longer degrades merely because its normal latency is above the default 100 ms fallback threshold.
- Keeps the absolute latency threshold as a fallback guardrail only when it is higher than the member's learned baseline, while low-latency routes can still degrade quickly by their own baseline multiplier.
- Adds unit coverage for mixed-latency groups such as US West to Hong Kong and Hong Kong to Hong Kong. This is a panel-only release; Agent and Connector remain `2.49.1`.

## 2.49.3 Cross-entry quality failover

- Adds optional quality failover for Cross-entry Failover groups. Existing groups keep the original outage-only behavior until quality failover is explicitly enabled.
- Tracks TCP connect latency, packet loss, learned baseline, quality state, bad samples, and recovery samples for each entry member. A reachable but persistently slow primary can switch to the next healthy, quality-normal backup after cooldown, then fail back only after the primary quality recovers.
- Adds panel, Agent node, and Connector probe-source choices. Remote probes reuse the existing `TcpPing` command and require Agent/Connector `2.19.0+`; no Agent rebuild or mandatory node upgrade is included in this panel release.
- Stores only additive database columns. Existing nodes, tunnels, forwards, DNS records, certificates, proxy services, Docker apps, CloudFront resources, and Agent runtimes are not rebuilt by the update.

## 2.49.2 Cross-entry failover ordering

- Adds up and down controls to the Cross-entry Failover edit form so entry members can be reordered without removing and recreating them.
- The saved order continues to map directly to failover priority: first row is the primary entry, then backup 1, backup 2, and so on.
- Frontend-only release. Agent and Connector remain `2.49.1`; existing nodes, tunnels, forwards, ports, DNS records, certificates, proxy services, Docker apps, and CloudFront resources are unchanged.

## 2.49.1 UDP / QUIC diagnostics guardrail

- Adds an administrator-only **UDP / QUIC Diagnostics** page under Utilities.
- Supports authenticated Agent-to-Agent UDP Echo checks with packet loss, RTT, jitter, packet size, IPv4/IPv6 selection, and NAT idle keepalive verification.
- Supports QUIC handshake checks from any online Agent to a custom host or node address, with SNI, ALPN, certificate verification, resolved address, failure reason, and historical results.
- Blocks execution on incompatible Agents before a run is started, and marks old nodes in the page instead of creating misleading failed history rows.
- Stores additive task and run history tables. Existing nodes, tunnels, forwards, proxy protocols, DNS records, certificates, nftables rules, private networks, Docker apps, and CloudFront resources are not rebuilt by this update.
- Agent `2.49.0+` is required only for this new diagnostic command. Older Agents keep existing functions but must be upgraded before running UDP / QUIC diagnostics.

## 2.48.3 Agent upgrade status reconciliation

- Treats the Agent's reconnect-reported version as the final source of truth for online upgrades.
- If the bootstrap terminal reports a failure but the node comes back on the target version, the panel automatically corrects that task and its batch summary to success.
- Keeps the `2.48.2` bootstrap-first upgrade path. Existing nodes, tunnels, forwards, XHTTP applications, CloudFront resources, DNS records, and private-network routes are not rebuilt by the panel update.

## 2.48.2 Bootstrap-first Agent upgrades

- Routes every remote-upgradable online Agent through the panel-controlled terminal bootstrap installer instead of the older Agent self-update path.
- This keeps reconnect-confirmed upgrade tracking while avoiding per-node GitHub Release metadata downloads, which caused some otherwise-online nodes to time out during batch upgrades.
- Panel and Agent move to `2.48.2`. Existing nodes, tunnels, forwards, XHTTP applications, CloudFront resources, DNS records, and private-network routes are not rebuilt by the panel update.

## 2.48.1 Old Agent upgrade fallback

- Routes Agent `2.8.0` through `2.46.x` upgrades through the rollback-protected terminal bootstrap installer instead of the older self-updater. This avoids nodes getting stuck while reading GitHub release metadata and uses the install script path with mirror fallback.
- Keeps Agent `2.47.0` and newer on the self-update path, which was verified by the `2.48.0` production batch upgrade. Existing nodes, tunnels, forwards, XHTTP applications, CloudFront resources, DNS records, and private-network routes are not rebuilt by the panel update.
- Panel and Agent move to `2.48.1`; use the normal panel rollback command if the panel update fails health checks.

## 2.48.0 CloudFront XHTTP split transport

- Adds AWS credential management to the Resource Center with encrypted secrets and live STS identity verification.
- Adds `VLESS + XHTTP + TLS` to private-network egress applications. The Agent reuses its managed Xray runtime, persists and restores XHTTP services, and performs a real temporary-client route test after deployment.
- Supports manual CloudFront/PSGO upload and download domains, or automatic Cloudflare origin DNS plus two AWS CloudFront distributions. Generated client URIs include XHTTP padding and download settings.
- CloudFront creation uses uncached HTTPS viewer behavior and forwards all XHTTP methods, headers, query strings, and cookies to the selected entry port. Existing tunnels continue carrying traffic from the entry to the selected exit.
- Failed deployment removes new Agent runtimes and attempts to disable any partially-created CloudFront resource. Deletion disables distributions first and retains the panel record until AWS permits final deletion; AWS accounts in use cannot be removed.
- Panel and Agent move to `2.48.0`. Existing nodes and applications are unchanged until an administrator creates this protocol. Roll back the panel with `sudo /usr/local/sbin/flux-panel-manager rollback`; delete CloudFront XHTTP applications first so their AWS and Cloudflare resources are reclaimed.

## 2.47.0 Docker Application Center

- Adds an administrator-only **Docker App Center** under Access and Publishing. The panel can inspect Docker-capable nodes, deploy X-UI, Nezha, Alist, and Nextcloud, and keep start, stop, upgrade, backup, remove, rollback, and manual fallback records.
- Deployment can optionally bind a domain through the existing service publishing pipeline, so ports, HTTPS entry, certificates, and reverse proxy rules stay in the same managed chain instead of becoming separate hidden state.
- Agent `2.47.0` adds bounded Docker inspection and application lifecycle commands. The Agent only accepts known templates and managed container names from the panel, validates Docker/Compose availability, and keeps unsupported or old Agents in manual-command mode.
- Existing nodes, tunnels, forwards, private proxies, DNS records, certificates, nftables rules, private networks, and Docker containers are not changed during upgrade. Docker actions run only after an administrator explicitly deploys or operates an app.
- Rollback: stop or remove Docker apps created by this page when possible, then run `sudo /usr/local/sbin/flux-panel-manager rollback`. Existing Docker containers outside CloudNest management are ignored by the feature.

## 2.45.1 Load-tolerant panel upgrades

- Keeps the complete private-network and proxy-egress feature set from `2.45.0`.
- Lets the backend health startup window be configured and raises the default to seven minutes. A healthy backend still continues immediately, while CPU-congested hosts are no longer rolled back before Spring finishes booting.
- Extends the manager's post-deployment wait window and reports backend/frontend states during long starts. Genuine startup failures still trigger the existing automatic rollback.
- Agent `2.45.1` is functionally compatible with `2.45.0`; it is rebuilt so staged upgrades and release assets remain version-consistent.

## 2.45.0 Private network routing and proxy egress applications

- Adds an administrator-only **Private Network and Egress** workspace. Agents can automatically establish a WireGuard network, while existing VPC, cloud-backbone, and dedicated private addresses can be registered and verified bidirectionally.
- Adds SOCKS5 and HTTP egress applications for `B entry -> C exit` and `B entry -> C private transit -> D exit`. Each hop independently selects public, verified native-private, Agent WireGuard, or custom IP transport.
- Supports strict private routing or ordered public fallback. Existing tunnels without a hop configuration continue using their current public addresses.
- Shows the resolved address, network resource, verification state, latency, loss, interface, and fallback for each hop. A real proxy CONNECT test runs after deployment and remains available on demand.
- Adds pause, resume, redeploy, retryable cleanup, port-ledger ownership, managed-tunnel cleanup, and runtime rollback when deployment fails. An Agent timeout cannot be reported as a successful deletion.
- Agent `2.45.0` adds bounded private-link and proxy-route probes. Existing Agent services, tunnels, forwards, proxies, DNS records, certificates, nftables rules, and WireGuard networks are not rebuilt during upgrade.

### Upgrade and rollback impact

- The schema changes are additive: three native-network tables, one egress-application table, and a nullable per-hop configuration on tunnels. Existing tunnel rows default to public routing.
- Delete egress applications and Agent-created WireGuard networks before rolling back when possible, then run `sudo /usr/local/sbin/flux-panel-manager rollback`. Application deletion removes its managed listeners, chains, relay services, and automatically-created tunnel before releasing ports.

## 2.44.1 TCP retransmission and UDP loss measurements

- Extends Real Bandwidth Testing with explicit TCP and UDP modes. Existing tasks remain TCP by default.
- TCP tests collect Linux kernel RTT, segment, and retransmission counters from both source and target Agents, then show retransmission count and rate without treating retransmission as raw packet loss.
- UDP tests use authenticated, sequenced 1200-byte datagrams and report packets sent, received, lost, out of order, loss percentage, jitter, and real payload throughput for upload, download, or bidirectional runs.
- Keeps the temporary listener lifecycle and one-time token protection. The selected TCP or UDP port closes after the run, on failure, or at session expiry.
- Adds only defaulted measurement columns to the bandwidth task and history tables. Existing bandwidth history, nodes, tunnels, forwards, virtual LANs, proxies, and ports are preserved.
- New measurements require Agent `2.44.1`. Agent replacement retains reconnect verification and automatic binary rollback; the panel retains `2.44.0` as its rollback version.

## 2.43.1 Isolated nftables port forwarding

- Adds an administrator-only **nftables port forwarding** board for direct Linux kernel IPv4 DNAT/SNAT without replacing or restarting GOST forwards, tunnels, proxies, or listeners.
- Supports TCP, UDP, and TCP+UDP; fixed IPv4 listeners and targets; source CIDR allowlists; standard masquerade mode; and source-preserving mode for targets with a correct return route.
- Gives `nft_forward` its own database records, event history, Agent state file, traffic counters, and global port-ledger type. Rules on the same node are shown in entry-port order.
- Checks the global panel ledger, real TCP/UDP sockets, external nftables DNAT rules, Agent version, nftables availability, IPv4 forwarding, UFW, firewalld, and the host FORWARD policy before applying a rule.
- Manages only `table ip cloudnest_nat`, never runs `flush ruleset`, validates every generated transaction with `nft --check`, and applies a complete node ruleset atomically. A failed transaction leaves the previous kernel rules active.
- Persists the last Agent ruleset across Agent restarts. If state persistence fails after an apply, the Agent immediately restores the previous kernel rules. The panel retains the last genuinely successful per-rule configuration for one-click rollback and does not release a deleted rule's port until the Agent confirms removal.
- Reconciles pending or drifted rules after an Agent reconnect, collects packet and byte counters, and keeps unsupported non-Linux Agents explicit. Existing features retain their previous Agent requirements; only nodes selected for nftables forwarding require Agent `2.43.1` and a locally installed `nft` command.
- Fixes first-time application on nodes where the managed `cloudnest_nat` table does not exist yet. The Agent now distinguishes that expected state from command execution failures without hiding nftables diagnostics.
- Validation includes the full 136-test backend suite, the complete Go Agent suite, Linux amd64/arm64 test compilation, frontend production build, and an isolated Linux network-namespace test covering bidirectional TCP/UDP forwarding, counters, atomic target replacement, and deletion.

### Upgrade and rollback impact

- The panel and Agent move to `2.43.1`. Existing nodes, tunnels, GOST forwards, private proxies, port pools, DNS records, certificates, and active services are not migrated, rebuilt, or restarted by the new feature.
- The database change only adds `nft_forward_rule` and `nft_forward_event`. Older panel versions ignore these additive tables.
- Stop only this feature by pausing or deleting its rules. Roll back an individual edited rule from its event row. Before rolling the complete panel back, delete nftables rules through the `2.43.1` page so the Agent confirms their removal; then run `sudo /usr/local/sbin/flux-panel-manager rollback`. Agent self-update keeps its existing atomic binary backup, reconnect acknowledgement, and automatic binary rollback.

## 2.42.4 Port-grouped route selectors

- Groups TCP route choices by public entry port in Three-network Optimization, Cross-entry Failover, and Source-IP Entry.
- Sorts port groups numerically, sorts routes within each group by node and route name, and includes the port in search text.
- Frontend-only release. Agent and Connector remain `2.42.3`; existing nodes, tunnels, forwards, ports, DNS records, certificates, and proxy services are unchanged.

## 2.42.3 Reliable Agent self-update fallback

- Falls back to a detached helper when `systemd-run` is present but cannot create the transient unit.
- Preserves the `systemd-run` failure detail and reports a combined error only when the detached fallback also fails.
- Keeps the existing atomic replacement, reconnect acknowledgement, and automatic rollback flow unchanged.

## 2.42.2 Source-IP entry routing

- Add a single TCP ingress that selects an existing backend forward by the real client source IP.
- Support longest-prefix IPv4/IPv6 CIDR matching, carrier databases, custom CIDRs, and default/health fallback.
- Require Agent 2.42.2 for the new ingress feature; existing resources are not changed by upgrade.

## 2.42.1 Carrier DNS convergence and diagnostics

- Keeps single-domain carrier routing while stopping unchanged DNSPod and Aliyun line records from being rewritten on every five-second health check. DNS writes now occur only after a route or TTL change, a missing record, or a retryable synchronization failure, with a minimum 60-second failure backoff.
- Reads every managed carrier record back from the DNS provider after a change and records its address, TTL, enabled state, duplicate/missing state, verification time, and failure detail. Aliyun carrier policies are normalized to its real 600-second minimum TTL instead of displaying an ineffective 60-second value.
- Adds an administrator-triggered DNS diagnosis to Three-network Optimization. It compares the configured entry, provider record, and public DNS answer for default, China Telecom, China Unicom, and China Mobile using ECS probes, and distinguishes provider mismatch, public-cache convergence, and probe failure.
- Detects an unmanaged same-name A or AAAA record that can cause IPv6-capable clients to bypass the intended carrier line. A missing sibling record is treated as normal, including DNSPod's no-record API response.
- This is a panel-only release. Agent and Connector remain `2.42.0`; existing nodes, tunnels, forwards, proxies, ports, certificates, and DNS records are not deleted or rebuilt. The schema change only adds DNS synchronization state to Smart Entry routes. Panel rollback remains `sudo /usr/local/sbin/flux-panel-manager rollback`.

## 2.42.0 Production runtime safeguards

- Sizes the backend JVM automatically from host memory and migrates the known-unsafe `256 MB + SerialGC` configuration during upgrades after creating a timestamped environment backup. New and migrated configurations use `ExitOnOutOfMemoryError` so Docker can recover a failed JVM instead of leaving an unresponsive container running.
- Adds a database-aware `/health/ready` probe and sustained-failure recovery. A new JVM receives a 120-second startup grace period; after that, five consecutive failures terminate PID 1 so `restart: unless-stopped` can recover it. Failure state is bound to the current process and cannot create a restart loop across container restarts.
- Refuses install, update, and rollback operations below 2 GB free disk and warns at 85% usage. Status and uninstall remain available during low-disk incidents. The full system self-check now reports panel disk pressure and JVM heap pressure with actionable guidance.
- Rejects a simultaneous Agent connection when the same node secret is used by another machine. Agent `2.42.0` sends a stable machine fingerprint; the registered node address wins after a backend restart, reconnect races cannot mark a live replacement offline, and recent conflicts appear in monitoring and the full system self-check. Legacy Agents remain compatible but fall back to public-IP comparison until upgraded.
- Stops writing Agent handshake query strings to Agent and Nginx access logs. Retires the unsupported `1.4.x` installer and its IPv4/IPv6 Compose files so all new installations use the tested installer, migration, readiness, and rollback path.
- Adds release gates for unsafe JVM settings, shallow health probes, startup-grace behavior, sustained-failure recovery, installer disk handling, duplicate Agent identity, real MySQL readiness, container startup without restarts, Compose parsing, and runtime image contents. The backend passed 126 tests, the complete Go Agent suite, installer/rollback tests, and an isolated Linux ARM64 Docker smoke test with database failure and recovery.
- The upgrade does not delete or rebuild nodes, tunnels, forwards, proxies, ports, DNS records, certificates, or database volumes. Panel rollback remains `sudo /usr/local/sbin/flux-panel-manager rollback`; Agent upgrades retain their existing binary backup and reconnect-confirmed rollback behavior.

## 2.41.9 Faster route transitions and shared read caching

- Keeps the existing navigation shell visible while a lazy page chunk is loading, so a slow first download no longer replaces the whole page with a blank loading screen.
- Prefetches the selected business page when an administrator hovers or focuses its menu item, and does the same for the mobile tab bar.
- De-duplicates concurrent site configuration and unread-alert requests across layout mounts. Alert refresh events still invalidate the short-lived cache immediately.
- Adds short-lived caching for forward and monitoring read lists. Forward mutations, monitoring read actions, and reorder operations invalidate the related cache immediately.
- Frontend-only performance release. Agent and Connector remain `2.41.4`; existing nodes, tunnels, forwards, proxies, DNS records, certificates, and port resources are unchanged. Panel rollback remains `sudo /usr/local/sbin/flux-panel-manager rollback`.

## 2.41.8 Slow API ranking and progressive page loading

- Adds an administrator-only **接口性能** view under the monitoring center. Recent API samples are kept in a bounded 15-minute in-memory window and ranked by P95 latency, with request count, HTTP errors, slow requests, average/P50/P95/max latency, last status, timestamp, and request ID.
- Loads Dashboard core package data first, then fills shared nodes, grants, monitoring, alerts, forwards, and users independently. A slow auxiliary endpoint no longer prevents the dashboard from rendering.
- Loads forward, tunnel, and private proxy lists independently from their node or tunnel selectors, so the current list becomes visible without waiting for form options.
- Splits Internal Service Publishing into independent service, domain route, certificate, connector, DNS, node, and port-pool loading states. Existing list content remains visible during refresh and forms disable only selectors whose options are still loading.
- Adds two registry tests for percentile ranking and rolling-window expiry. Frontend production build and all 119 backend tests pass. This is a panel-only release; Agent and Connector remain `2.41.4`, and existing resources are unchanged. Panel rollback remains `sudo /usr/local/sbin/flux-panel-manager rollback`.

## 2.41.7 Route-level loading and shared request de-duplication

- Loads each business page on demand. Terminal, topology, charts, user management, and other unrelated page code are no longer downloaded and parsed whenever a normal page opens.
- Restores production tree-shaking and minification. The initial JavaScript entry drops from about 3.64 MB (775 KB gzip) to about 835 KB (258 KB gzip), while ordinary route chunks are generally 2-18 KB gzip.
- De-duplicates concurrent reads and keeps an 8-second per-user cache for shared node, tunnel, Connector, port pool, proxy, service, domain route, and Dynamic DNS lists. DNS zone choices use a 30-second cache.
- Successful create, update, delete, sync, pause, resume, refresh, and status-check operations invalidate their related cache immediately, so the faster page transitions do not leave stale edited data visible.
- Frontend production build and all 117 backend tests pass. This is a panel-only release; Agent and Connector remain `2.41.4`, and existing resources are unchanged. Panel rollback remains `sudo /usr/local/sbin/flux-panel-manager rollback`.

## 2.41.6 Faster page loading and API diagnostics

- The Home Access page loads only its current routes on first paint. Node, tunnel, port pool, connector, and Dynamic DNS choices load when the create dialog opens; one failed option request no longer blocks the whole form.
- Route refreshes keep the current content visible instead of replacing it with a full-page spinner. The summary uses the loaded route data and remains available while background refresh runs.
- Home proxy list enrichment now batch-loads users, connectors, pools, tunnels, gateways, and nodes instead of issuing repeated per-route queries.
- Adds API request IDs, duration and upstream status fields to application and Nginx logs. Slow API requests are warned at 1 second by default, and unhandled exceptions include a full stack trace for diagnosing 5xx responses.
- This is a panel-only release. Agent and Connector remain `2.41.4`; existing nodes, tunnels, forwards, proxies, DNS records, certificates, and port resources are unchanged. Panel rollback remains `sudo /usr/local/sbin/flux-panel-manager rollback`.

## 2.41.5 Reliable online Agent upgrades and large self-check responses

- Raises the Spring WebSocket session text and binary receive limits to 4 MiB. A new embedded-Tomcat integration test sends and receives a 3 MiB message so production-sized Connector self-check responses are covered before release, not only mocked in a unit test.
- Routes existing Agent `2.8.0` through `2.41.4` upgrades through the rollback-protected remote terminal installer. This avoids the `2.41.2` self-updater path that could time out while reading GitHub release metadata even when an interactive `curl` later succeeded.
- Keeps Agent and Connector target version `2.41.4`; this is a panel `2.41.5` release and does not require another Connector upgrade. Each Agent task retains atomic replacement, a 45-second reconnect acknowledgment, and automatic restoration of the previous binary.
- Adds two administrator-selectable batch modes. **Parallel all** dispatches every eligible online node without stopping the remaining nodes when one fails; **Safe staged** keeps the previous one-at-a-time canary behavior. Both modes preserve per-node rollback and show per-node results. Failed batches remain visible for individual retry.
- Associates timeout guidance with the exact task-specific `/var/log/flux-agent-update-<task>.log` instead of a wildcard that could point to an older successful installation log.
- The schema change only adds a defaulted `mode` column to Agent batch-upgrade metadata. It does not rewrite nodes, secrets, ports, tunnels, forwards, DNS records, certificates, proxies, or active traffic. Panel rollback remains `sudo /usr/local/sbin/flux-panel-manager rollback`.

## 2.41.4 Connector self-check message size fix

- Raises the Connector WebSocket incoming message limit to 4 MiB so large self-check requests containing many registered entry targets are processed instead of being closed with WebSocket code 1009.
- Deduplicates Connector reachability targets by host and port before sending the self-check command, so the same endpoint is tested once even when several resources share it.
- The check remains read-only. Existing resources and the `2.41.3` release remain available for rollback.

## 2.41.3 macOS Connector self-check timeout fix

- Skips the expensive system-wide socket enumeration when a Connector self-check does not request local listener inspection. This prevents some macOS Connectors from exceeding the panel's 45-second response deadline before DNS, IPv4/IPv6, default-route, and entry-port results can be returned.
- Adds a three-second hard timeout to local route commands so an unresponsive operating-system utility cannot block the entire check. The check remains read-only and does not alter DNS, routes, firewall rules, interfaces, or existing services.
- Connector `2.41.3` is required for the corrected local check. Panel and Agent rollback remain available; existing nodes, tunnels, forwards, ports, domains, certificates, proxies, and Connector configuration are not rebuilt.

## 2.41.2 Local device and Connector self-check

- Adds a separate self-check scope for registered Windows, macOS, and Linux Connectors. The Connector reports local interfaces, IPv4/IPv6 outbound capability, system DNS, default routes, and TCP reachability to the panel and the panel's registered entry ports.
- Findings identify the observed segment (local machine, route, network path, or entry listener) and do not claim a specific router brand or carrier from a single timeout. Missing IPv6 is skipped. Checks are read-only and do not scan LANs, change firewall or routes, or rebuild existing resources.
- Connector `2.41.2` is required for the full local check. Older Connectors remain compatible with existing services but show an upgrade warning. The schema only adds nullable run-scope fields; panel rollback remains `sudo /usr/local/sbin/flux-panel-manager rollback`.

## 2.41.1 Terminal bootstrap marker fix

- Fixes online upgrades from Agent `2.8.0` through `2.40.x` being marked failed before the detached helper started. Interactive terminals echo the submitted bootstrap command, and the old command contained the literal failure marker; the backend could mistake that echo for an executed failure result and close the session early.
- Bootstrap result markers are now assembled only when the command reaches the corresponding execution branch, so terminal echo cannot trigger a false failure. Agent target version remains `2.41.0`; existing nodes, services, DNS, certificates, ports, tunnels, forwards, and proxy configuration are unchanged.

## 2.41.0 System self-check and safe staged Agent upgrades

- Adds an admin-only **System Self-Check** center under System Management. A manual run checks Agent connectivity and identity baselines, system DNS versus public DoH, IPv4/IPv6 capability, the port ledger versus real listeners, tunnel/forward/domain/certificate dependency chains, DDNS failures, and multi-entry routes that still converge on one exit endpoint. Missing IPv6 is reported as skipped instead of failed, and router-specific causes are never asserted without evidence.
- Agent `2.41.0` adds the fixed, read-only `SystemSelfCheck` command. It returns masked identity fingerprints, DNS resolvers and A/AAAA results, protocol-family routes, and requested listener state without exposing secrets or accepting arbitrary shell commands.
- Agent upgrades now require a real panel reconnect within 45 seconds. Both online and manual updates preserve the old binary, switch atomically, and automatically restore and restart the previous version when the candidate cannot reconnect. Batch upgrades are staged: one canary node first, then one node at a time; any failure pauses all remaining nodes. Installing over an existing different Agent identity is rejected unless the operator explicitly adds `-R`, preventing a copied command from silently replacing another node's secret.
- The schema initializer only creates self-check history, identity-baseline, and staged-upgrade metadata. It does not delete or rewrite nodes, tunnels, forwards, ports, DNS records, certificates, or proxy services. Panel rollback remains `sudo /usr/local/sbin/flux-panel-manager rollback`. Agent failures automatically restore the previous Agent binary; a manually initiated Agent update also prints the rollback outcome.

## 2.40.1 Restore forward traffic reporting

- Restores traffic processing for ordinary forward services whose Agent runtime names include the current `_tcp` or `_udp` transport suffix. A strict parser introduced with service telemetry accepted only the older unsuffixed form, so valid reports were received but silently excluded from forward totals, user/resource quota accounting, and Smart Entry live activity.
- Accepts only the legacy `<forward>_<user>_<grant>` form and the explicit `_tcp`/`_udp` variants. Published services, private proxies, and malformed runtime names remain excluded from ordinary forward accounting.
- TCP reports update Smart Entry cumulative and current connection counters. UDP reports add traffic bytes without replacing the TCP connection baseline, preventing the two protocol runtimes from corrupting one shared connection count.
- This is a backend-only compatibility fix. It does not modify the database, rebuild services, restart Agents, change ports, or alter existing nodes, tunnels, forwards, DNS, certificates, proxies, and quotas. Agent `2.40.0` remains current and does not need another upgrade. Roll back with `sudo /usr/local/sbin/flux-panel-manager rollback` if needed.

## 2.40.0 Managed panel domains in Website Settings

- Reorganizes **Website Settings** into three explicit areas: browser-facing panel access, Agent communication, and branding/login. The Agent `IP:port` endpoint remains independent from the browser domain so changing or adding a website address cannot silently disconnect nodes.
- Connects Website Settings to existing Cloudflare zones, online nodes, managed HTTPS domain routes, and Let's Encrypt certificates. Administrators can select a registered zone and HTTPS ingress node to create a new `https://panel.example.com` address without re-entering DNS credentials.
- Shows the current browser origin, preferred panel URL, managed ingress count, DNS/certificate/deployment state, ingress node, backend target, certificate expiry, and actionable failure detail on the same page. Existing compatible panel routes are detected and can be selected as the preferred address.
- Creation is additive and explicit: upgrading alone does not create, delete, replace, or rebuild any DNS record, certificate, route, port, proxy, tunnel, forward, node, or mapping. The raw IP address remains available as a recovery path, and existing panel domains remain unchanged.
- Extends administrator-created user grants to all eight private-proxy protocols: SOCKS5, HTTP, Shadowsocks, VLESS+REALITY, Trojan, Hysteria2, TUIC v5, and WireGuard. All grants enforce bidirectional traffic allowance, reset day, permanent/expiry time, and per-instance suspension when exhausted or expired.
- Adds private cumulative traffic accounting for Trojan, Hysteria2, and TUIC through a loopback-only Sing-box controller, and for WireGuard through its runtime peer counters. Counter baselines survive panel restarts and prevent historical traffic from being counted again after a monthly or manual reset.
- Advanced protocols intentionally do not expose or create a GOST rate limiter. Administrators see that Mbps limiting is unsupported for these protocols; ordinary users continue to see only renewal information such as allowance, used/remaining traffic, reset day, remaining time, expiry, and the reason a grant is unavailable.
- Agent `2.40.0` is required only when creating a new advanced-protocol user grant. Existing standalone advanced proxies remain compatible with Agent `2.38.0+` and are not recreated by the panel upgrade. The migration only adds two zero-default cumulative-counter columns to `private_proxy`; it does not delete or rewrite existing rows.
- Roll back the panel with `sudo /usr/local/sbin/flux-panel-manager rollback`. Managed domain routes remain compatible. Before rollback, delete any advanced-protocol user grants created on `2.40.0`, because an older panel does not poll or enforce their traffic allowance; existing unrelated proxies, ports, nodes, tunnels, and forwards are unaffected.

## 2.39.2 Hide internal limiter policy from ordinary users

- Removes the administrator-configured Mbps value from ordinary-user proxy cards and private-proxy details while retaining traffic allowance, used and remaining traffic, reset day, grant duration, expiry, and forward quota information needed for renewal decisions.
- Removes tunnel limiter identifiers, preset names, and speeds from the ordinary-user package response, and masks proxy limiter fields in ordinary-user proxy API responses. Administrators retain the full policy fields and controls.
- Includes the `2.39.1` custom REALITY camouflage-domain selector. This panel-only update does not change limiter enforcement, restart Agents, rebuild proxies, alter ports, or modify the database. Roll back with `sudo /usr/local/sbin/flux-panel-manager rollback` if needed.

## 2.39.1 Custom REALITY camouflage for user grants

- Adds the missing **Custom domain** option to administrator-created VLESS+REALITY user grants, matching the standalone private-proxy form.
- Shows a dedicated custom-domain field and rejects empty or malformed values containing a scheme, port, or path before submission; backend validation remains authoritative.
- This is a frontend-only hotfix. It does not change the database, Agent, ports, proxy runtimes, nodes, tunnels, forwards, or the existing test instances. Roll back with `sudo /usr/local/sbin/flux-panel-manager rollback` if needed.

## 2.37.1 Reliable forwarded-route port allocation

- When a new forward uses automatic port allocation, the panel now asks a current Agent whether the candidate port is already occupied by another local process before choosing it.
- This applies to the public entry listener and the internally allocated hop ports used by multi-hop and multi-line tunnel routes. An occupied port is skipped automatically instead of failing the entire deployment after selection.
- Existing forwards, tunnel paths, port allocations, DNS, domain routes, mappings, certificates, and Agent versions are unchanged. Roll back with `sudo /usr/local/sbin/flux-panel-manager rollback` if required.

## 2.36.3 Port reuse overview

- Adds a dedicated **Port reuse** overview to Domain Direct.
- Groups domain rules by the same public ingress node, listening port, and ingress mode, so users can immediately see which domains share one public IP and port.
- Shows the shared ingress address, HTTPS mode, domain count, healthy/pending/abnormal counts, backend service, current connections, and live traffic for each rule.
- Sorts abnormal groups after healthy groups while keeping the existing per-rule actions available in a collapsed **All domain rules** section.
- This is a frontend-only presentation enhancement. It does not allocate ports, modify DNS, rebuild HTTPS listeners, change the database schema, or upgrade Agents.
- Existing forwarding, tunnel, mapping, DNS, certificate, and service configurations are unchanged. Roll back with `sudo /usr/local/sbin/flux-panel-manager rollback` if needed.

## 2.36.2 Quality target validation

- Explains common probe failures in plain language. In particular, `connection refused` now states that the target port has no listener or is actively rejected by its firewall, instead of looking like an Agent failure.
- Renames the Agent-to-Agent field to **Target open port** and makes it explicit that an online Agent does not imply that arbitrary inbound ports such as `443` are open.
- Adds an administrator-triggered listener lookup for a selected target node. It reads that node's existing TCP listener table and does not open ports or scan remote port ranges.
- Adds a one-sample TCP preflight from the selected source Agent before a task is saved. A failed preflight is shown inline; administrators can still explicitly save it as an outage-monitoring task.
- This is a panel-only release. Agent `2.36.0`, existing quality tasks and samples, nodes, tunnels, forwards, DNS records, ports, and services are unchanged.
- Roll back with `sudo /usr/local/sbin/flux-panel-manager rollback` if needed.

## 2.36.1 Mobile layout correction

- Keeps the Network Quality Lab title, description, refresh action, and create action inside narrow phone viewports.
- Uses a stable two-column mobile action row and responsive title size so no header text or button label is clipped.
- This is a panel-only layout follow-up. Agent `2.36.0`, quality samples, nodes, tunnels, forwards, DNS, ports, and active services are unchanged.
- Roll back with `sudo /usr/local/sbin/flux-panel-manager rollback` if needed.

## 2.36.0 Network Quality Lab

- Adds an administrator-only Network Quality Lab using existing Agents to measure DNS resolution, TCP connection, TLS handshake, HTTP(S) TTFB, P50/P95/P99 latency, successive-sample jitter, failure rate, and full-run interruptions.
- Supports IPv4-only, IPv6-only, and automatic address-family probes; custom targets and Agent-to-Agent tests; 24-hour, 7-day, and 30-day trends; source-line and hour-of-day comparison; and downloadable Markdown reports.
- Uses the source node's Server Asset line label for comparison. This is an existing-node viewpoint and is not presented as a real residential China Telecom, Unicom, or Mobile probe.
- Keeps every task disabled by default, limits a round to 10 samples, enforces a five-minute minimum schedule, runs at most two probes globally, does not queue work beyond those slots, and automatically releases a stale running task after a restart or timeout.
- Adds strict Agent-side validation and one fixed `QualityProbe` command. It does not execute arbitrary shell commands, scan port ranges, install packages, or change failover and forwarding state.

### Upgrade and rollback impact

- Panel and Agent move to `2.36.0`; only nodes selected as probe sources need the new Agent. Older Agents continue all existing tunnels, forwards, proxies, mappings, DNS, certificates, and terminal sessions.
- Adds only `quality_probe_task`, `quality_probe_run`, and `quality_probe_sample`. Existing business tables and Agent runtime configurations are not rewritten.
- No probe traffic exists until an administrator clicks **Run now** or explicitly enables a task. The Agent gains no additional resident process.
- To stop only the feature, pause or delete its tasks. To roll back the complete panel release, run `sudo /usr/local/sbin/flux-panel-manager rollback`. Previous panels ignore the additive `quality_*` tables, which remain available if `2.36.0` is installed again.

## 2.34.5 Compressed XUI root-path rewrite correction

- Requests an uncompressed backend response when a managed HTTPS route rewrites the backend root path, allowing HTML, CSS, JavaScript, and JSON body paths to be rewritten reliably for normal browsers that advertise gzip support.
- Fixes XUI domain routes that returned HTTP 200 but rendered a blank page because their compressed HTML still referenced the private backend root path.
- Existing domains, DNS records, certificates, tunnels, forwards, mappings, and XUI configuration remain unchanged.

## 2.34.4 Agent release alignment and health cleanup

- Aligns the panel and Agent release versions so the self-updater downloads Agent `2.34.4` from Release `2.34.4`, preventing a valid update from being rejected after resolving an older same-named Agent release.
- Explicitly clears a domain route's previous health error when a later health check succeeds.
- Includes the HTTPS-first node service discovery and editable direct-domain backend correction introduced in `2.34.3`.
- Existing tunnels, forwards, mappings, domain records, certificates, listeners, and node configuration remain unchanged.

## 2.34.3 HTTPS service discovery correction

- Probes HTTPS before HTTP during manual node service discovery, preventing TLS-enabled XUI panels from being misidentified when their HTTP listener returns a redirect or a generic error.
- Rejects malformed `HTTP/0.0` responses instead of publishing them as valid HTTP services.
- Adds an **Edit backend** action to direct domain routes. Administrators can correct the backend protocol, listener address, port, and root path without deleting the domain, DNS record, or HTTPS certificate.
- Reapplies the existing managed HTTPS listener after an edit and rolls the database transaction back when deployment fails.
- Panel `2.34.3` targets Agent `2.34.1`. Existing tunnels, forwards, mappings, DNS records, certificates, and listeners remain unchanged until an operator edits a route or manually scans a node.

## 2.34.2 One-click publish selection fix

- Keeps the discovered service node preselected while the Domain Direct page loads its node options asynchronously.
- Removes transient invalid-selection warnings and prevents an empty node selector from flashing on slower connections.
- This is a frontend-only follow-up. It does not change the database, Agent, nodes, tunnels, forwards, mappings, DNS records, certificates, or active listeners.
- Roll back with `sudo /usr/local/sbin/flux-panel-manager rollback` if needed. Agent `2.34.0` remains current and does not need another upgrade.

## 2.34.1 Runtime image artifact fix

- Fixes the runtime Docker build contexts so the backend image contains the JAR built by the current workflow and the frontend image contains the current Vite `dist` output.
- Adds a release gate that starts both published images and verifies the node-discovery backend class and the new discovery UI are physically present before a GitHub Release can be created.
- Replaces the incorrectly packaged `2.34.0` panel images. Agent `2.34.0` assets were built independently and remain valid; no second Agent upgrade is required.
- Does not change database schema, ports, nodes, tunnels, forwards, mappings, DNS records, certificates, or Agent configuration. Updating from the mislabeled image only replaces the backend and frontend containers, with the existing manager rollback still available.

## 2.34.0 Node service discovery and one-click publishing

- Adds an administrator-only **Discover services** action to every node. Discovery runs only after a manual click and reads the operating system's active TCP listener table; it never scans all 65,535 ports in the background.
- Identifies local HTTP/HTTPS services, process metadata, response title/status/latency, and common products such as XUI, Grafana, Portainer, Nginx, OpenWrt, and Home Assistant.
- Reads `/var/run/docker.sock` when available and labels published TCP ports with the Docker container name, image, and short ID. Nodes without Docker keep the same discovery flow without Docker metadata.
- Publishes a discovered Web listener directly through the existing managed HTTPS entry, without first allocating a second public mapping port. The form remains editable before confirmation.
- Adds independent external and backend root paths. For example, `/` can proxy to an XUI backend at `/abc123`, including request URL, redirect `Location`, cookie `Path`, and common uncompressed text response rewrites.
- Adds route health state, HTTP status, latency, last check time, and concise failure details to each Domain Direct card. Checks run once per minute and never stop or delete an unhealthy route.
- Preserves mapping-backed Domain Direct routes, certificates, nodes, tunnels, forwards, internal mappings, and port allocations. New direct routes require Agent `2.34.0` on the HTTPS entry node.

### Upgrade and rollback impact

- The database upgrade only makes `domain_route.published_service_id` nullable and adds nullable/defaulted direct-backend and health columns. Startup migration is idempotent and does not rewrite existing rows.
- Manual discovery briefly probes only services that are already listening on the selected node. There is no idle CPU, memory, network, or database workload from discovery.
- Health checking adds at most 20 short HTTP/TCP checks per minute from the panel. Docker discovery reads metadata only and does not start, stop, or modify containers.
- Before rolling back, delete direct node-service routes created on `2.34.0` and wait for their cards to disappear. Then run `sudo /usr/local/sbin/flux-panel-manager rollback`. Existing mapping-backed routes can remain.

## 2.33.2 Reliable DNS-01 propagation checks

- Wait for both Cloudflare and Google public DNS to read the ACME TXT value in two consecutive rounds before triggering Let's Encrypt validation.
- Show a dedicated DNS propagation state and separate permission, propagation-timeout, and certificate-authority failures.
- Resume interrupted issuing, renewal, and DNS propagation work after a panel restart.
- Preserve all active certificates and domain routes during the upgrade.

## 2.33.1 Fix LAN service discovery on MySQL 8

- Escape the `sensitive` discovery-result column for MySQL 8 compatibility.
- Keep existing discovery data and all other panel resources unchanged.

## 2.33.0 Domain Direct and independent HTTPS ingress

- Renames the Internal Publishing domain workflow to **Domain Direct** and adds a **Bind domain** action directly to every active mapping card.
- Lets administrators select a different online node as the standard HTTPS ingress when port `443` is already occupied on the mapping server.
- Keeps same-server routes on loopback and sends only cross-node routes to the mapping's published address, so existing routes preserve their current path.
- Shows the DNS ingress address separately from the backend mapping address and adds a direct HTTPS link to each active rule.
- Continues to use the existing Agent-managed TLS listener, Cloudflare DNS-01 certificate issuance, automatic renewal, and path routing. No Caddy, Nginx, new database table, or additional resident process is introduced.

### Upgrade and rollback impact

- This is a panel-only release. Existing mappings, domain routes, certificates, nodes, tunnels, forwards, and Agents are not rewritten or restarted.
- Cross-node ingress adds one network hop from the selected HTTPS entry node to the existing public mapping. The original mapping remains reachable and can be used as a fallback.
- Before rolling back, delete cross-node Domain Direct rules created with this release. Then run `sudo /usr/local/sbin/flux-panel-manager rollback`.

## 2.32.2 Home Device record deletion

- Adds a clearly labeled trash action and confirmation dialog to each Home Device card so stale offline records can be removed without confusing it with the Agent uninstall command.
- Blocks deletion while a Home Device is online and preserves the existing dependency checks for internal mappings and home-network relay routes.
- Fixes a deletion deadlock where an offline Agent left a removed home-network relay in `delete_pending`: the relay disappeared from the page but still prevented the stale Home Device record from being deleted.

## 2.32.1 database and macOS installer hotfix

- Restores existing Home Devices that appeared missing after `2.32.0` when the new discovery candidate table was not created. Connector records were never deleted; the list endpoint now remains available even if candidate storage is temporarily unavailable.
- Moves LAN discovery columns and candidate-table creation into an independent, idempotent schema initializer so unrelated historical service-publishing migrations cannot skip it.
- Makes the macOS LaunchDaemon upgrade wait for asynchronous `bootout`, retry `bootstrap`, verify the Agent is actually running, and reliably restart the previous binary and configuration if the new version cannot start.

## 2.32.0 local network service discovery

- Adds an opt-in **Discover services** workflow to each Home Device. Discovery is disabled by default and never starts automatically after an update.
- Lets the Agent detect up to two active private `/24` networks, or scan one explicitly authorized private IPv4 CIDR. Public networks and ranges larger than `/24` are rejected by both the panel and Agent.
- Uses bounded TCP probes to identify common Web/HTTPS, NAS, router, SSH, RDP, SMB, FTP, Telnet, RTSP, MQTT, MySQL, PostgreSQL, Home Assistant, and Plex services. It does not attempt authentication, read device data, scan UDP broadcasts, or inspect the public Internet.
- Stores results as review-only candidates with endpoint, product/title metadata, confidence, and a sensitive-service warning. Scanning never reserves a public port or publishes a service.
- Opens the existing Internal Publishing form with the connector, address, port, name, and matching service template prefilled. The operator must still select an authorized Port Resource, choose a lease, and confirm creation.
- Prevents concurrent scans per connector, expires stuck scan state, caps Agent work at 513 hosts and 32 ports, and revalidates every returned endpoint against the connector's allowed CIDRs.

### Upgrade and rollback impact

- Service discovery requires the selected Home Device to run Agent `2.32.0` or newer. Older Agents continue running all existing mappings, Home Network Relay routes, NAT sessions, nodes, tunnels, and forwards.
- Adds opt-in discovery state columns to `internal_connector` and the independent `lan_discovered_service` candidate table. Existing records and listeners are not rewritten.
- There is no steady resource increase while discovery is disabled or idle. A manually started scan briefly opens bounded local TCP probes and stops after about 22 seconds at most.
- Rollback does not require deleting candidates because the previous panel ignores the additive schema. Run `sudo /usr/local/sbin/flux-panel-manager rollback`; existing published services remain active.

## 2.31.0 NAT traversal and relay fallback

- Adds **Smart Direct + Relay** to Home Network Relay. A company Agent and home Agent exchange IPv4/IPv6 ICE candidates through the existing authenticated panel WebSocket and attempt a UDP hole-punched QUIC path.
- Protects the direct data path with TLS 1.3, a per-session token, and an ephemeral certificate fingerprint. The company SOCKS5 listener binds only to `127.0.0.1`.
- Falls back to the existing public TCP relay when direct setup exceeds five seconds or a direct stream cannot be opened. The public ingress pool remains mandatory and continues using the global port ledger. Existing TCP connections are not migrated between paths; new connections use the current path.
- Shows the active path, inferred NAT type, direct success rate, direct/relay bidirectional traffic, last switch time, and the most recent path events. A bounded scheduler retries failed direct negotiation no more than once per minute.
- Persists the local relay listener on the company Agent so it remains usable after an Agent restart while the panel negotiates a new direct session.

### Upgrade and rollback impact

- Smart Direct requires both company and home connectors to run Agent `2.31.0` or newer. Browsers, phones, and ordinary SOCKS5 clients cannot perform hole punching by themselves; applications connect to the company device's local SOCKS5 address.
- Adds nullable NAT fields to `home_proxy_route` and the independent `home_proxy_nat_event` table. Existing nodes, tunnels, forwards, port pools, direct Home Access routes, public relay routes, DNS records, and Agent services are not rewritten.
- Each active Smart Direct route adds one ICE/QUIC session and a small keepalive load to both endpoint Agents. Business traffic bypasses the panel backend and MySQL. Symmetric NAT, blocked UDP, and restrictive carrier networks can still require the public relay.
- Before rollback, delete routes created with **Smart Direct + Relay** and wait for their cards to disappear. Then run `sudo /usr/local/sbin/flux-panel-manager rollback`. The previous panel ignores the additive schema but cannot manage Agent-side NAT listeners left behind.

## 2.30.1 DNS provider domain selection

- Replaces the manual root-domain field in Smart Entry with a domain selector linked to the selected DNS provider account.
- Reads all manageable root domains from DNSPod or Aliyun DNS, with pagination, normalization, duplicate removal, and alphabetical sorting.
- Reloads domains when the provider changes, auto-selects a sole domain, preserves an existing policy's domain while editing, and provides an explicit retry action for provider API errors.
- Domain discovery is read-only: it does not create, update, or delete any DNS record.

### Upgrade and rollback impact

- This panel-only update does not change the database or Agent runtime. Nodes and active routes continue running without an Agent update or restart.
- Roll back with `sudo /usr/local/sbin/flux-panel-manager rollback` if needed.

## 2.30.0 Selectable server egress and Reality home first hop

- Renames single-VPS egress to **Selected Server Egress** and lets an operator choose any online node they own or have been granted. An egress Port Resource pool is no longer required.
- Keeps SOCKS5 as a selectable, lightweight home-to-egress protocol. Existing Home Access routes remain on their current standard TCP/SOCKS5 runtime and are not rebuilt.
- Adds VLESS + REALITY as an optional home-to-first-egress protocol. The company-to-home endpoint remains SOCKS5; only the home-to-overseas first hop uses Reality.
- Supports both protocols with a selected server or an existing multi-node tunnel. Reality terminates on the tunnel's first node and the remaining nodes retain their ordered authenticated SOCKS5 gateways.
- Adds a local-only Xray client runtime to Linux, Windows, and macOS Agents. The local SOCKS listener binds to `127.0.0.1`; Xray is downloaded on demand from XTLS, SHA-256 verified, persisted, and restored after restart.
- Automatically allocates every server-side port from the node range under the global ledger lock, enforces shared-node/tunnel quotas, checks real listeners, and cleans both Xray runtimes, frontends, chains, gateways, leases, and database rows after partial failure.

### Upgrade and rollback impact

- Panel and Agent move to `2.30.0`. New Reality routes require the home connector on `2.30.0`; the first public node must support the existing Reality server runtime.
- Adds nullable `home_proxy_route.egress_node_id` and `reality_server_name`, additive transport/type columns, and one index. No existing route, node, tunnel, forward, pool, DNS record, certificate, or listener is rewritten.
- Each active Reality route adds one Xray process on the home device and one on the first public node, plus about 20-30 MB of Xray files per device. The panel server itself gains no traffic-plane process or meaningful steady resource requirement.
- Before rolling back, delete Reality Home Access routes created on `2.30.0` and wait for cleanup to finish. Then run `sudo /usr/local/sbin/flux-panel-manager rollback`. SOCKS5 and pre-existing routes can remain running.

## 2.29.0 In-panel operations guide

- Adds a searchable in-panel guide that explains every main module by purpose, prerequisites, operating steps, expected result, and common mistakes.
- Adds a four-step first-run path for Node, Tunnel, Forward, and Monitoring, plus a concise diagram showing how nodes, tunnels, forwards, domains, and failover relate.
- Documents Smart Entry and Cross-entry Failover separately, including DNS provider requirements, switching behavior, cache delays, existing-connection behavior, and when each feature should be used.
- Covers Port Resources, Home Devices, Domain Management, Dynamic DNS, Internal Publishing, Home Network Relay, Private Proxy, Network Diagnostics, Topology, Alerts, Limits, Server Assets, Users, Site Settings, and Updates.
- Adds the guide as the final desktop sidebar group and as a mobile Profile shortcut. Administrator-only operations remain clearly marked and cannot be opened by a normal user.
- Changes no database schema, Agent runtime, node, tunnel, forward, port reservation, DNS record, certificate, proxy, published service, or active Home Network Relay. Agent `2.26.4` remains the current target.

### Upgrade and rollback impact

- This is a frontend-only documentation and navigation release. No Agent update or restart is required.
- Roll back with `sudo /usr/local/sbin/flux-panel-manager rollback` if needed. Existing configurations and running services remain intact.

## 2.28.2 Mobile Resource Center active-tab visibility

- Automatically scrolls the Resource Center tab row so the active section remains visible when a narrow-screen user opens Domain Management or Dynamic Resolution from a direct link.
- Preserves manual touch scrolling and the hidden-scrollbar treatment introduced in `2.28.1`.
- Changes no database schema, Agent runtime, port reservation, DNS record, tunnel, forward, proxy, published service, or active Home Network Relay. Agent `2.26.4` remains the current target.

### Upgrade and rollback impact

- This is a frontend-only navigation fix. Roll back with `sudo /usr/local/sbin/flux-panel-manager rollback` if needed.

## 2.28.1 Mobile Resource Center tab polish

- Hides the native horizontal scrollbar under Resource Center tabs on narrow screens while preserving touch and trackpad scrolling.
- Keeps the tab row width independent from page content so long labels do not create page-level horizontal overflow.
- Changes no database schema, Agent runtime, port reservation, DNS record, tunnel, forward, proxy, published service, or active Home Network Relay. Agent `2.26.4` remains the current target.

### Upgrade and rollback impact

- This is a frontend-only layout fix. Roll back with `sudo /usr/local/sbin/flux-panel-manager rollback` if needed.

## 2.28.0 Resource Center and navigation cleanup

- Reorganizes the sidebar into Core Business, Access and Publishing, Utilities, System Management, and Version Maintenance. Full-chain topology now belongs to System Management.
- Adds one Resource Center entry with responsive tabs for Port Resources, Home Devices, Domain Management, and Dynamic Resolution. Existing URLs remain valid, so saved links and internal navigation continue to work.
- Adds a dedicated Home Devices view for Linux, Windows, and macOS connectors, including online state, Agent version, remote address, allowed networks, and install/uninstall commands.
- Renames Home Access to Home Network Relay and keeps this page focused on creating and operating relay paths. Device enrollment now opens the centralized Home Devices view instead of duplicating the same forms.
- Unifies related labels and entry points across Smart Entry, Cross-entry Failover, and the administrator profile.
- Changes no database schema, Agent runtime, port reservation, DNS record, tunnel, forward, proxy, published service, or active Home Access route. Agent `2.26.4` remains the current target.

### Upgrade and rollback impact

- This is a panel-only information-architecture release. Updating does not modify existing resources or call any DNS provider API.
- Roll back with `sudo /usr/local/sbin/flux-panel-manager rollback` if needed. Existing configurations and running services remain intact.

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
## 2.37.0 Three-stage load balancing

- Adds real same-ingress L4 tunnel balancing: round robin, random, weighted random, and source-IP hash select a GOST chain for each new connection. Existing connections remain untouched.
- Adds managed HTTPS backend pools with health-based member removal/recovery, round/random/weighted selection, and optional source-IP affinity. Existing one-backend routes are migrated additively as their first pool member.
- Extends Cross-entry Failover with **Active-active DNS**. Healthy entries are published together as DNS-only A/AAAA records; unhealthy entries are removed after the configured failure threshold and restored after recovery.
- DNS active-active is explicitly resolver-level distribution, not strict connection-level weighting or Anycast. It affects fresh DNS resolution and new connections only; all-down groups retain their last DNS answer and are marked offline rather than publishing an empty set.

### Upgrade and rollback impact

- This is a panel-only release. Agent `2.36.0`, installed tunnels, forwards, nodes, ports, mappings, certificates, and existing DNS records are not restarted or rewritten.
- Adds `domain_route_backend` and `cross_entry_dns_record`, plus additive defaulted fields on `forward`, `domain_route`, and cross-entry tables. Normal startup performs the migration; [`migrations/20260731_load_balancing.sql`](../migrations/20260731_load_balancing.sql) is available for manual maintenance.
- To stop active-active DNS before returning to `2.36.3`, change each affected group back to **Primary/backup failover** in the new UI so the panel clears its extra DNS records. Then run `sudo /usr/local/sbin/flux-panel-manager rollback`.
## 2.38.0 Advanced private proxy protocols

- Adds grouped private-proxy creation for Trojan, Hysteria2, TUIC v5, and WireGuard while keeping SOCKS5, HTTP, Shadowsocks, and VLESS+REALITY unchanged.
- Trojan, Hysteria2, and TUIC run through a per-instance Sing-box runtime. The Agent validates the generated configuration before it starts the listener, stores runtime state and TLS material with owner-only permissions, and cleans the instance when creation fails.
- WireGuard uses the Agent's embedded userspace runtime on Linux. It creates only an instance-specific TUN interface and NAT/forward rules, then removes those exact resources on pause/delete. It does not clear existing firewall rules.
- New protocols require Linux Agent `2.38.0`; older Agents keep all existing forwarding and proxy services running normally. QUIC and WireGuard require the corresponding UDP port to be open in both the provider security group and node firewall.
- Advanced-protocol instances do not yet support source-IP allowlists or GOST per-service traffic counters. The UI states this at creation time rather than silently accepting an ineffective setting.
- Roll back safely by deleting the new advanced instances first and waiting for their cards to disappear, then run `sudo /usr/local/sbin/flux-panel-manager rollback`. Existing proxy types, nodes, tunnels, forwards, DNS, certificates, mappings, and user resources are not modified.

## 2.38.3 Private proxies grouped by node

- Groups all private proxies by their server node so operators can find a node first and expand only its protocols.
- Each collapsed node row shows connectivity, public address, protocol summary, active count, attention count, and total proxy count.
- Expanded rows retain connection export, pause, resume, delete, expiry, access control, and per-proxy error details.
- This is a panel-only presentation change. Agent `2.38.0`, listeners, ports, tunnels, forwards, proxy runtimes, and existing test instances are not restarted or rewritten.

## 2.39.0 Per-user tunnel limits and private proxy grants

- Reframes the standalone limiter page as **User limiter presets** and exposes the matching preset directly in each shared-tunnel quota editor. A selected limiter applies only to that ordinary user's forwarding services; administrator traffic on the same tunnel remains unlimited by that grant.
- Reloads only the affected user's existing forwards when an integrated user edit changes the tunnel limiter, so a saved limit takes effect immediately without rebuilding unrelated administrator or user services.
- Adds administrator-created, dedicated SOCKS5, HTTP, Shadowsocks, and VLESS+REALITY proxy grants for ordinary users with independent bidirectional traffic quota, monthly reset day, permanent or fixed expiry, and Mbps rate limit.
- Pauses only the granted proxy when its quota is exhausted or its authorization expires. Offline nodes retain the port and retry the required pause or deletion after reconnecting; extending the grant or resetting its flow resumes the same instance.
- Adds ordinary-user dashboard cards for remaining and used traffic, remaining time, expiry, reset schedule, speed, and a concise unavailable reason. Granted users can read connection details but cannot pause or delete administrator-managed instances.
- Keeps grant instances out of shared-node forward-slot and traffic-quota accounting because their dedicated grant is the governing quota. Deleting a user also removes their proxy instances or queues cleanup while a node is offline.

### Upgrade and rollback impact

- This is a panel-only release; Agent `2.38.0` remains current. Existing private proxies, test proxies, listeners, tunnels, forwards, users, and administrator traffic are not restarted or rewritten during upgrade.
- Adds defaulted/nullable grant columns and one index to `private_proxy`. Startup migration is idempotent, and [`migrations/20260731_private_proxy_grants.sql`](../migrations/20260731_private_proxy_grants.sql) is available for manual maintenance.
- Dedicated grants consume one real node port each. Traffic limiting is available only for GOST-metered SOCKS5, HTTP, Shadowsocks, and VLESS+REALITY; unsupported advanced protocols are rejected instead of receiving ineffective limits.
- Before rollback, delete grants created in `2.39.0` and wait for offline-node cleanup when possible. Then run `sudo /usr/local/sbin/flux-panel-manager rollback`; the previous panel ignores the additive columns but cannot manage grant quotas or their dedicated limiter objects.
## 2.42.5 Remove standalone user rate-limit presets

- Removes the standalone administrator navigation entry and page for “用户限速预设”.
- Keeps rate-limit configuration and authorization inside user management, so existing user entitlements and limits are unaffected.
- Keeps `/limit` as a compatibility redirect to `/user` for old bookmarks.
- Panel-only release. Agent and Connector remain `2.42.3`; existing nodes, tunnels, forwards, ports, DNS records, certificates, and proxy services are unchanged.
## 2.46.4

- 新增多线路聚合：一个公网入口按权重调度多条同入口隧道上的并发连接。
- 自动结合真实带宽、延迟、丢包、抖动和线路健康状态计算权重，支持速度、均衡和稳定三种模式。
- 支持自动健康摘除、五分钟自适应重算、暂停恢复、线路验证、事件历史和部署失败回退。
- 聚合底层转发由聚合模块托管，避免从普通转发页面误改造成配置脱节。
- 此版本不要求升级 Agent，现有 2.46.3 Agent 可直接使用。
## 2.46.5

- 多线路聚合新增“修复底层线路”，可在聚合页一键重新下发底层转发、重新部署入口并刷新验证状态。
- 聚合线路异常时直接显示失败段、疑似失败地址端口和 Agent 返回原因，避免手动去隧道页猜是哪台机器没监听。
- 修复动作沿用托管转发回退流程，新配置下发失败时保留原配置。
- 面板-only release，不要求升级 Agent，现有 2.46.3 Agent 可直接使用。
## 2.46.6

- 多线路聚合异常或降级时，“修复底层线路”改为可见文字按钮，避免只看到图标不知道该点哪里。
- 正常聚合组仍保留紧凑图标按钮，并补充 `aria-label` 方便浏览器和无障碍工具识别。
- 面板-only release，不要求升级 Agent，现有 2.46.3 Agent 可直接使用。
## 2.46.7

- 多线路聚合页统一显示“修复底层线路”文字按钮，健康组也不再只显示图标，避免用户不知道该如何重新部署底层线路。
- 保持原有修复逻辑、回退流程、故障段展示和历史事件不变。
- 面板-only release，不要求升级 Agent，现有 2.46.3 Agent 可直接使用。
## 2.46.8

- 多线路聚合修复底层线路时，如果 Agent 上残留同名 chain 或 hop 服务，会自动清理对应线路资源并重试一次，避免 `chain ... already exists` 卡住。
- 负载均衡回退时不再因为历史健康状态全是 unhealthy 而直接报“没有可用线路”；会先按 enabled 线路恢复服务，再交给健康检查刷新真实状态。
- 面板-only release，不要求升级 Agent，现有 2.46.3 Agent 可直接使用。
