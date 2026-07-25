## Grouped route selection

- Primary and candidate route selectors now group available lines into port-forward, 2-hop tunnel, 3-hop tunnel, and higher-hop tunnel sections.
- Every option shows the tunnel name, ID, owner, and an explicit type badge.
- The selected value keeps the type visible, for example `CNMB-NNC Japan · 3-hop tunnel`.
- Candidate routes are still filtered to tunnels that share the primary route's ingress node before grouping.
- Desktop and mobile dropdowns use the same compact two-line option layout.

Port-forward lines in this list are single-node tunnel records (`type = 1`). This release does not make arbitrary existing forward cards selectable as upstream routes.
