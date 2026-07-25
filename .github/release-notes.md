## Cross-platform internal connectors

- Adds Linux, Windows, and macOS choices when creating an internal connector.
- Generates the correct shell or administrator PowerShell command and allows switching platforms from the install dialog.
- Provides separate install/update and uninstall commands for each platform without touching a co-located node Agent.
- Publishes native amd64 and arm64 Agent binaries for all three operating systems.
- Installs the Windows connector as the automatic `FluxConnector` service under `%ProgramData%\FluxConnector`.
- Installs the macOS connector as the `com.fluxpanel.connector` LaunchDaemon under `/Library/Application Support/FluxConnector`.
- Keeps the existing Linux systemd and OpenRC installation path unchanged.
- Preserves `gost.json` during connector upgrades and uses an explicit Agent configuration path so services do not depend on their launch directory.
- Stores the selected connector platform without changing the forwarding protocol, port allocation, or existing published services.

The backend migration adds only `internal_connector.platform` with a default of `linux`. Existing connectors and all node, tunnel, forwarding, user, lease, and traffic records remain intact.
