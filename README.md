# Consultant

A film consultant web service.

Consultant exposes an [MCP](https://modelcontextprotocol.io/) server at `POST /mcp`
(built with [Chimp](https://github.com/softwaremill/chimp)) with four tools for
[Radarr](https://radarr.video/): `search`, `add`, `get` and `delete`.

## Configuration

- `RADARR_API_KEY` (required) — Radarr API key; the server refuses to start without it
- `RADARR_URL` — Radarr base URL (default `http://localhost:7878`)
- `MCP_ALLOWED_HOSTS` — hostnames accepted in the `Host`/`Origin` headers of MCP
  requests, comma-separated (default: `localhost`, `127.0.0.1`, `::1`); `*` disables
  the check. Chimp rejects requests whose `Host` is not on the list (HTTP 403), so
  set this when clients reach the server through a proxy or a service DNS name —
  for example `consultant,consultant.media-server.svc.cluster.local` inside
  Kubernetes.

## Stack

- [Scala 3](https://scala-lang.org/) with a direct-style stack (no effect system):
  - [Ox](https://ox.softwaremill.com/) — structured concurrency, resiliency
  - [Tapir](https://tapir.softwaremill.com/) `tapir-netty-server-sync` — HTTP server
- Built with [Mill](https://mill-build.org). Toolchain (Mill, JDK, Metals) is
  pinned in `flake.nix` — run everything through `nix develop`.

## Running

```
nix develop --command mill consultant.run
```

The MCP endpoint is served on port 8080 at `POST /mcp`.

## Status

Private project.
