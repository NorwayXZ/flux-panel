## Alert center and history monitoring

- Adds a unified alert center for node, tunnel, and forward failures.
- Automatically resolves active alerts when a resource recovers or is intentionally paused.
- Tracks status-change intervals instead of writing repetitive per-minute samples, keeping storage use low on small servers.
- Adds 24-hour, 7-day, and 30-day availability trends, incident counts, and per-resource history.
- Adds alert filtering, unread badges, single-alert read handling, and mark-all-read support.
- Applies existing ownership and sharing permissions to monitoring data. Administrators see every resource and its owner; regular users only see owned or shared resources.
- Adds responsive desktop and mobile layouts, including a compact mobile alert bell.
- Automatically creates the monitoring tables during upgrade and includes an idempotent manual migration.
- Enables MySQL 8 public-key authentication for clean amd64 and arm64 installations.

Monitoring runs every 30 seconds and retains closed history for 90 days by default. Paused and unknown intervals are excluded from availability calculations.
