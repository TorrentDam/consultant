package consultant.radarr

import munit.FunSuite
import sttp.client4.testing.SyncBackendStub
import sttp.model.{StatusCode, Uri}

class RadarrClientSpec extends FunSuite:
  private val config = RadarrConfig(Uri.unsafeParse("http://localhost:7878"), "test-key")

  private val inLibraryJson =
    """[{"id": 1, "title": "Dune", "tmdbId": 438631, "year": 2021, "overview": "o",
      | "isAvailable": true, "monitored": true, "hasFile": true, "sizeOnDisk": 1048576,
      | "qualityProfileId": 1, "path": "/movies/dune", "added": "2024-01-01"}]""".stripMargin

  private val notInLibraryJson =
    """[{"id": null, "title": "Dune", "tmdbId": 438631, "year": 2021, "overview": "o",
      | "isAvailable": false, "monitored": false, "hasFile": false, "sizeOnDisk": null,
      | "qualityProfileId": null, "path": null, "added": null}]""".stripMargin

  private def stubBackend(response: String) =
    SyncBackendStub.whenAnyRequest.thenRespondAdjust(response)

  test("search returns movies from the lookup endpoint") {
    val backend = stubBackend(inLibraryJson)
    val client = RadarrClient(config, backend)
    val result = client.search("dune")
    assertEquals(result.map(_.size), Right(1))
    assertEquals(result.map(_.head.title), Right("Dune"))
    assertEquals(result.map(_.head.isInLibrary), Right(true))
  }

  test("get reports the film's state as InLibrary when the movie has an id") {
    val backend = stubBackend(inLibraryJson)
    val client = RadarrClient(config, backend)
    val result = client.get(438631)
    assert(result.exists(_.isInstanceOf[FilmState.InLibrary]))
  }

  test("get reports NotInLibrary when the movie has no id") {
    val backend = stubBackend(notInLibraryJson)
    val client = RadarrClient(config, backend)
    assertEquals(client.get(438631), Right(FilmState.NotInLibrary))
  }

  test("add posts the movie to Radarr and returns it") {
    var posted: Option[String] = None
    val backend = SyncBackendStub
      .whenRequestMatches(_.method.method == "GET")
      .thenRespondAdjust(notInLibraryJson)
      .whenRequestMatches(_.method.method == "POST")
      .thenRespondAdjust("""{"id": 2, "title": "Dune", "tmdbId": 438631, "isAvailable": false, "monitored": true, "hasFile": false}""")
    val client = RadarrClient(config, backend)
    val result = client.add(438631)
    assertEquals(result.map(_.title), Right("Dune"))
    assert(result.exists(_.isInLibrary))
  }

  test("search surfaces HTTP errors as RequestFailed") {
    val backend = SyncBackendStub.whenAnyRequest.thenRespondWithCode(StatusCode.Unauthorized)
    val client = RadarrClient(config, backend)
    assert(client.search("dune").left.exists(_.isInstanceOf[RadarrError.RequestFailed]))
  }
