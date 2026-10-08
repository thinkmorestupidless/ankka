package com.thinkmorestupidless.ankka.runtime

import java.nio.file.Files

/** features/topics/brokers.feature: how a declared broker is reached (feature 037). */
class KafkaCredentialSuite extends munit.FunSuite:

  test("a SASL credential is the secret's entries over TLS, with the authority as PEM") {
    val dir = Files.createTempDirectory("broker")
    Files.writeString(
      dir.resolve("ca.crt"),
      "-----BEGIN CERTIFICATE-----\nMIIB\n-----END CERTIFICATE-----\n"
    )
    Files.writeString(dir.resolve("username"), "ingest\n")
    Files.writeString(dir.resolve("password"), "s3cr\"et")
    val properties = KafkaCredential.Sasl(dir.toString).properties
    assertEquals(properties("security.protocol"), "SASL_SSL")
    assertEquals(properties("sasl.mechanism"), "SCRAM-SHA-512")
    assertEquals(
      properties("sasl.jaas.config"),
      "org.apache.kafka.common.security.scram.ScramLoginModule required username=\"ingest\" password=\"s3cr\\\"et\";"
    )
    assertEquals(properties("ssl.truststore.type"), "PEM")
    assert(properties("ssl.truststore.certificates").startsWith("-----BEGIN CERTIFICATE-----"))
    Files.writeString(dir.resolve("mechanism"), "PLAIN")
    assert(
      KafkaCredential
        .Sasl(dir.toString)
        .properties("sasl.jaas.config")
        .startsWith("org.apache.kafka.common.security.plain.PlainLoginModule")
    )
  }

  test("a certificate credential is the installation's own engine over the directory") {
    val properties = KafkaCredential.Certificate("/var/run/secrets/ankka/brokers/legacy").properties
    assertEquals(properties("security.protocol"), "SSL")
    assertEquals(properties(KafkaTls.DirectoryConfig), "/var/run/secrets/ankka/brokers/legacy")
  }

  test("the declared brokers are read from the environment, each with no topic prefix") {
    val env = Map(
      "ANKKA_TOPIC_BROKER_LEGACY_NAME"              -> "legacy",
      "ANKKA_TOPIC_BROKER_LEGACY_BOOTSTRAP_SERVERS" -> "kafka.legacy:9094",
      "ANKKA_TOPIC_BROKER_LEGACY_SHAPE"             -> "sasl",
      "ANKKA_TOPIC_BROKER_LEGACY_SECRET_DIRECTORY"  -> "/var/run/secrets/ankka/brokers/legacy",
      "ANKKA_KAFKA_BOOTSTRAP_SERVERS" -> "ankka-kafka-bootstrap.ankka-broker.svc:9093",
      "ANKKA_KAFKA_TOPIC_PREFIX"      -> "shop."
    )
    val declared = KafkaConnection.declaredFromEnv(env).toOption.get
    assertEquals(declared.keySet, Set("legacy"))
    assertEquals(declared("legacy").bootstrapServers, "kafka.legacy:9094")
    assertEquals(declared("legacy").topicPrefix, "")
    assertEquals(
      declared("legacy").credential,
      Some(KafkaCredential.Sasl("/var/run/secrets/ankka/brokers/legacy"))
    )
    assertEquals(KafkaConnection.declaredFromEnv(Map.empty), Right(Map.empty))
    // The installation's connection is untouched by a declared broker's variables.
    assertEquals(KafkaConnection.fromEnv(env).map(_.topicPrefix), Some("shop."))
    assert(
      KafkaConnection
        .declaredFromEnv(env.updated("ANKKA_TOPIC_BROKER_LEGACY_SHAPE", "plain"))
        .isLeft
    )
  }
