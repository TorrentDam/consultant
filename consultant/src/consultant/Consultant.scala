package consultant

import consultant.mcp.RadarrMcpServer
import consultant.radarr.{RadarrClient, RadarrConfig}
import ox.*
import ox.channels.Channel
import ox.flow.{Flow, FlowEmit}
import ox.logback.InheritableMDC
import sttp.client4.DefaultSyncBackend
import sttp.ai.openai.OpenAISyncClient
import sttp.ai.openai.requests.completions.chat.ChatRequestBody.ChatBody
import sttp.ai.openai.requests.completions.chat.ChatRequestBody.ChatCompletionModel.{
  CustomChatCompletionModel => CustomModel
}
import sttp.ai.openai.requests.completions.chat.message.{Content, Message}
import sttp.model.Uri
import sttp.tapir.*
import sttp.tapir.CodecFormat.TextPlain
import sttp.tapir.server.netty.sync.{NettySyncServer, OxStreams}

import java.util.UUID
import org.slf4j.{Logger, LoggerFactory}
import scala.collection.mutable

case class ChatMessage(text: String)
given Codec[String, ChatMessage, TextPlain] = Codec.string.map(ChatMessage(_))(_.text)

/** One WebSocket connection = one conversation. History accumulates per connection. */
class Conversation(client: OpenAISyncClient, model: CustomModel, logger: Logger):
  private val history = mutable.ListBuffer[Message]()

  def reply(userText: String): String =
    history += Message.User(Content.TextContent(userText))
    logger.debug(s"calling LLM, history size = ${history.size}")
    val response = client.createChatCompletion(ChatBody(model = model, messages = history.toList))
    val answer = response.choices.head.message.content
    history += Message.Assistant(answer)
    answer

object Consultant extends OxApp:
  private val logger = LoggerFactory.getLogger(getClass)

  /** Checked configuration, parsed from environment variables once at startup. */
  final case class LlmConfig(apiKey: String, baseUrl: Uri, model: String)
  final case class RadarrConfigEnv(baseUrl: Uri, apiKey: String)
  final case class AppConfig(llm: LlmConfig, radarr: Option[RadarrConfigEnv])

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

    val apiKey = sys.env.getOrElse("OPENAI_KEY", sys.env.getOrElse("OPENAI_API_KEY", "ollama"))
    val llm = LlmConfig(
      apiKey = apiKey,
      baseUrl = parseUri("OPENAI_BASE_URL", Some("http://localhost:11434/v1")),
      model = sys.env.getOrElse("OPENAI_MODEL", "GLM-5.3")
    )

    val radarr =
      sys.env.get("RADARR_API_KEY").flatMap(v => Option.when(v.trim.nonEmpty)(v)) match
        case Some(key) =>
          val baseUrl = parseUri("RADARR_URL", Some("http://localhost:7878"))
          Some(RadarrConfigEnv(baseUrl, key))
        case None =>
          logger.warn("RADARR_API_KEY not set — the Radarr MCP server endpoint is disabled")
          None

    AppConfig(llm, radarr)

  private val wsEndpoint = endpoint.get
    .in("chat")
    .out(webSocketBody[ChatMessage, TextPlain, ChatMessage, TextPlain](OxStreams))

  // A pipe: each incoming user message -> one assistant reply, per connection
  private def chatPipe(client: OpenAISyncClient, model: CustomModel): OxStreams.Pipe[ChatMessage, ChatMessage] =
    incoming =>
      Flow.usingEmit: emit =>
        InheritableMDC.supervisedWhere("conversationId" -> UUID.randomUUID().toString.take(8)):
          val connectionLogger = LoggerFactory.getLogger("consultant.connection")
          connectionLogger.info("conversation started")
          val conversation = Conversation(client, model, connectionLogger)
          val replies = Channel.bufferedDefault[ChatMessage]

          forkDiscard:
            incoming.runForeach: msg =>
              connectionLogger.info(s"user message: ${msg.text}")
              val answer =
                try conversation.reply(msg.text)
                catch
                  case e: Exception =>
                    connectionLogger.error("LLM call failed", e)
                    s"LLM call failed: ${e.getMessage}"
              replies.send(ChatMessage(answer))
            replies.done()

          FlowEmit.channelToEmit(replies, emit)

  override def run(args: Vector[String])(using Ox): ExitCode =
    InheritableMDC.init

    val config =
      try parseEnvOrThrow()
      catch
        case e: IllegalStateException =>
          logger.error(s"Invalid configuration, refusing to start: ${e.getMessage}")
          return ExitCode.Failure(1)

    val client = OpenAISyncClient(config.llm.apiKey, config.llm.baseUrl)
    val chatModel = CustomModel(config.llm.model)

    val chatServerEndpoint = wsEndpoint.handleSuccess(_ => chatPipe(client, chatModel))

    val mcpServerEndpoint = config.radarr.map { radarr =>
      val radarrClient = RadarrClient(
        RadarrConfig(radarr.baseUrl, radarr.apiKey),
        DefaultSyncBackend()
      )
      RadarrMcpServer.endpoint(radarrClient, List("mcp"))
    }

    val binding = NettySyncServer()
      .host("0.0.0.0")
      .port(8080)
      .addEndpoint(chatServerEndpoint)
      .addEndpoints(mcpServerEndpoint.toList)
      .start()

    releaseAfterScope(binding.stop())
    logger.info(s"Consultant listening on ${binding.hostName}:${binding.port} (model: ${config.llm.model})")
    never