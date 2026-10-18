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

## Limits of the earlier one-machine test

The earlier test ran two real consumers on ONE machine. By itself it did not establish that two
separate servers, the public HTTPS payment endpoint, browser binding form and
Agent command forwarding work together in production; that path was subsequently
exercised below. It also
does not establish long-run throughput, dozens of concurrent nodes, same-provider
session compatibility, safe recovery from a lost pending payment, or exact budgets.

The production daemon still held the same identity during this bounded test.
That is unsuitable as an ongoing deployment; all clients of the shared identity
must use the coordinator once enabled. The short test is evidence for the custom
payment path, not a production readiness claim.


## Subsequent two-server panel validation

Deployed published `2.84.0-beta.1` through the panel host updater: update succeeded
and backend health passed. Upgraded NNC Japan (node 8) and Misaka Seattle (node 13)
to the matching Agent; both reconnected successfully.

- Bound node 13 to node 8 through the public panel HTTPS configure endpoint.
  The response reported member `ready`, with the same funded identity above.
- NNC Japan ran `myst-shared` and its existing US connection continued serving
  HTTPS with exit `165.140.26.94`.
- Created a temporary Shadowsocks proxy bound only to `127.0.0.1` on node 13,
  then selected a US Mysterium exit through the panel routing endpoint.
- Concurrent HTTPS trace requests on the two servers, at Unix timestamp
  `1792265784`, returned `165.140.26.94` and `72.84.199.11` respectively.
- Node 13 logged subsequent `invoice_paid` events for the shared identity,
  including agreement totals `48130115702814` and `88143690214523`. Node 8 also
  continued paying its independent provider through the same signer.
- The panel Shadowsocks connection self-check returned `ok: true`, exit
  `72.84.199.11`, US, 310 ms. This exercised the proxy routing path as well as
  the node-local Mysterium HTTP proxy.
- Deleted the temporary proxy and disconnected remote terminal sessions.
  The shared pool binding remains available for actual use.
- Full release CI run `39061893056` succeeded, publishing images and Agent assets.
  A subsequent payment-envelope integer precision test also passed.

This establishes the real two-server HTTPS panel/Agent/signing/traffic path in
one short validation run. Sustained operation at dozens of nodes, lost pending
payment recovery, same-provider compatibility and exact budgets remain unproven.

## Panel restart with 1.39.7-3 — 2026-10-18

Production panel, payer node NNC日本 and member 懒猫云美国vimiss, both with a US
residential connection through the shared balance (Agent 2.84.0-beta.16).

| Restart | Panel API unavailable | Exits afterwards | Session traffic counter | Reconnect events |
|---|---|---|---|---|
| beta.16 → beta.15 | 17:10:26–17:10:50 (CST) | unchanged: 165.140.26.183, 99.190.44.223 | kept rising (37,744 → 48,344; 39,488 → 50,088) | none |
| beta.15 → beta.16 | 17:13:11–17:13:42 | unchanged | kept rising (48,376 → 49,544; 50,120 → 51,336) | none |

Each check ran more than two minutes after the panel returned, beyond the
providers' unpaid-payment leeway. Before this change a panel restart could end
both sessions and, with a lost payment, keep the pool blocked.
