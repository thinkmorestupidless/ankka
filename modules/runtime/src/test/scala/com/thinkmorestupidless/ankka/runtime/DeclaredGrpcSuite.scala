package com.thinkmorestupidless.ankka.runtime

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files

/** A service deployed to serve gRPC that registers nothing to serve it does not start. */
class DeclaredGrpcSuite extends munit.FunSuite:

  private val declared: String => Option[String] = Map("ANKKA_GRPC_PORT" -> "9090").get

  test("declared and unserved: refused, naming the port and what to do") {
    assertEquals(
      DeclaredGrpc.problem(declared, Seq("http-server")),
      Some(
        "the descriptor declares gRPC (ANKKA_GRPC_PORT=9090) and this service registers no gRPC " +
          "endpoint: register GrpcServer.of(…), or remove \"grpc\" from the descriptor"
      )
    )
  }

  test("declared and served: no problem") {
    assertEquals(DeclaredGrpc.problem(declared, Seq("http-server", "grpc-server")), None)
  }

  test("not declared: no problem, whatever is registered") {
    assertEquals(DeclaredGrpc.problem(_ => None, Seq("http-server")), None)
    assertEquals(DeclaredGrpc.problem(_ => None, Seq("grpc-server")), None)
  }

  test("the reason is written to the termination log before the service refuses to start") {
    val log = Files.createTempFile("termination", ".log")
    sys.props("ankka.termination.log") = log.toString
    try
      val failure = intercept[IllegalStateException](DeclaredGrpc.check(declared, Seq.empty))
      assertEquals(Files.readString(log, UTF_8).trim, failure.getMessage)
    finally sys.props.remove("ankka.termination.log"): Unit
  }
