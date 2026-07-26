## TLS domain entry, phase one

- Adds a dedicated **Domain Entry** tab to Intranet Mapping.
- Routes multiple exact TLS SNI domains through one shared public node and listening port.
- Keeps TLS end-to-end encrypted; certificates and private keys remain on the backend service.
- Reuses existing active intranet mappings instead of introducing a second target configuration model.
- Shows the DNS target, public TLS endpoint, backend mapping, owner, and a precise degraded-state reason.

## Port and lifecycle safety

- Reserves each shared TLS listening port in the global port ledger.
- Rejects conflicts with forwarding entries, multi-hop tunnel ports, port-pool ranges, and control ports.
- Treats duplicate node records for the same physical server as one port namespace.
- Prevents deletion of an intranet mapping while a domain entry depends on it.
- Retries domain-entry removal after an offline public node reconnects.

This release adds only the `domain_route` table. It does not rewrite existing nodes, tunnels, forwards, port pools, leases, or intranet mappings, and it does not start a TLS listener until the first domain entry is created.
