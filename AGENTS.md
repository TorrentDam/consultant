# AGENTS.md

Guidelines for AI coding agents working on this repository.

## Project

Film consultant — a Scala 3 web application exposing a single MCP server
endpoint (`POST /mcp`) with tools for managing a movie library in Radarr.

## Stack

- Scala 3 (3.9.0 LTS), built with [Mill](https://mill-build.org) 1.1.8
- Direct style with [Ox](https://github.com/softwaremill/ox) (core 1.0.6):
  structured concurrency, resiliency — no effect system
- [Tapir](https://tapir.softwaremill.com) `tapir-netty-server-sync` — HTTP server
- [Chimp](https://github.com/softwaremill/chimp) — MCP server and tool definitions
- Tool implementations talk to the Radarr HTTP API over sttp.
- Toolchain (Mill, JDK, Metals) is pinned in `flake.nix`

## Tools the MCP server exposes

1. Search in Radarr
2. Add to Radarr
3. Get a movie's state in Radarr

Tool implementations talk to the Radarr/Sonarr HTTP APIs over sttp.

## Build/Test Commands

```bash
nix develop --command mill __.compile   # compile all modules
nix develop --command mill __.test      # run all tests
nix develop --command mill mill.scalalib.scalafmt.ScalafmtModule/reformatAll __.sources
```

Drop the `nix develop --command` prefix if you're already inside the dev shell.
**Always run compile and test before finishing a task.**

## Project Structure

- `flake.nix` / `flake.lock` — pins Mill, JDK, and Metals versions
- `build.mill` — Mill build definition
- `consultant/` — application module; sources in `src`, tests in `test/src`

## Code Style

- **Language**: Scala 3, max line length 120
- **Formatting**: Scalafmt, config in `.scalafmt.conf`
- **Testing**: MUnit (`munit.FunSuite`)

## LSP (Metals)

`opencode.json` runs Metals via `nix develop --command metals`, configured to
talk to Mill's BSP server directly and share Mill's incremental compilation
cache. LSP diagnostics are a convenience, not the source of truth — `mill
__.compile` is. If diagnostics look empty or stale, run the CLI compile
rather than trusting the silence.

## Conventions

- Prefer direct-style, expression-oriented code consistent with Ox idioms
  (`supervised`, `fork`, `Flow`, `Channel`) — no effect system.
- API keys and secrets come from environment variables / config — never hardcode
  or commit them.
- The repository is hosted on GitHub as a **private** repository.
- Never commit or push unless explicitly asked.
