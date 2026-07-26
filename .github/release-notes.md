## Intranet service templates

- Adds presets for HTTP, HTTPS, SSH, Windows RDP, Minecraft Java, Synology DSM, MySQL, and PostgreSQL.
- Selecting a template pre-fills the mapping name and target port while preserving the selected connector, public port resource, and lease settings.
- Keeps custom TCP mappings available and clearly identifies the current TCP-only protocol support.
- Adds focused security notices for remote administration, NAS, and database templates.

## Upgrade impact

- Contains no database migration and does not change existing mappings, tunnels, or ordinary port forwarding.
- Existing Agents do not need to upgrade for this frontend-only feature.
