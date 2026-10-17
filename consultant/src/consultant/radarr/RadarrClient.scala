package consultant.radarr

import io.circe.{Codec, Decoder, Json}
import io.circe.parser
import io.circe.syntax.*
import org.slf4j.{Logger, LoggerFactory}
import sttp.client4.{Request, SyncBackend, asString, basicRequest}
import sttp.model.{MediaType, Uri}

/** A movie as returned by Radarr's lookup/search endpoints. */
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
  def isInLibrary: Boolean = id.isDefined

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

  /** `search`: looks up movies by title. Returns both library and non-library results. */
  def search(term: String): Either[RadarrError, List[Movie]] =
    logger.debug(s"searching Radarr for '$term'")
    getJson[List[Movie]](lookupUri.param("term", term))

  /** `add`: adds a movie (identified by TMDB id) to Radarr, so Radarr starts looking for it. */
  def add(tmdbId: Long, monitored: Boolean = true): Either[RadarrError, Movie] =
    logger.info(s"adding movie with TMDB id $tmdbId to Radarr (monitored=$monitored)")
    lookupByTmdb(tmdbId).flatMap {
      case movie :: _ =>
        val body = Json
          .obj(
            "tmdbId" -> tmdbId.asJson,
            "title" -> movie.title.asJson,
            "monitored" -> monitored.asJson,
            "qualityProfileId" -> movie.qualityProfileId.map(_.asJson).getOrElse(Json.Null),
            "addOptions" -> Json.obj("searchForMovie" -> monitored.asJson)
          )
          .dropNullValues
        postJson[Movie](config.baseUrl.addPath("api", "v3", "movie"), body.toString())
      case Nil => Left(RadarrError.NotFound(s"movie with TMDB id $tmdbId"))
    }

  /** `get`: returns the film's state in Radarr — whether it is in the library, downloaded, monitored. */
  def get(tmdbId: Long): Either[RadarrError, FilmState] =
    logger.debug(s"checking Radarr state for TMDB id $tmdbId")
    lookupByTmdb(tmdbId).map {
      case movie :: _ if movie.isInLibrary => FilmState.InLibrary(movie)
      case _                               => FilmState.NotInLibrary
    }

  private def lookupUri: Uri =
    config.baseUrl.addPath("api", "v3", "movie", "lookup")

  private def lookupByTmdb(tmdbId: Long): Either[RadarrError, List[Movie]] =
    getJson[List[Movie]](lookupUri.param("tmdbId", tmdbId.toString))
