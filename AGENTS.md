# AGENTS.md

Guidelines for AI coding agents working on this repository.

## Project

Film consultant — a Scala 3 web application exposing a single WebSocket endpoint
that runs an agentic AI loop. Every WebSocket connection is a conversation:
the user sends text messages, the agent answers (max 5 loop iterations with
tools), and responses are streamed back over the same socket.

## Stack

- Scala 3, sbt
- Typelevel stack: cats-effect, fs2 (streaming), http4s (HTTP & WebSocket server)
- [sttp-ai](https://github.com/softwaremill/sttp-ai) with the OpenAI-compatible
  client module (`"com.softwaremill.sttp.ai" %% "openai"`) — used for the agent
  loop and tool calling

## Tools the agent can call

1. Search in Radarr/Sonarr
2. Add to Radarr/Sonarr
3. Get torrents

Tool implementations talk to the Radarr/Sonarr HTTP APIs over sttp.

## Conventions

- Prefer pure functional style consistent with cats-effect / fs2 idioms.
- Keep the agent loop iteration cap at 5.
- One WebSocket connection = one conversation; conversation state is per-connection.
- Use sttp for HTTP client calls (Radarr/Sonarr APIs).
- API keys and secrets come from environment variables / config — never hardcode
  them and never commit them.
- The repository is hosted on GitHub as a **private** repository.

## Workflow

- Run `sbt compile` and relevant tests before finishing a task.
- Never commit or push unless explicitly asked.