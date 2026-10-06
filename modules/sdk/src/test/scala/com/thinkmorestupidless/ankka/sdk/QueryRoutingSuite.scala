package com.thinkmorestupidless.ankka.sdk

import com.thinkmorestupidless.ankka.core.*

import scala.concurrent.Future
import scala.concurrent.duration.*

/**
 * The client tells the transport which calls are queries: a read-only handle goes through
 * `askQuery`, which a transport may send again, and anything else through `ask`, which it may not.
 */
class QueryRoutingSuite extends munit.FunSuite:

  private final class Recording extends CallTransport:
    var asked = Vector.empty[String]
    def ask(c: ComponentId, e: EntityId, m: MethodName, p: Array[Byte], md: Metadata) =
      asked :+= s"ask $m"; Future.successful(Serializers.string.toBytes("ok"))
    override def askQuery(
        c: ComponentId,
        e: EntityId,
        m: MethodName,
        p: Array[Byte],
        md: Metadata
    ) =
      asked :+= s"askQuery $m"; Future.successful(Serializers.string.toBytes("ok"))
    def tell(c: ComponentId, e: EntityId, message: Any): Unit = ()
    def askTimeout: FiniteDuration                            = 1.second

  private val Cart = ComponentId("cart")

  private def oneArg(name: String, readOnly: Boolean) =
    CommandHandle[Any, String, String](
      Cart,
      MethodName(name),
      readOnly,
      Serializers.string,
      Serializers.string,
      (_, _) => ()
    )

  private def noArg(name: String, readOnly: Boolean) =
    NoArgHandle[Any, String](Cart, MethodName(name), readOnly, Serializers.string, _ => ())

  test("a query goes through askQuery and a command through ask, with or without an argument") {
    val t = Recording()
    Invocation(t, EntityId("c1"), oneArg("get-item", readOnly = true)).invoke("p1"): Unit
    Invocation(t, EntityId("c1"), oneArg("add-item", readOnly = false)).invoke("p1"): Unit
    NoArgInvocation(t, EntityId("c1"), noArg("get-cart", readOnly = true)).invoke(): Unit
    NoArgInvocation(t, EntityId("c1"), noArg("checkout", readOnly = false)).invoke(): Unit
    assertEquals(
      t.asked,
      Vector("askQuery get-item", "ask add-item", "askQuery get-cart", "ask checkout")
    )
  }

  test("a transport that says nothing of queries answers them with ask") {
    var asked = 0
    val plain = new CallTransport:
      def ask(c: ComponentId, e: EntityId, m: MethodName, p: Array[Byte], md: Metadata) =
        asked += 1; Future.successful(Serializers.string.toBytes("ok"))
      def tell(c: ComponentId, e: EntityId, message: Any): Unit = ()
      def askTimeout: FiniteDuration                            = 1.second
    NoArgInvocation(plain, EntityId("c1"), noArg("get-cart", readOnly = true)).invoke(): Unit
    assertEquals(asked, 1)
  }
