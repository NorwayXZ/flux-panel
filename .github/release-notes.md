## Agent one-click upgrades

- Adds single-node and batch Agent upgrades to the node management page.
- Shows the installed version, latest-version state, live upgrade progress, failures, and rollback results on each node card.
- Uses a dedicated self-update command on Agent 2.13.0 and newer.
- Bootstraps Agent 2.8.0 through 2.12.x through the existing encrypted terminal channel with a fixed, non-editable update command.
- Verifies both the release SHA256 checksum and the binary's embedded Agent version before replacement.
- Runs the updater outside the Agent process lifecycle on systemd and OpenRC hosts.
- Backs up the current binary and automatically restores it when the replacement service cannot start.
- Keeps Agent 2.7.x and older on the manual upgrade path because they do not provide the required secure terminal channel.

Agent restart can briefly interrupt traffic handled by that node. The release adds only the `agent_upgrade_task` audit table and temporary update files; it does not rewrite node, tunnel, forwarding, port, user, or traffic data and adds no persistent Agent resource usage.
