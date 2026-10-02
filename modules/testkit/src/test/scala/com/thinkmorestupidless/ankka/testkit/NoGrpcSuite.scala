package com.thinkmorestupidless.ankka.testkit

import scala.util.Try

/**
 * A service that tests no gRPC carries none of it: the test kit brings neither the platform's gRPC
 * module nor grpc-java. A service that serves gRPC depends on `ankka-grpc`, which brings both.
 */
class NoGrpcSuite extends munit.FunSuite:

  private def loadable(name: String) = Try(Class.forName(name)).isSuccess

  test("a service that tests no gRPC carries none of the gRPC library") {
    assert(
      !loadable("com.thinkmorestupidless.ankka.grpc.GrpcServer"),
      "the gRPC module is on the classpath"
    )
    assert(!loadable("io.grpc.ManagedChannel"), "grpc-java is on the test kit's classpath")
  }
