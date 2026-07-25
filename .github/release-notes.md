## Card layout ordering

- Dashboard summary, shared-node permission, tunnel permission, node, tunnel, forward, speed-limit, and user cards can now be reordered from a dedicated drag handle.
- Layouts are isolated by account and card group, then stored in the panel database so the same order follows the user across browsers and devices.
- Browser-local storage remains available as an offline fallback and is automatically uploaded when server storage becomes available.
- Online/offline and tunnel hop-level sections stay fixed; cards only move inside their current section so operational grouping remains clear.
- New cards appear first until the user chooses another position.
- Forward cards use the new shared ordering behavior while preserving existing order as the initial migration source.

The backend creates the `layout_preference` table automatically at startup. Operators who manage schemas manually can run `migrations/20260725_layout_preferences.sql`; it is safe to run repeatedly.
