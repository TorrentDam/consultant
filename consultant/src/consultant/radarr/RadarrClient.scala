package consultant.radarr

import io.circe.{Codec, Decoder, Json}
import io.circe.parser
import io.circe.syntax.*
import org.slf4j.{Logger, LoggerFactory}
import sttp.client4.{Request, SyncBackend, asString, basicRequest}
import sttp.model.{MediaType, Uri}

/** A movie as returned by Radarr's v3 API. Fields that Radarr emits as non-nullable ints with
  * sentinel defaults (0) or that may be absent (HasFile on lookup results) decode leniently.
  */
final case class Movie(
    id: Option[Long],
    title: String,
    tmdbId: Long,
    year: Option[Int],
    overview: Option[String],
    isAvailable: Boolean,
    monitored: Boolean,
    hasFile: Boolean,
    sizeOnDisk: Option[Long],
    qualityProfileId: Option[Long],
    path: Option[String],
    added: Option[String]
) derives Codec.AsObject:
  def isInLibrary: Boolean = id.exists(_ > 0)

object Movie:
  given sttp.tapir.Schema[Movie] = sttp.tapir.Schema.derived[Movie]

/** The state of a film in Radarr, as reported by the `get` tool. */
enum FilmState:
  case InLibrary(movie: Movie)
  case NotInLibrary

/** Errors that the Radarr client surfaces to the MCP tool layer. */
enum RadarrError:
  case NotFound(what: String)
  case RequestFailed(status: Int, body: String)
  case InvalidResponse(detail: String)

/** Which Radarr instance to talk to. */
final case class RadarrConfig(
    baseUrl: Uri,
    apiKey: String
)

/** Minimal synchronous Radarr v3 API client used by the MCP tools. */
final class RadarrClient(config: RadarrConfig, backend: SyncBackend):
  private val logger: Logger = LoggerFactory.getLogger("consultant.radarr")
  private val XApiKeyHeader = "X-Api-Key"

  private def sendJson[A: Decoder](request: Request[Either[String, String]]): Either[RadarrError, A] =
    request.send(backend) match
      case response if response.code.isSuccess =>
        response.body match
          case Right(body) =>
            parser.decode[A](body).left.map { e =>
              logger.warn(s"Radarr returned invalid JSON for ${request.method} ${request.uri.path}: ${e.getMessage}")
              RadarrError.InvalidResponse(e.getMessage)
            }
          case Left(error) => Left(RadarrError.RequestFailed(response.code.code, error))
      case response =>
        logger.warn(s"Radarr request failed: ${request.method} ${request.uri.path} -> ${response.code.code}")
        Left(RadarrError.RequestFailed(response.code.code, s"${response.body.merge}"))

  private def getJson[A: Decoder](uri: Uri): Either[RadarrError, A] =
    sendJson[A](
      basicRequest
        .header(XApiKeyHeader, config.apiKey)
        .contentType(MediaType.ApplicationJson)
        .response(asString)
        .get(uri)
    )

  private def postJson[A: Decoder](uri: Uri, body: String): Either[RadarrError, A] =
    sendJson[A](
      basicRequest
        .header(XApiKeyHeader, config.apiKey)
        .contentType(MediaType.ApplicationJson)
        .response(asString)
        .post(uri)
        .body(body)
    )

  /** `search`: looks up movies by title via `GET /api/v3/movie/lookup?term=...`. */
  def search(term: String): Either[RadarrError, List[Movie]] =
    logger.debug(s"searching Radarr for '$term'")
    getJson[List[Movie]](lookupUri.param("term", term))

  /** `add`: adds a movie (identified by TMDB id) to Radarr via `POST /api/v3/movie`. Resolves the
    * quality profile and root folder from the instance defaults when not given, as Radarr's
    * PostValidator requires both.
    */
  def add(
      tmdbId: Long,
      monitored: Boolean = true,
      qualityProfileId: Option[Long] = None,
      rootFolderPath: Option[String] = None
  ): Either[RadarrError, Movie] =
    logger.info(s"adding movie with TMDB id $tmdbId to Radarr (monitored=$monitored)")
    lookupByTmdb(tmdbId).flatMap { movie =>
      // Radarr emits qualityProfileId=0 on lookup results (no profile assigned); treat as unset.
      val effectiveProfile = qualityProfileId.orElse(movie.qualityProfileId).filter(_ > 0)
      resolveQualityProfileId(effectiveProfile).flatMap { qProfileId =>
        resolveRootFolderPath(rootFolderPath).flatMap { rootFolder =>
          val body = Json
            .obj(
              "tmdbId" -> tmdbId.asJson,
              "title" -> movie.title.asJson,
              "year" -> movie.year.map(_.asJson).getOrElse(Json.Null),
              "monitored" -> monitored.asJson,
              "qualityProfileId" -> qProfileId.asJson,
              "rootFolderPath" -> rootFolder.asJson,
              "addOptions" -> Json.obj("searchForMovie" -> monitored.asJson)
            )
            .dropNullValues
          postJson[Movie](config.baseUrl.addPath("api", "v3", "movie"), body.toString())
        }
      }
    }

  /** `get`: returns the film's state in Radarr via `GET /api/v3/movie?tmdbId=...` — the movie is
    * returned only when it is in the library, with its downloaded/monitored state.
    */
  def get(tmdbId: Long): Either[RadarrError, FilmState] =
    logger.debug(s"checking Radarr state for TMDB id $tmdbId")
    getJson[List[Movie]](moviesUri.param("tmdbId", tmdbId.toString)).map {
      case movie :: _ => FilmState.InLibrary(movie)
      case Nil        => FilmState.NotInLibrary
    }

  private def moviesUri: Uri = config.baseUrl.addPath("api", "v3", "movie")

  private def lookupUri: Uri = moviesUri.addPath("lookup")

  /** TMDB metadata lookup: `GET /api/v3/movie/lookup/tmdb?tmdbId=...`, a single MovieResource. */
  private def lookupByTmdb(tmdbId: Long): Either[RadarrError, Movie] =
    getJson[Movie](lookupUri.addPath("tmdb").param("tmdbId", tmdbId.toString))

  private def resolveQualityProfileId(givenId: Option[Long]): Either[RadarrError, Long] =
    givenId match
      case Some(id) => Right(id)
      case None =>
        getJson[List[Json]](config.baseUrl.addPath("api", "v3", "qualityprofile")).flatMap { profiles =>
          profiles.headOption
            .flatMap(_.asObject.flatMap(_.apply("id")).flatMap(_.as[Long].toOption))
            .toRight(RadarrError.NotFound("no quality profile configured in Radarr"))
        }

  private def resolveRootFolderPath(givenPath: Option[String]): Either[RadarrError, String] =
    givenPath match
      case Some(path) => Right(path)
      case None =>
        getJson[List[Json]](config.baseUrl.addPath("api", "v3", "rootfolder")).flatMap { folders =>
          folders.headOption
            .flatMap(_.asObject.flatMap(_.apply("path")).flatMap(_.as[String].toOption))
            .toRight(RadarrError.NotFound("no root folder configured in Radarr"))
        }
