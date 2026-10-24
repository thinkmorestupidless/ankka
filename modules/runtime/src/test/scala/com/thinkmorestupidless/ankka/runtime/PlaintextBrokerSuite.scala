package com.thinkmorestupidless.ankka.runtime

import munit.FunSuite

/**
 * A service that names the installation's broker in the clear is refused at start, with the reason
 * and the fix, rather than dying of heap exhaustion (issue #108). The decision alone: no broker, no
 * actor system.
 */
final class PlaintextBrokerSuite extends FunSuite:

  private val tls = Map(
    KafkaConnection.BootstrapVariable    -> "ankka-kafka-bootstrap.ankka-broker:9093",
    KafkaConnection.TlsDirectoryVariable -> "/var/run/secrets/ankka/service"
  )

  test("a plaintext connection on an installation with a TLS broker is refused, naming fromEnv") {
    val why = ProjectionRuntime.plaintextAgainstTls(KafkaConnection("kafka:9093"), tls)
    assert(why.isDefined)
    assert(why.get.contains("kafka:9093"), why.get)
    assert(why.get.contains("ProjectionRuntime.fromEnv()"), why.get)
    assert(why.get.contains(KafkaConnection.TlsDirectoryVariable), why.get)
    assert(why.get.contains("Java heap space"), why.get)
  }

  test("the connection fromEnv builds on that installation carries the certificate and passes") {
    val connection = KafkaConnection.fromEnv(tls).get
    assertEquals(connection.tlsDirectory, Some("/var/run/secrets/ankka/service"))
    assertEquals(ProjectionRuntime.plaintextAgainstTls(connection, tls), None)
  }

  test(
    "a plaintext connection where the platform gave no TLS directory passes: a broker of its own"
  ) {
    val own = Map(KafkaConnection.BootstrapVariable -> "kafka:9092")
    assertEquals(ProjectionRuntime.plaintextAgainstTls(KafkaConnection("kafka:9092"), own), None)
    assertEquals(
      ProjectionRuntime.plaintextAgainstTls(KafkaConnection("kafka:9092"), Map.empty),
      None
    )
  }

  test("a declared credential is not plaintext") {
    val connection =
      KafkaConnection("kafka:9093", credential = Some(KafkaCredential.Sasl("/var/run/broker")))
    assertEquals(ProjectionRuntime.plaintextAgainstTls(connection, tls), None)
  }

  test("a blank TLS directory is no TLS directory") {
    val blank = tls.updated(KafkaConnection.TlsDirectoryVariable, "  ")
    assertEquals(ProjectionRuntime.plaintextAgainstTls(KafkaConnection("kafka:9093"), blank), None)
  }
