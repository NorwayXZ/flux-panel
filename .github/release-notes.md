## Automatic route failover hardening

- Switches immediately to the highest-priority healthy backup after the active route reaches its failure threshold.
- Requires consecutive successful probes and a stable recovery window before failing back to a preferred route.
- Adds a switch cooldown and latency hysteresis to prevent route flapping.
- Keeps the active route unchanged when a GOST service update fails and records the failed attempt for diagnosis.
- Stores successful and failed route switch events with the source route, destination route, reason, trigger, and timestamp.
- Adds a fixed-height failover status row to every forward card and a dedicated responsive detail view for candidate health and switch history.
- Prevents overlapping health-check rounds when a large route set takes longer than the configured interval.
- Automatically upgrades existing MySQL 5.7 or 8.0 databases with the new route metadata and event table.

Defaults: one health-check round every 60 seconds, two failures to declare a route down, two successes to confirm recovery, a 120-second switch cooldown, and a 180-second stable period before normal failback. Emergency failure switching bypasses cooldown and failback delays.
