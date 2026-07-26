## Clear Agent install and upgrade actions

- Online nodes consistently show **Upgrade**, regardless of whether the panel can perform a direct self-update or must provide a compatibility command.
- Offline nodes consistently show **Install**, making recovery and reinstallation distinct from an online upgrade.
- Command dialogs, clipboard messages, colors, and icons now follow the selected install or upgrade action.
- Keeps dedicated self-update, encrypted-terminal bootstrap, checksum verification, backup, and automatic rollback behavior from 2.13.0.

Agent restart can briefly interrupt traffic handled by that node. This patch does not change database schemas or rewrite node, tunnel, forwarding, port, user, or traffic data.
