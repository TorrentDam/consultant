# Consultant

A film consultant web application with an agentic AI backend.

Consultant exposes a single WebSocket endpoint backed by an AI agent that helps
users manage their movie library. The agent runs a tool-calling loop and can
search and add titles in [Radarr](https://radarr.video/) (movies) and
[Sonarr](https://sonarr.tv/) (TV shows), as well as look up available torrents.

## How it works

1. The user opens a WebSocket connection — each connection is a conversation.
2. The user sends a text message (e.g. *"Add the latest Denis Villeneuve movie"*).
3. The agent runs an agentic loop — up to **5 iterations** — invoking tools as needed:
   - **Search in Radarr/Sonarr** — look up a movie or TV show in the user's library
   - **Add to Radarr/Sonarr** — add a new title for the *arr stack to grab
   - **Get torrents** — list torrents available for a title
4. The agent's final answer is sent back over the WebSocket.

## Stack

- [Scala 3](https://scala-lang.org/) with the [Typelevel](https://typelevel.org/) stack:
  - [cats-effect](https://typelevel.org/cats-effect/) / [fs2](https://fs2.io/) — effects & streaming
  - [http4s](https://http4s.org/) — HTTP & WebSocket server
- [sttp-ai](https://github.com/softwaremill/sttp-ai) — Scala toolkit for LLMs;
  OpenAI-compatible client (`com.softwaremill.sttp.ai %% openai`), used to implement
  the agent loop and tool calling. Works with any OpenAI-compatible API
  (OpenAI, Ollama, OpenRouter, Grok, …).
- Built with [Mill](https://mill-build.org). Toolchain (Mill, JDK, Metals) is
  pinned in `flake.nix` — run everything through `nix develop`.
- Two build targets share the same sources:
  - `consultant` — JVM target (`mill consultant.run`)
  - `consultantNative` — Scala Native target ([Scala Native](https://scala-native.org/) 0.5),
    an ahead-of-time compiled binary (`mill consultantNative.nativeLink`). The
    native toolchain (LLVM/Clang, Boehm GC) is provided by the nix dev shell.

## Running

```
nix develop --command mill consultant.run          # JVM
nix develop --command mill consultantNative.run    # Scala Native
```

Connect to the WebSocket endpoint and send a text message to start a conversation.

## Status

Private project. Initial milestone: single WebSocket endpoint with the agentic
loop and the three tools listed above.