package com.thinkmorestupidless.ankka.cli

import com.thinkmorestupidless.ankka.cli.console.*
import munit.FunSuite

import java.io.{ByteArrayOutputStream, PrintStream}
import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.util.concurrent.atomic.AtomicInteger

/**
 * The local console lists a service's gRPC methods beside its HTTP routes, and calls none of them.
 */
final class ConsoleGrpcSuite extends FunSuite:

  private val client  = HttpClient.newHttpClient()
  private val invoked = AtomicInteger()

  private object Cart extends Source:
    def services(): Vector[ServiceSummary] = Vector.empty
    def service(name: String): Option[String] =
      Option.when(name == "cart")(
        """{"name":"cart","instances":[],"components":[],"routes":[""" +
          """{"method":"GET","path":"/carts/{cartId}","streaming":false},""" +
          """{"method":"GRPC","path":"shoppingcart.v1.CartService/GetCart","streaming":false},""" +
          """{"method":"SOCKET","path":"/carts/{cartId}/watch","streaming":true}]}"""
      )
    def traces(name: String): Option[String]              = None
    def topology(name: String): Option[QueryResponse]     = None
    def trace(name: String, id: String): Option[String]   = None
    def session(name: String, id: String): Option[String] = None
    def invoke(name: String, r: InvokeRequest): Option[InvokeResponse] =
      invoked.incrementAndGet()
      Some(InvokeResponse(200, Vector.empty, "{}"))
    def invokeStream(name: String, r: InvokeRequest, onChunk: String => Unit): Boolean =
      invoked.incrementAndGet()
      true
    def query(n: String, c: String, i: String, m: String): Option[QueryResponse] = None

  private def withConsole[A](body: ConsoleServer => A): A =
    val server = ConsoleServer.start(Cart, 0, PrintStream(ByteArrayOutputStream()))
    try body(server)
    finally server.stop()

  private def post(server: ConsoleServer, path: String, method: String) =
    client.send(
      HttpRequest
        .newBuilder(URI.create(s"${server.address}$path"))
        .POST(
          HttpRequest.BodyPublishers
            .ofString(s"""{"method":"$method","path":"/x","body":"","contentType":""}""")
        )
        .build(),
      HttpResponse.BodyHandlers.ofString()
    )

  test("the local console lists a service's gRPC methods and does not offer to call them") {
    withConsole { server =>
      val inventory = client.send(
        HttpRequest.newBuilder(URI.create(s"${server.address}/api/service/cart")).build(),
        HttpResponse.BodyHandlers.ofString()
      )
      assert(inventory.body.contains("\"GRPC\""), inventory.body)
      assert(inventory.body.contains("\"GET\""), inventory.body)
      Seq("/api/invoke/cart", "/api/invoke-stream/cart").foreach { path =>
        val refused = post(server, path, "GRPC")
        assertEquals(refused.statusCode, 400, path)
        assert(refused.body.contains("does not call gRPC methods"), refused.body)
      }
      assertEquals(invoked.get, 0)
      assertEquals(post(server, "/api/invoke/cart", "GET").statusCode, 200)
    }
  }

  test("the local console lists a service's socket routes beside its routes and opens none") {
    withConsole { server =>
      val inventory = client.send(
        HttpRequest.newBuilder(URI.create(s"${server.address}/api/service/cart")).build(),
        HttpResponse.BodyHandlers.ofString()
      )
      assert(inventory.body.contains("\"SOCKET\""), inventory.body)
      val before = invoked.get
      Seq("/api/invoke/cart", "/api/invoke-stream/cart").foreach { path =>
        val refused = post(server, path, "SOCKET")
        assertEquals(refused.statusCode, 400, path)
        assert(refused.body.contains("does not open sockets"), refused.body)
      }
      assertEquals(invoked.get, before)
    }
  }
