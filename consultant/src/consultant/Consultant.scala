package consultant

import chimp.server.OriginCheck
import consultant.mcp.RadarrMcpServer
import consultant.radarr.{RadarrClient, RadarrConfig}
import ox.*
import sttp.client4.DefaultSyncBackend
import sttp.model.Uri
import sttp.tapir.server.netty.sync.NettySyncServer

import org.slf4j.{Logger, LoggerFactory}

object Consultant extends OxApp:
  private val logger = LoggerFactory.getLogger(getClass)

  /** Checked configuration, parsed from environment variables once at startup. */
  final case class AppConfig(radarr: RadarrConfigEnv, originCheck: OriginCheck)
  final case class RadarrConfigEnv(baseUrl: Uri, apiKey: String)

  /** Fails fast with a descriptive error if any environment variable is invalid. */
  private def parseEnvOrThrow(): AppConfig =
    def requireNonEmpty(name: String): String =
      sys.env.get(name).flatMap(v => Option.when(v.trim.nonEmpty)(v)) match
        case Some(value) => value
        case None => throw IllegalStateException(s"$name must be set to a non-empty value")

    def parseUri(name: String, default: Option[String] = None): Uri =
      val raw = sys.env.getOrElse(name, default.getOrElse(""))
      Uri.parse(raw) match
        case Right(uri) if uri.scheme.isDefined && uri.host.isDefined => uri
        case Right(_) => throw IllegalStateException(s"Invalid $name '$raw': an absolute http(s) URL is required")
        case Left(err) => throw IllegalStateException(s"Invalid $name '$raw': $err")

    def parseOriginCheck(): OriginCheck =
      sys.env.get("MCP_ALLOWED_HOSTS").flatMap(v => Option.when(v.trim.nonEmpty)(v.trim)) match
        case None      => OriginCheck.localhostOnly
        case Some("*") => OriginCheck.disabled
        case Some(csv) =>
          val hosts = csv.split(",").map(_.trim).filter(_.nonEmpty).toSet
          if hosts.isEmpty then
            throw IllegalStateException("MCP_ALLOWED_HOSTS must be '*' or a comma-separated list of hostnames")
          OriginCheck(hosts)

    val radarr = RadarrConfigEnv(
      baseUrl = parseUri("RADARR_URL", Some("http://localhost:7878")),
      apiKey = requireNonEmpty("RADARR_API_KEY")
    )

    AppConfig(radarr, parseOriginCheck())

  override def run(args: Vector[String])(using Ox): ExitCode =
    val config =
      try parseEnvOrThrow()
      catch
        case e: IllegalStateException =>
          logger.error(s"Invalid configuration, refusing to start: ${e.getMessage}")
          return ExitCode.Failure(1)

    val radarrClient = RadarrClient(
      RadarrConfig(config.radarr.baseUrl, config.radarr.apiKey),
      DefaultSyncBackend()
    )

    val binding = NettySyncServer()
      .host("0.0.0.0")
      .port(8080)
      .addEndpoint(RadarrMcpServer.endpoint(radarrClient, List("mcp"), config.originCheck))
      .start()

    releaseAfterScope(binding.stop())
    logger.info(s"Consultant listening on ${binding.hostName}:${binding.port}")
    never
