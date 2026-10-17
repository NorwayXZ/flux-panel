# Shared balance deployment

Use matching panel and Agent releases, starting with `2.84.0-beta.1`.
This is a prerelease. The stable update channel intentionally does not select it.

1. Back up the panel database and the funded node's Mysterium data directory.
   Keep identity keys private. Never copy an older payment ledger back over an
   active signer.
2. Deploy the matching panel backend and frontend. Upgrade the funded node and
   one additional node before configuring other servers.
3. In **出口管理 → Mysterium 家宽 → 统一余额 · 各节点直连**, select the funded
   account as payer and the second node as member. Use the panel's public HTTPS
   address followed by `/api/v1/mysterium-shared/pay`.
4. Check the binding result for every selected node. The member must show the
   same identity as the payer and `ready` in the shared pool overview.
5. Configure a private proxy on each server to use a Mysterium country. Send
   HTTPS requests through both proxies at the same time. Check the returned exit
   addresses and wait for subsequent invoices, beyond connection establishment.
6. Inspect payment and connection logs for rejected, pending or uncoordinated
   payments. A successful configuration response alone does not establish that
   both traffic and payment work.
7. Add more nodes only after the two-server check succeeds. All consumers of
   the shared identity must use the coordinated client, including the payer.

Each server carries its own application traffic directly to its provider. The
panel and payer carry invoice/signature requests and must stay online. Funding
one account means pooled spending, not unlimited traffic: every server's usage
still consumes that balance.

Do not remove the shared configuration or downgrade to stock myst while it is
spending. Stop shared connections before any rollback. Preserve the current
`shared-billing.db` and identity together. A pending signed payment requires
investigation; deleting the ledger is not a recovery procedure.

See [VALIDATION.md](VALIDATION.md) for the exact checks actually completed and
[README.md](README.md) for operational limitations.
