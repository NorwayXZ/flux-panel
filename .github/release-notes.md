## Node Agent status display

- Moves the Agent version to a permanent row directly below node uptime.
- Hides completed upgrade tasks from node cards instead of leaving stale success banners visible.
- Shows a short success notification only when an active upgrade transitions to success.
- Keeps active progress and current-target failure details visible so retry actions remain clear.

## Included platform changes

- Includes the TLS SNI domain-entry feature introduced in 2.14.0.
- Contains no database migration and does not restart or reconfigure node forwarding services.
