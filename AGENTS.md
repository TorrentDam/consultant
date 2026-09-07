# AGENTS.md

Guidelines for AI coding agents working on this repository.

## Project

Film consultant — a Scala 3 web application exposing a single WebSocket endpoint
that runs an agentic AI loop. Every WebSocket connection is a conversation:
the user sends text messages, the agent answers (max 5 loop iterations with
tools), and responses are streamed back over the same socket.

## Stack

- Scala 3 (3.8.4), built with [Mill](https://mill-build.org) 1.1.8
- Typelevel stack: cats-effect, fs2 (streaming), http4s (HTTP & WebSocket server)
- [sttp-ai](https://github.com/softwaremill/sttp-ai) with the OpenAI-compatible
  client module (`"com.softwaremill.sttp.ai" %% "openai"`) — used for the agent
  loop and tool calling
- Toolchain (Mill, JDK, Metals) is pinned in `flake.nix`

## Tools the agent can call

1. Search in Radarr/Sonarr
2. Add to Radarr/Sonarr
3. Get torrents

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
- **Testing**: MUnit (`munit.FunSuite`); add `munit-cats-effect` and extend
  `CatsEffectSuite` once effectful code needs testing

## LSP (Metals)

`opencode.json` runs Metals via `nix develop --command metals`, configured to
talk to Mill's BSP server directly and share Mill's incremental compilation
cache. LSP diagnostics are a convenience, not the source of truth — `mill
__.compile` is. If diagnostics look empty or stale, run the CLI compile
rather than trusting the silence.

## Conventions

- Prefer pure functional style consistent with cats-effect / fs2 idioms.
- Keep the agent loop iteration cap at 5.
- One WebSocket connection = one conversation; conversation state is per-connection.
- API keys and secrets come from environment variables / config — never hardcode
  or commit them.
- The repository is hosted on GitHub as a **private** repository.
- Never commit or push unless explicitly asked.
