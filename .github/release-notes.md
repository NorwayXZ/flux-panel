## Internal service publishing and port leases

- Adds a dedicated service publishing page for mapping public node ports to TCP services behind NAT or inside a LAN.
- Adds outbound-only internal connectors with per-connector target CIDR restrictions and live online status.
- Adds admin-managed public port pools with strict conflict checks against existing forwards and nodes that share the same public network namespace.
- Adds automatic port allocation, optional requested ports, lease renewal, expiry cleanup, cooldown, release, and audit history.
- Keeps a port reserved when a connector is offline until its stale reverse service can be removed, preventing duplicate allocation after reconnect.
- Uses GOST SOCKS5 BIND and `rtcp`; application traffic does not pass through the panel backend or database.
- Restricts publishing gateways to BIND-only mode and rejects SOCKS5 CONNECT; public ingress nodes must run Agent 2.7.0 or newer.
- Extends the existing amd64/arm64 Agent installer with an optional `connector` role while retaining `node` as the default.
- Installs connectors as an isolated `flux-connector` service under `/etc/flux-connector`, allowing a connector and a normal node Agent to coexist on one host.

The database upgrade is additive. Existing nodes, tunnels, forwards, user quotas, and monitoring data are not rewritten. The first release supports reverse TCP publishing; reverse UDP and multi-ingress failover remain future work.
