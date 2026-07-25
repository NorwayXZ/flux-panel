## Secure remote terminal

- Adds an administrator-only browser terminal for online Linux nodes over the existing encrypted Agent WebSocket connection.
- Keeps terminal access disabled per node until an administrator explicitly enables it and re-enters the current password.
- Uses single-use 60-second connection tickets, password retry throttling, one session per node, global concurrency limits, idle timeouts, and maximum session duration.
- Records session metadata for auditing without storing commands, terminal output, or passwords.
- Runs shells in isolated PTYs and tears them down when the browser disconnects, the node goes offline, or the Agent stops.
- Keeps terminal messages out of monitoring broadcasts and GOST configuration persistence.
- Requires Agent 2.8.0 for terminal access; older Agents continue to provide all existing monitoring and forwarding functions.

The schema migration only adds the disabled-by-default `node.terminal_enabled` flag and the `terminal_session_audit` table. Existing nodes, tunnels, forwards, users, and traffic records are not rewritten.
