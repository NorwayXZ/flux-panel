# Mysterium shared billing investigation

Status: offline prototype; NOT available in the panel or suitable for funding.

## Intended behavior

One funded consumer identity pays for multiple private-proxy servers. Every
server establishes its own Mysterium provider tunnel. Application traffic never
passes through the billing coordinator. All usage is still charged to the shared
balance; this is not an unlimited-traffic subscription.

## Verified on 2026-10-18

The panel pins myst 1.39.7. In that exact upstream version:

- `session/pingpong/invoice_payer.go` reads a locally stored grand total, adds
  the latest agreement delta, signs an exchange message, and increments local
  storage. There is no cross-machine transaction in that path.
- `session/pingpong/consumer_totals_storage.go` uses an in-memory map protected
  by process-local mutexes.
- Separate processes starting from total 100 and paying 7 and 11 produce valid
  signatures for totals 107 and 111, rather than a combined total 118.
- `session/pingpong/invoice_tracker.go` treats the Hermes errors `payment value
  too low`, `promise value too low`, and overspending as fatal.

The reproduced overlap demonstrates an accounting defect in naive key cloning.
It does not establish that every live multi-device connection fails, or that
Hermes has rejected a payment in this experiment. No funded identity was used.

Sources:

- https://github.com/mysteriumnetwork/node/blob/1.39.7/session/pingpong/invoice_payer.go
- https://github.com/mysteriumnetwork/node/blob/1.39.7/session/pingpong/consumer_totals_storage.go
- https://github.com/mysteriumnetwork/node/blob/1.39.7/session/pingpong/invoice_tracker.go
- https://github.com/mysteriumnetwork/node/blob/1.39.7/session/pingpong/consumer_balance_tracker.go

## Reproduce the offline experiments

Run the shared allocation tests with Python's standard library:

```sh
cd experiments/mysterium-shared-billing
python3 -m unittest -v test_ledger.py
```

For the upstream reproduction, copy `upstream_shared_identity_test.go` into
`session/pingpong/shared_identity_audit_test.go` of an upstream node checkout at
tag `1.39.7`, then run:

```sh
go test ./session/pingpong -run '^TestAuditSharedIdentity' -count=1 -v
```

`ledger.py` demonstrates a durable atomic allocator with invoice replay
protection, cumulative agreement deltas, an allocation ceiling, and exact integer
arithmetic. Fifty simulated servers run through eight worker processes and retry
all invoices. Restart and rollback behavior are also tested. This does NOT test
fifty real servers, WireGuard tunnels, Hermes acceptance, or network latency.

## Integration work still required

The stock myst binary has no remote payment allocation hook. Panel-only changes
cannot fix this. A custom consumer binary and a billing service would be needed:

1. Validate the invoice locally with myst's existing price/traffic checks.
2. Authenticate each agent to a central signer and authorize its agreements.
   Scope each pool by chain, identity, Hermes and active payment channel.
3. Persist invoice identity, full payload, allocated total and signed message in
   one recoverable transaction. Replays must return the same signed message.
4. Deliver messages in an order accepted by Hermes. Concurrent allocation alone
   does not solve out-of-order delivery across providers. Test this against the
   actual service before claiming shared billing works.
5. Reconcile the ledger with Hermes's latest promise, settlement and balance on
   startup; prevent an old daemon or database restore from spending independently.
   Persisting an allocation without delivering it must not be reported as paid.
6. Enforce budgets centrally, reject requests while the signer is unavailable,
   and verify whether concurrent sessions to the same provider are supported.
7. Validate two independent funded consumer servers concurrently, verify both
   exit IPs and bill acceptance, then test restart, retries and signer failure.

The Python allocator is deliberately not exposed as a network service or wired
into the panel. It cannot authorize spending or verify invoice signatures.

An alternative is one wallet funding independent node channels automatically.
That keeps direct traffic and avoids shared-identity payment races, but distributes
funds rather than exposing one instantly shared balance. Current consumers may
use off-chain balances, so direct token transfers must not be assumed to top up
those balances without verifying the payment path.
