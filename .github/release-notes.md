## Domain entry stability

- Fixes an Agent crash when a TLS SNI domain entry receives a matching connection.
- Passes the service logger into both TLS and HTTP protocol-sniffing routes.
- Adds an end-to-end regression test that sends a real TLS ClientHello and verifies SNI routing to the selected HTTPS backend.

## Upgrade impact

- Contains no database migration and does not change existing tunnels or ordinary port forwarding.
- Domain entry nodes should upgrade their Agent to 2.14.2 before receiving production traffic.
