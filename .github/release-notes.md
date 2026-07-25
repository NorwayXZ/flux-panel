## Low-resource deployment

- Panel installation and online updates now pull prebuilt amd64/arm64 images instead of compiling Maven and Vite projects on the target server.
- Runtime defaults are tuned for a 1 vCPU, 1 GB RAM host with swap while retaining every panel feature.
- Updates use immutable version tags, preserve database volumes, and keep the previous image version for rollback.
- Backend runtime uses a smaller Alpine JRE image; frontend and Agent remain multi-architecture.
- Backend file logs are compressed, size-rotated, retained for 30 days, and capped at 1 GB.

See the README for sizing guidance, customization, migration details, and the reason for this deployment change.
