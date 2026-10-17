package consultant.radarr

import io.circe.Json
import munit.FunSuite
import sttp.client4.testing.{ResponseStub, SyncBackendStub}
import sttp.model.{StatusCode, Uri}

class RadarrClientSpec extends FunSuite:
  private val config = RadarrConfig(Uri.unsafeParse("http://localhost:7878"), "test-key")

  private val inLibraryJson =
    """[{"id": 1, "title": "Dune", "tmdbId": 438631, "year": 2021, "overview": "o",
      | "isAvailable": true, "monitored": true, "hasFile": true, "sizeOnDisk": 1048576,
      | "qualityProfileId": 1, "path": "/movies/dune", "added": "2024-01-01"}]""".stripMargin

  // Shape returned by GET /api/v3/movie/lookup/tmdb: a single MovieResource with id 0 (not in library)
  private val tmdbLookupJson =
    """{"id": 0, "title": "Dune", "tmdbId": 438631, "year": 2021, "overview": "o",
      | "isAvailable": false, "monitored": true, "hasFile": false, "sizeOnDisk": null,
      | "qualityProfileId": 0, "path": null, "added": null}""".stripMargin

  private val qualityProfilesJson =
    """[{"id": 1, "name": "HD"}, {"id": 2, "name": "UHD"}]"""

  private val rootFoldersJson =
    """[{"id": 1, "path": "/movies"}]"""

  private val addedMovieJson =
    """{"id": 2, "title": "Dune", "tmdbId": 438631, "year": 2021, "isAvailable": false,
      | "monitored": true, "hasFile": false, "qualityProfileId": 1, "path": "/movies/dune"}""".stripMargin

  /** A stub mirroring the real Radarr v3 routes, recording every request it serves. */
  private class RadarrStub(
      termSearch: String = inLibraryJson,
      library: String = inLibraryJson,
      post: String = addedMovieJson,
      rootFolders: String = rootFoldersJson
  ):
    val requests = scala.collection.mutable.ListBuffer.empty[String]

    private def respondTo(r: sttp.client4.GenericRequest[?, ?]): (String, String) =
      val path = r.uri.path.toList
      val call = s"${r.method.method} /${path.mkString("/")}"
      if r.method.method == "GET" && path.endsWith(List("lookup", "tmdb")) then
        (s"$call ${r.uri.params}", tmdbLookupJson)
      else if r.method.method == "GET" && path.endsWith(List("qualityprofile")) then (call, qualityProfilesJson)
      else if r.method.method == "GET" && path.endsWith(List("rootfolder")) then (call, rootFolders)
      else if r.method.method == "GET" && path.endsWith(List("movie", "lookup")) then
        (s"$call ${r.uri.params}", termSearch)
      else if r.method.method == "GET" && path.endsWith(List("movie")) then
        (s"$call ${r.uri.params}", library)
      else if r.method.method == "POST" then (s"$call ${r.body.show}", post)
      else throw IllegalArgumentException(s"unstubbed: $call")

    val backend = SyncBackendStub.whenRequestMatchesPartial { case r =>
      val (call, body) = respondTo(r)
      requests.addOne(call)
      ResponseStub.adjust(body)
    }

    def calls: List[String] = requests.toList

  test("search returns movies from the term lookup endpoint") {
    val stub = RadarrStub()
    val client = RadarrClient(config, stub.backend)
    val result = client.search("dune")
    assertEquals(result.map(_.size), Right(1))
    assertEquals(result.map(_.head.title), Right("Dune"))
    assertEquals(result.map(_.head.isInLibrary), Right(true))
    assert(stub.calls.exists(_.startsWith("GET /api/v3/movie/lookup term=dune")))
  }

  test("get queries the Radarr library and reports InLibrary with downloaded state") {
    val stub = RadarrStub()
    val client = RadarrClient(config, stub.backend)
    val result = client.get(438631)
    assert(result.exists(_.isInstanceOf[FilmState.InLibrary]))
    assert(stub.calls.exists(_.contains("tmdbId=438631")))
  }

  test("get reports NotInLibrary when the Radarr library query returns an empty list") {
    val stub = RadarrStub(library = "[]")
    val client = RadarrClient(config, stub.backend)
    assertEquals(client.get(438631), Right(FilmState.NotInLibrary))
  }

  test("add looks up TMDB metadata, resolves quality profile and root folder, and starts a search") {
    val stub = RadarrStub()
    val client = RadarrClient(config, stub.backend)
    val result = client.add(438631)
    assertEquals(result.map(_.title), Right("Dune"))
    assert(result.exists(_.isInLibrary))
    assert(stub.calls.exists(_.startsWith("GET /api/v3/movie/lookup/tmdb")))
    assert(stub.calls.exists(_.startsWith("GET /api/v3/qualityprofile")), s"calls: ${stub.calls}")
    assert(stub.calls.exists(_.startsWith("GET /api/v3/rootfolder")))
    stub.calls.find(_.startsWith("POST /api/v3/movie ")).foreach { post =>
      val json = post.substring(post.indexOf("{"))
      val body = io.circe.parser.parse(json).fold(e => Json.Null, identity)
      def field(name: String) = body.hcursor.downField(name)
      assertEquals(field("monitored").as[Boolean], Right(true), s"monitored must default to true: $post")
      assertEquals(field("addOptions").downField("searchForMovie").as[Boolean], Right(true), s"Radarr must start searching: $post")
      assertEquals(field("qualityProfileId").as[Long], Right(1L), s"required by Radarr PostValidator: $post")
      assertEquals(field("rootFolderPath").as[String], Right("/movies"), s"required by Radarr PostValidator: $post")
      assertEquals(field("tmdbId").as[Long], Right(438631L))
    }
  }

  test("search surfaces HTTP errors as RequestFailed") {
    val backend = SyncBackendStub.whenAnyRequest.thenRespondWithCode(StatusCode.Unauthorized)
    val client = RadarrClient(config, backend)
    assert(client.search("dune").left.exists(_.isInstanceOf[RadarrError.RequestFailed]))
  }

  test("add fails when no root folder is configured") {
    val stub = RadarrStub(rootFolders = "[]")
    val client = RadarrClient(config, stub.backend)
    assert(client.add(438631).left.exists(_.isInstanceOf[RadarrError.NotFound]))
  }
