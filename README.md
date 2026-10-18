# Consultant

A film consultant web service.

Consultant exposes an [MCP](https://modelcontextprotocol.io/) server at `POST /mcp`
(built with [Chimp](https://github.com/softwaremill/chimp)) with three tools for
[Radarr](https://radarr.video/): `search`, `add` and `get`.

## Configuration

- `RADARR_API_KEY` (required) — Radarr API key; the server refuses to start without it
- `RADARR_URL` — Radarr base URL (default `http://localhost:7878`)

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
