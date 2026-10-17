# Shared Mysterium consumer

This source overlay extends the exact upstream `myst` 1.39.7 revision
`46f6d152f58cb66df8f74f882f32df0fb258d955`. The changes are GPL-3.0-or-later.
Upstream source and license: https://github.com/mysteriumnetwork/node/tree/1.39.7

## Build

```
python3 mysterium-consumer/build.py /tmp/flux-myst-build
```

The script checks the upstream revision before adding the payment hook. It can
be repeated without duplicating hooks. `--prepare-only` prepares sources for
cross-compilation. The dedicated GitHub workflow builds Linux amd64 and arm64
binaries, tests the upstream payment package, and publishes checksums plus the
source overlay. It does not replace the regular panel release workflow.

## Operation

The panel's Mysterium section has a shared balance binding form. Select a funded
account node and the nodes that should use it, and enter the public HTTPS payment
endpoint. Upgrade their Agents to the matching implementation first. Binding
restarts their Mysterium daemons. The original identity keys remain in each
keystore; the imported shared identity becomes the consumer default.

A consumer still checks provider invoices against local traffic and agreed price.
It then sends the invoice to the panel over HTTPS using a per-node random token.
The panel checks membership, enabled state and the shared budget before relaying
the raw JSON through the existing authenticated Agent command channel. The funded
node's loopback signer reconciles the consumer state with Hermes, durably saves
the signed message in BoltDB, and returns it. Consumers deliver the signed payment
to providers directly. Application traffic never enters the panel or signer.

The funded node also uses the signer for its own connections, so it cannot spend
independently of the shared ledger. Before allocating the next payment, the signer
requires Hermes to have observed the preceding signed amount. Conflicting requests
retry for a bounded interval and fail closed if no confirmation arrives. Replays
return the same durable message. The payment JSON is carried as text inside Agent
commands to preserve all 256-bit integer values.

## Availability and limitations

- The panel and funded node must stay online for new invoices. They carry billing
  requests, not application traffic. Existing tunnels may close when payment fails.
- A lost payment sender can leave the pool pending. The signer deliberately blocks
  new allocations until the old signed promise is confirmed; it does not reclaim
  or reuse signed spending capacity. Administrative recovery is not implemented.
- Protect and back up the signer's `shared-billing.db` with its account. Do not run
  an unmodified client with the shared key or restore an old ledger while spending.
- Consumer keys are imported to member nodes because the upstream connection
  protocol needs the consumer identity. A compromised member can therefore bypass
  this coordinator with custom software. Use only nodes under the same trusted
  administrator; the membership token is not a cryptographic spending restriction.
- The existing budget sampler has its existing polling delay. It is not an exact
  prepaid ceiling. The signer additionally checks the actual available balance.
- Real two-server provider acceptance, overlapping sessions, throughput, latency,
  and recovery under provider failures still require live validation. Unit tests
  and successful builds do not establish production readiness.

The original accounting investigation remains under
`experiments/mysterium-shared-billing/`.
