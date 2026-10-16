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
import sttp.client4.testing.SyncBackendStub
import sttp.model.Uri
import sttp.shared.Identity
import sttp.tapir.server.netty.sync.NettySyncServer

class RadarrMcpServerSpec extends FunSuite:
  private val config = RadarrConfig(Uri.unsafeParse("http://localhost:7878"), "test-key")

  private val inLibraryJson =
    """[{"id": 1, "title": "Dune", "tmdbId": 438631, "year": 2021, "overview": "o",
      | "isAvailable": true, "monitored": true, "hasFile": true, "sizeOnDisk": 1048576,
      | "qualityProfileId": 1, "path": "/movies/dune", "added": "2024-01-01"}]""".stripMargin

  private val notInLibraryJson =
    """[{"id": null, "title": "Dune", "tmdbId": 438631, "year": 2021, "overview": "o",
      | "isAvailable": false, "monitored": false, "hasFile": false, "sizeOnDisk": null,
      | "qualityProfileId": null, "path": null, "added": null}]""".stripMargin

  private def withServer(radarrResponse: String, postResponse: Option[String] = None)(
      test: McpClient[Identity] => Unit
  ): Unit =
    supervised:
      val radarrBackend = SyncBackendStub
        .whenRequestMatches(_.method.method == "POST")
        .thenRespondAdjust(postResponse.getOrElse(radarrResponse))
        .whenRequestMatches(_.method.method == "GET")
        .thenRespondAdjust(radarrResponse)
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
    withServer(inLibraryJson) { client =>
      val tools = client.listTools()
      assertEquals(tools.tools.map(_.name).toSet, Set("search", "add", "get"))
    }
  }

  test("search returns matching movies and their library state") {
    withServer(inLibraryJson) { client =>
      val result = client.callTool("search", Json.obj("query" -> "dune".asJson))
      assert(!result.isError)
      assert(textOf(result).contains("Dune"))
      assert(textOf(result).contains("downloaded"))
    }
  }

  test("get returns the film's state in Radarr") {
    withServer(inLibraryJson) { client =>
      val result = client.callTool("get", Json.obj("tmdbId" -> 438631.asJson))
      assert(!result.isError)
      assert(textOf(result).contains("in the Radarr library"))
      assert(textOf(result).contains("monitored=true"))
    }
  }

  test("get reports a movie that is not in the library") {
    withServer(notInLibraryJson) { client =>
      val result = client.callTool("get", Json.obj("tmdbId" -> 438631.asJson))
      assert(!result.isError)
      assert(textOf(result).contains("not in the Radarr library"))
    }
  }

  test("add adds the movie to Radarr") {
    val addedJson =
      """{"id": 2, "title": "Dune", "tmdbId": 438631, "year": 2021, "overview": "o",
        | "isAvailable": false, "monitored": true, "hasFile": false, "sizeOnDisk": null,
        | "qualityProfileId": null, "path": null, "added": null}""".stripMargin
    withServer(notInLibraryJson, Some(addedJson)) { client =>
      val result = client.callTool("add", Json.obj("tmdbId" -> 438631.asJson, "monitored" -> true.asJson))
      assert(!result.isError)
      assert(textOf(result).contains("Added Dune"))
    }
  }
