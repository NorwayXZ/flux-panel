## User quota and layout fixes

- Fixes blank monthly reset-day values in account, tunnel, and shared-node quota editors.
- Consolidates account and resource permissions under the single user edit workflow and removes the duplicate permissions action.
- Shows shared-node forward slots as used, limit, and remaining values, including the configured monthly traffic reset day.
- Marks a shared node unavailable when its forward-slot quota is exhausted.
- Enforces and tests one forward slot per shared node traversed by a user-owned route; creating a tunnel alone does not consume a slot.
- Keeps one slot per forward on a node even when multiple candidate routes of that forward traverse the same node.
- Aligns shared-node permission metrics into stable desktop columns with responsive stacking on narrower screens.

This update does not change the database schema or rewrite existing nodes, tunnels, forwards, or quota assignments. It does not increase Agent resource requirements.
