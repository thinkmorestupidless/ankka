package com.thinkmorestupidless.ankka.runtime

import com.sun.net.httpserver.HttpServer

import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files

/**
 * How a service on this machine finds another's addresses: through the running-services directory,
 * by asking the named service's own inventory, now. Driven against a stand-in inventory so it needs
 * no database; `ServiceRegistrationSuite` drives a whole service's announcement.
 */
class ServiceRegistrationLookupSuite extends munit.FunSuite:

  private val directory = Files.createTempDirectory("running")

  override def beforeAll(): Unit = sys.props("ankka.running.dir") = directory.toString
  override def afterAll(): Unit  = sys.props.remove("ankka.running.dir"): Unit

  /** A process announced as `name` whose inventory answers `instance`; stopped by the caller. */
  private def running(name: String, instance: String): HttpServer =
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext(
      "/observability/service",
      exchange =>
        val body = s"""{"name":"$name","instances":[$instance],"routes":[]}""".getBytes(UTF_8)
        exchange.sendResponseHeaders(200, body.length.toLong)
        exchange.getResponseBody.write(body)
        exchange.close()
    )
    server.start()
    Files.writeString(
      directory.resolve(s"$name.json"),
      s"""{"name":"$name","observabilityAddress":"http://127.0.0.1:${server.getAddress.getPort}"}"""
    )
    server

  test("a service that serves both is found at both addresses") {
    val cart = running(
      "cart",
      """{"id":"1","http":{"address":"http://127.0.0.1:9000"},"grpc":{"address":"127.0.0.1:9090"}}"""
    )
    try
      assertEquals(ServiceRegistration.httpAddressOf("cart"), Some("http://127.0.0.1:9000"))
      assertEquals(ServiceRegistration.grpcAddressOf("cart"), Some("127.0.0.1:9090"))
      assert(ServiceRegistration.isAnnounced("cart"))
    finally cart.stop(0)
  }

  test("a service that serves no gRPC is announced and has no gRPC address") {
    val orders = running("orders", """{"id":"1","http":{"address":"http://127.0.0.1:9001"}}""")
    try
      assertEquals(ServiceRegistration.grpcAddressOf("orders"), None)
      assert(ServiceRegistration.isAnnounced("orders"))
    finally orders.stop(0)
  }

  test("a service that serves gRPC and no HTTP has a gRPC address and no HTTP one") {
    val quotes = running("quotes", """{"id":"1","grpc":{"address":"127.0.0.1:9091"}}""")
    try
      assertEquals(ServiceRegistration.httpAddressOf("quotes"), None)
      assertEquals(ServiceRegistration.grpcAddressOf("quotes"), Some("127.0.0.1:9091"))
    finally quotes.stop(0)
  }

  test("a service nobody announced is not announced, and has neither address") {
    assert(!ServiceRegistration.isAnnounced("nobody"))
    assertEquals(ServiceRegistration.grpcAddressOf("nobody"), None)
  }
