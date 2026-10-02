package com.thinkmorestupidless.ankka.grpc

import ankka.fixtures.v1.cart.*
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.{Level, Logger}
import ch.qos.logback.core.read.ListAppender
import com.thinkmorestupidless.ankka.http.{Acl, Callers}
import com.typesafe.config.ConfigFactory
import io.grpc.{Status, StatusRuntimeException}
import org.slf4j.LoggerFactory

import java.util.concurrent.TimeUnit
import scala.jdk.CollectionConverters.*

/**
 * What the server writes to the service's log: a handler's failure, whole, while its caller is told
 * nothing of it; and, on a developer's machine, once, that named callers are not enforced there.
 */
class GrpcLoggingSuite extends munit.FunSuite:

  private val logs = ListAppender[ILoggingEvent]()
  private def logger =
    LoggerFactory.getLogger("com.thinkmorestupidless.ankka.grpc.GrpcServer").asInstanceOf[Logger]

  override def beforeEach(context: BeforeEach): Unit =
    logs.list.clear()
    logs.start()
    logger.addAppender(logs)

  override def afterEach(context: AfterEach): Unit =
    logger.detachAppender(logs)
    logs.stop()

  private def lines(level: Level): Vector[ILoggingEvent] =
    logs.list.asScala.toVector.filter(_.getLevel == level)

  private final class Failing(acl0: Acl) extends GrpcEndpoint(CartServiceGrpc.SERVICE):
    val acl: Acl = acl0
    unary(CartServiceGrpc.METHOD_GET_CART)(_ =>
      throw RuntimeException("the database password is hunter2")
    )
    unary(CartServiceGrpc.METHOD_ADD_ITEM)(request => Cart(request.cartId))

  private def serving[A](endpoint: GrpcEndpoint)(body: GrpcServer => A): A =
    val server = GrpcServer.at("127.0.0.1", 0)()
    server.serve(Vector(endpoint), "127.0.0.1", 0, ConfigFactory.load())
    try body(server)
    finally server.stop()

  test("a handler's failure is written to the log, and its caller is told nothing of it") {
    serving(Failing(Acl.AllowAll)) { server =>
      val channel = GrpcChannels.plaintext(server.boundPort.get)
      try
        val failure = intercept[StatusRuntimeException](
          CartServiceGrpc.blockingStub(channel).getCart(GetCartRequest("c1"))
        )
        assertEquals(failure.getStatus.getCode, Status.Code.INTERNAL)
        assert(!failure.getStatus.toString.contains("hunter2"), failure.getStatus.toString)

        val logged = lines(Level.ERROR)
        assertEquals(logged.size, 1, logged.map(_.getFormattedMessage).toString)
        assert(logged.head.getFormattedMessage.contains("ankka.fixtures.v1.CartService/GetCart"))
        assertEquals(
          Option(logged.head.getThrowableProxy).map(_.getMessage),
          Some("the database password is hunter2")
        )
      finally channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS): Unit
    }
  }

  test(
    "on a developer's machine the service says once at startup that named callers are not enforced"
  ) {
    def notices =
      lines(Level.INFO).count(_.getFormattedMessage.contains("caller identity is not enforced"))
    serving(Failing(Acl.allowCallers(Callers.service("checkout")))) { _ =>
      assertEquals(notices, 1)
    }
    logs.list.clear()
    // An endpoint that names no caller has nothing it could fail to enforce.
    serving(Failing(Acl.AllowAll))(_ => assertEquals(notices, 0))
  }
