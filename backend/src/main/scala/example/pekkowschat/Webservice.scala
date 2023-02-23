package example.pekkowschat

import java.util.Date

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.http.scaladsl.model.ws.{ Message, TextMessage }

import scala.concurrent.duration._
import org.apache.pekko.http.scaladsl.server.{ Directives, Route }
import org.apache.pekko.stream.scaladsl.Flow
import upickle.default._
import shared.Protocol
import shared.Protocol._

import scala.util.Failure

class Webservice(implicit system: ActorSystem) extends Directives {
  val theChat = Chat.create()
  import system.dispatcher
  system.scheduler.scheduleAtFixedRate(15.second, 15.second) { () =>
    theChat.injectMessage(ChatMessage(sender = "clock", s"Bling! The time is ${new Date().toString}."))
  }

  val route: Route =
    get {
      pathSingleSlash {
        getFromResource("web/index.html")
      } ~
        // Scala-JS puts them in the root of the resource directory per default,
        // so that's where we pick them up
        path("frontend-launcher.js")(getFromResource("frontend-launcher.js")) ~
        path("frontend-fastopt.js")(getFromResource("frontend-fastopt.js")) ~
        path("chat") {
          parameter("name") { name =>
            handleWebSocketMessages(websocketChatFlow(sender = name))
          }
        }
    } ~
      getFromResourceDirectory("web")

  def websocketChatFlow(sender: String): Flow[Message, Message, Any] =
    Flow[Message]
      .collect {
        case TextMessage.Strict(msg) => msg // unpack incoming WS text messages...
        // This will lose (ignore) messages not received in one chunk (which is
        // unlikely because chat messages are small) but absolutely possible
        // FIXME: We need to handle TextMessage.Streamed as well.
      }
      .via(theChat.chatFlow(sender)) // ... and route them through the chatFlow ...
      .map {
        case msg: Protocol.Message =>
          TextMessage.Strict(write(msg)) // ... pack outgoing messages into WS JSON messages ...
      }
      .via(reportErrorsFlow) // ... then log any processing errors on stdin

  def reportErrorsFlow[T]: Flow[T, T, Any] =
    Flow[T]
      .watchTermination()((_, f) => f.onComplete {
        case Failure(cause) =>
          println(s"WS stream failed with $cause")
        case _ => // ignore regular completion
      })
}
