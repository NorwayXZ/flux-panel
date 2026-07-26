## Reliable Agent upgrade recovery

- Records terminal-bootstrap upgrade output in a per-task log on the node.
- Reports helper startup and execution failures instead of leaving the card in a generic waiting state.
- Explains whether an upgrade timed out because the node stayed offline or because it came back with the old Agent version.
- Shows `Retry upgrade` after failed, rolled-back, or timed-out attempts.
- Hides stale failed task banners after a node has already reached the current Agent version.

The updater still preserves the existing node configuration and restores the previous Agent binary if the new service cannot start.

- Adds a dedicated **Intranet Access** navigation group for the related Port Resources and Intranet Mapping workflows.
- Orders Port Resources before Intranet Mapping to reflect the actual setup sequence.
- Moves Alert Center into System Management as an operational administration feature.
- Renames the former Service Publishing page and its actions to concise intranet-mapping terminology across desktop, mobile, port ledger, user permissions, and backend validation messages.

This navigation and terminology patch does not change routes, API contracts, database schemas, stored resource types, or existing node, tunnel, forwarding, port, user, and traffic data.
