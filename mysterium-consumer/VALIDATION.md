# Validation record, 2026-10-18

## Automated checks

- Original myst 1.39.7 payment package tests: passed, including the new central
  signer, replay, persistence, pending confirmation and TLS consumer tests.
- Agent Mysterium tests: passed, including binary SHA256, environment isolation,
  configuration rejection and signer self-payment routing.
- Backend selected Mysterium, shared-billing, private-proxy and routing utility
  tests: passed. Shared-billing service suite: 4 tests, 0 failures/errors.
- Frontend Mysterium panel and exit actions tests passed; the panel suite was
  subsequently extended to 5 tests and passed again. Production UI build passed.
- GitHub shared consumer build run 39061121958 passed. Linux amd64 and arm64
  binaries, source overlay, GPL license and checksums were published as a prerelease.

## Real network test

Used the existing registered, funded identity on NNC Japan with two independent
myst processes in `/opt/myst-shared-validation`, separate data directories and
Tequilapi ports 4460/4461. Both used the loopback central signer on port 4451.
The production Agent and its port 4450 daemon were left in place.

- Identity: `0xd5a156f8ba7ab692fe42f569dd8a8acea3b8b9c1`.
- First provider: `0xa6d406b8eaabdbca2f77225840d1150fd169df17`.
- Second provider: `0x4962519f9198cf4703247424deb6041b813e5d85`.
- Both returned `Connected` concurrently.
- Concurrent HTTPS Cloudflare trace requests through ports 18990 and 18991
  returned distinct exit IPs `216.75.151.35` and `64.93.81.242`.
- Logs showed subsequent invoices receiving signed cumulative payment messages,
  beyond the first connection invoice. The second process's cumulative promise
  rose from 4439932103639319757 to 4439972820156072756 and 4440026532062360738
  base units. The central signer's pending-confirmation gate was active.
- No shared-billing failure appeared during this short check. The production
  connection on proxy port 47100 still returned `Connected` afterwards.
- Both test connections were deleted and test processes signaled to stop after
  the check. Copied identity keystores were then deleted. The validation directory retains
  logs, binary and the durable ledger with root-only access for inspection.

## What this does not establish

This test ran two real consumers on ONE machine. It does not establish that two
separate servers, the public HTTPS payment endpoint, browser binding form and
Agent command forwarding work together in production. The live panel remains
2.83.3; matching backend/frontend and Agent deployment is still required. It also
does not establish long-run throughput, dozens of concurrent nodes, same-provider
session compatibility, safe recovery from a lost pending payment, or exact budgets.

The production daemon still held the same identity during this bounded test.
That is unsuitable as an ongoing deployment; all clients of the shared identity
must use the coordinator once enabled. The short test is evidence for the custom
payment path, not a production readiness claim.
