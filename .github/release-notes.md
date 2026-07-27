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
