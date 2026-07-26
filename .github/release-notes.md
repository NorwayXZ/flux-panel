## Cross-entry failover groups

- Adds independent cross-entry groups that bind existing forwards from different ingress nodes without changing same-ingress route candidates.
- Performs parallel node-presence and public TCP probes with a 2-second fast profile and two-failure confirmation.
- Updates a Cloudflare DNS-only A or AAAA record to the healthiest available ingress and records every successful or failed switch.
- Prevents route flapping with recovery confirmation, switch cooldown, and optional automatic failback.
- Encrypts the Cloudflare API token with AES-GCM and never returns it to the browser.
- Adds a responsive status page with active ingress, member latency, failure counters, manual checks, editing, and history.
- Uses the existing Telegram forward-notification switch for concise failover and DNS-update failure notices.

## Upgrade impact

- Creates three isolated failover tables and does not rewrite nodes, tunnels, forwards, port allocations, or users.
- Adds only short TCP probes from the panel server. Probe load grows with enabled group members and is bounded by four group workers and eight probe workers.
- Existing Agent 2.14.4 remains supported and does not need to upgrade. Panel and Agent release targets are now tracked separately.
- DNS detection and API updates complete in seconds, but client and resolver caches can delay traffic convergence; established sessions must reconnect.
