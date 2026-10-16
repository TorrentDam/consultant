package consultant.mcp

import consultant.radarr.*
import chimp.server.*
import io.circe.Codec
import io.circe.generic.semiauto.*
import sttp.tapir.*
import sttp.tapir.server.netty.sync.NettySyncServer

final case class SearchInput(query: String) derives Codec, Schema
final case class AddInput(tmdbId: Long, monitored: Option[Boolean] = None) derives Codec, Schema
final case class GetInput(tmdbId: Long) derives Codec, Schema

/** Wiring between the Radarr client and the chimp tool definitions. */
object RadarrMcpServer:
  private def movieSummary(movie: Movie): String =
    val year = movie.year.map(y => s" ($y)").getOrElse("")
    val state =
      if movie.hasFile then "downloaded"
      else if movie.isInLibrary then s"in library${if movie.monitored then ", monitored" else ""}"
      else "not in library"
    s"${movie.title}$year — TMDB ${movie.tmdbId}: $state"

  private def describeSearch(results: List[Movie]): String =
    if results.isEmpty then s"No movies found."
    else results.map(movieSummary).mkString("\n")

  private def describeState(state: FilmState): String = state match
    case FilmState.InLibrary(movie) =>
      val size = movie.sizeOnDisk.map(b => s", ${b / (1024 * 1024)} MB on disk").getOrElse("")
      val path = movie.path.map(p => s" at $p").getOrElse("")
      s"${movie.title} is in the Radarr library: " +
        s"downloaded=${movie.hasFile}, monitored=${movie.monitored}, available=${movie.isAvailable}$size$path"
    case FilmState.NotInLibrary => "The movie is not in the Radarr library."

  /** Creates the chimp MCP server with the `search`, `add` and `get` tools. */
  def apply(client: RadarrClient): McpServer[sttp.shared.Identity] =
    val search = tool("search")
      .description("Search for a movie in Radarr by title. Returns matching movies and their library state.")
      .input[SearchInput]
      .handle: input =>
        client.search(input.query) match
          case Right(results) => ToolResult.text(describeSearch(results))
          case Left(error)    => ToolResult.error(s"Radarr search failed: $error")

    val add = tool("add")
      .description("Add a movie to Radarr by its TMDB id, so that Radarr starts looking for it.")
      .input[AddInput]
      .handle: input =>
        client.add(input.tmdbId, monitored = input.monitored.getOrElse(true)) match
          case Right(movie) => ToolResult.text(s"Added ${movie.title} (TMDB ${movie.tmdbId}) to Radarr.")
          case Left(error)  => ToolResult.error(s"Radarr add failed: $error")

    val get = tool("get")
      .description("Get a movie's state in Radarr by its TMDB id: whether it is in the library, downloaded, monitored.")
      .input[GetInput]
      .handle: input =>
        client.get(input.tmdbId) match
          case Right(state)  => ToolResult.text(describeState(state))
          case Left(error)   => ToolResult.error(s"Radarr get failed: $error")

    McpServer[sttp.shared.Identity](name = "radarr-mcp", version = "0.1.0")
      .instructions("Tools for searching, adding and inspecting movies in a Radarr instance.")
      .addTools(search, add, get)

  /** The tapir server endpoint exposing the MCP server at the given path, e.g. `List("mcp")`. */
  def endpoint(client: RadarrClient, path: List[String]) =
    apply(client).endpoint(path)
