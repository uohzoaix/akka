/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package docs.stream

import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference
import akka.actor.ActorSystem
import akka.stream._
import akka.stream.scaladsl._
import akka.stream.stage.{ PushStage, SyncDirective, Context }
import akka.stream.testkit.AkkaSpec
import akka.testkit.TestProbe
import akka.util.ByteString
import cookbook.RecipeParseLines
import StreamTcp._

import scala.concurrent.Future

class StreamTcpDocSpec extends AkkaSpec {

  implicit val ec = system.dispatcher
  implicit val mat = ActorFlowMaterializer()

  // silence sysout
  def println(s: String) = ()

  val localhost = new InetSocketAddress("127.0.0.1", 8888)

  "simple server connection" ignore {
    //#echo-server-simple-bind
    val localhost = new InetSocketAddress("127.0.0.1", 8888)
    //#echo-server-simple-handle
    val connections: Source[IncomingConnection, Future[ServerBinding]] = StreamTcp().bind(localhost)
    //#echo-server-simple-bind

    connections runForeach { connection =>
      println(s"New connection from: ${connection.remoteAddress}")

      val echo = Flow[ByteString]
        .transform(() => RecipeParseLines.parseLines("\n", maximumLineBytes = 256))
        .map(_ + "!!!\n")
        .map(ByteString(_))

      connection.handleWith(echo)
    }
    //#echo-server-simple-handle
  }

  "simple repl client" ignore {
    val sys: ActorSystem = ???

    //#repl-client
    val connection: Flow[ByteString, ByteString, Future[OutgoingConnection]] = StreamTcp().outgoingConnection(localhost)

    val repl = Flow[ByteString]
      .transform(() => RecipeParseLines.parseLines("\n", maximumLineBytes = 256))
      .map(text => println("Server: " + text))
      .map(_ => readLine("> "))
      .map {
        case "q" =>
          sys.shutdown(); ByteString("BYE")
        case text => ByteString(s"$text")
      }

    connection.join(repl)
    //#repl-client
  }

  "initial server banner echo server" ignore {
    val connections = StreamTcp().bind(localhost)
    val serverProbe = TestProbe()

    //#welcome-banner-chat-server
    connections runForeach { connection =>

      val serverLogic = Flow() { implicit b =>
        import FlowGraph.Implicits._

        // server logic, parses incoming commands
        val commandParser = new PushStage[String, String] {
          override def onPush(elem: String, ctx: Context[String]): SyncDirective = {
            elem match {
              case "BYE" ⇒ ctx.finish()
              case _     ⇒ ctx.push(elem + "!")
            }
          }
        }

        import connection._
        val welcomeMsg = s"Welcome to: $localAddress, you are: $remoteAddress!\n"

        val welcome = Source.single(ByteString(welcomeMsg))
        val echo = b.add(Flow[ByteString]
          .transform(() => RecipeParseLines.parseLines("\n", maximumLineBytes = 256))
          //#welcome-banner-chat-server
          .map { command ⇒ serverProbe.ref ! command; command }
          //#welcome-banner-chat-server
          .transform(() ⇒ commandParser)
          .map(_ + "\n")
          .map(ByteString(_)))

        val concat = b.add(Concat[ByteString]())
        // first we emit the welcome message,
        welcome ~> concat.in(0)
        // then we continue using the echo-logic Flow
        echo.outlet ~> concat.in(1)

        (echo.inlet, concat.out)
      }

      connection.handleWith(serverLogic)
    }

    //#welcome-banner-chat-server

    val input = new AtomicReference("Hello world" :: "What a lovely day" :: Nil)
    def readLine(prompt: String): String = {
      input.get() match {
        case all @ cmd :: tail if input.compareAndSet(all, tail) ⇒ cmd
        case _ ⇒ "q"
      }
    }

    //#repl-client
    val connection = StreamTcp().outgoingConnection(localhost)

    val replParser = new PushStage[String, ByteString] {
      override def onPush(elem: String, ctx: Context[ByteString]): SyncDirective = {
        elem match {
          case "q" ⇒ ctx.pushAndFinish(ByteString("BYE\n"))
          case _   ⇒ ctx.push(ByteString(s"$elem\n"))
        }
      }
    }

    val repl = Flow[ByteString]
      .transform(() => RecipeParseLines.parseLines("\n", maximumLineBytes = 256))
      .map(text => println("Server: " + text))
      .map(_ => readLine("> "))
      .transform(() ⇒ replParser)

    connection.join(repl)
    //#repl-client

    serverProbe.expectMsg("Hello world")
    serverProbe.expectMsg("What a lovely day")
    serverProbe.expectMsg("BYE")
  }
}
