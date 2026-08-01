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
