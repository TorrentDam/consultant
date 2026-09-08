package consultant

import ox.*
import ox.channels.Channel
import ox.flow.{Flow, FlowEmit}
import ox.logback.InheritableMDC
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

  private def envConfig: (String, Uri, String) =
    val apiKey = sys.env.getOrElse("OPENAI_KEY", sys.env.getOrElse("OPENAI_API_KEY", "ollama"))
    val baseUrl = Uri.parse(sys.env.getOrElse("OPENAI_BASE_URL", "http://localhost:11434/v1")) match
      case Right(uri) => uri
      case Left(err)  => throw new IllegalArgumentException(s"Invalid OPENAI_BASE_URL: $err")
    val model = sys.env.getOrElse("OPENAI_MODEL", "GLM-5.3")
    (apiKey, baseUrl, model)

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
    val (apiKey, baseUrl, model) = envConfig
    val client = OpenAISyncClient(apiKey, baseUrl)
    val chatModel = CustomModel(model)

    val chatServerEndpoint = wsEndpoint.handleSuccess(_ => chatPipe(client, chatModel))

    val binding = NettySyncServer()
      .host("0.0.0.0")
      .port(8080)
      .addEndpoint(chatServerEndpoint)
      .start()

    releaseAfterScope(binding.stop())
    logger.info(s"Consultant listening on ${binding.hostName}:${binding.port} (model: $model)")
    never