package consultant.mcp

import consultant.radarr.{FilmState, RadarrClient, RadarrConfig}
import chimp.client.McpClient
import chimp.client.transport.ClientHttpTransport
import chimp.protocol.{Implementation, ToolContent}
import io.circe.Json
import io.circe.syntax.*
import munit.FunSuite
import ox.supervised
import sttp.client4.{DefaultSyncBackend, UriContext}
import sttp.client4.testing.{ResponseStub, SyncBackendStub}
import sttp.model.Uri
import sttp.shared.Identity
import sttp.tapir.server.netty.sync.NettySyncServer

class RadarrMcpServerSpec extends FunSuite:
  private val config = RadarrConfig(Uri.unsafeParse("http://localhost:7878"), "test-key")

  // Shapes mirroring the real Radarr v3 API (see MovieLookupController / MovieController)
  private val termSearchJson =
    """[{"id": 1, "title": "Dune", "tmdbId": 438631, "year": 2021, "overview": "o",
      | "isAvailable": true, "monitored": true, "hasFile": true, "sizeOnDisk": 1048576,
      | "qualityProfileId": 1, "path": "/movies/dune", "added": "2024-01-01"}]""".stripMargin

  private val libraryJson =
    """[{"id": 1, "title": "Dune", "tmdbId": 438631, "year": 2021, "overview": "o",
      | "isAvailable": true, "monitored": true, "hasFile": true, "sizeOnDisk": 1048576,
      | "qualityProfileId": 1, "path": "/movies/dune", "added": "2024-01-01"}]""".stripMargin

  private val emptyLibraryJson = "[]"

  private val tmdbLookupJson =
    """{"id": 0, "title": "Dune", "tmdbId": 438631, "year": 2021, "overview": "o",
      | "isAvailable": false, "monitored": true, "hasFile": false, "sizeOnDisk": null,
      | "qualityProfileId": 0, "path": null, "added": null}""".stripMargin

  private val qualityProfilesJson = """[{"id": 1, "name": "HD"}]"""
  private val rootFoldersJson = """[{"id": 1, "path": "/movies"}]"""

  private val addedMovieJson =
    """{"id": 2, "title": "Dune", "tmdbId": 438631, "year": 2021, "isAvailable": false,
      | "monitored": true, "hasFile": false, "qualityProfileId": 1, "path": "/movies/dune"}""".stripMargin

  private def radarrBackend(
      termSearch: String = termSearchJson,
      library: String = libraryJson
  ): SyncBackendStub =
    SyncBackendStub.whenRequestMatchesPartial { case r =>
      val path = r.uri.path.toList
      if r.method.method == "GET" && path.endsWith(List("lookup", "tmdb")) then ResponseStub.adjust(tmdbLookupJson)
      else if r.method.method == "GET" && path.endsWith(List("qualityprofile")) then
        ResponseStub.adjust(qualityProfilesJson)
      else if r.method.method == "GET" && path.endsWith(List("rootfolder")) then ResponseStub.adjust(rootFoldersJson)
      else if r.method.method == "GET" && path.endsWith(List("movie", "lookup")) then ResponseStub.adjust(termSearch)
      else if r.method.method == "GET" && path.endsWith(List("movie")) then ResponseStub.adjust(library)
      else if r.method.method == "POST" then ResponseStub.adjust(addedMovieJson)
      else throw IllegalArgumentException(s"unstubbed: $path")
    }

  private def withServer(radarrBackend: SyncBackendStub)(test: McpClient[Identity] => Unit): Unit =
    supervised:
      val radarrClient = RadarrClient(config, radarrBackend)
      val binding =
        NettySyncServer().port(0).addEndpoint(RadarrMcpServer.endpoint(radarrClient, List("mcp"))).start()
      try
        val backend = DefaultSyncBackend()
        try
          val transport =
            ClientHttpTransport[Identity](backend, uri"http://localhost:${binding.port}/mcp")
          try test(McpClient(transport, Implementation("radarr-mcp-test", "0.0.1")))
          finally transport.close()
        finally backend.close()
      finally binding.stop()

  private def textOf(result: chimp.protocol.CallToolResult): String =
    result.content.collect { case ToolContent.Text(_, text) => text }.mkString("\n")

  test("the server lists the search, add and get tools") {
    withServer(radarrBackend()) { client =>
      val tools = client.listTools()
      assertEquals(tools.tools.map(_.name).toSet, Set("search", "add", "get"))
    }
  }

  test("search returns matching movies and their library state") {
    withServer(radarrBackend()) { client =>
      val result = client.callTool("search", Json.obj("query" -> "dune".asJson))
      assert(!result.isError)
      assert(textOf(result).contains("Dune"))
      assert(textOf(result).contains("downloaded"))
    }
  }

  test("get returns the film's state in Radarr") {
    withServer(radarrBackend()) { client =>
      val result = client.callTool("get", Json.obj("tmdbId" -> 438631.asJson))
      assert(!result.isError)
      assert(textOf(result).contains("in the Radarr library"))
      assert(textOf(result).contains("monitored=true"))
    }
  }

  test("get reports a movie that is not in the library") {
    withServer(radarrBackend(library = emptyLibraryJson)) { client =>
      val result = client.callTool("get", Json.obj("tmdbId" -> 438631.asJson))
      assert(!result.isError)
      assert(textOf(result).contains("not in the Radarr library"))
    }
  }

  test("add adds the movie to Radarr") {
    withServer(radarrBackend(library = emptyLibraryJson)) { client =>
      val result = client.callTool("add", Json.obj("tmdbId" -> 438631.asJson, "monitored" -> true.asJson))
      assert(!result.isError)
      assert(textOf(result).contains("Added Dune"))
    }
  }
