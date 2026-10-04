package com.thinkmorestupidless.ankka.operator

import org.testcontainers.images.builder.Transferable
import org.testcontainers.k3s.K3sContainer

import java.nio.charset.StandardCharsets
import scala.concurrent.duration.*

/**
 * A broker in a test cluster that is not the installation's: one `apache/kafka` pod in KRaft mode
 * with a plaintext listener, and a Service in front of it. What a descriptor names when it brings a
 * broker of its own, and so what proves a supplied broker reaches a service — with no Strimzi, no
 * certificate and nothing of the platform's in it.
 *
 * Topics are made on first use, as a broker a team runs itself may well be configured to.
 */
object PlainKafka:

  val Name: String = "plain-kafka"

  /** Applies the broker in `namespace`, waits for it to answer, and returns its address. */
  def install(k3s: K3sContainer, namespace: String): String =
    val address = s"$Name.$namespace.svc.cluster.local:9092"
    val manifest =
      s"""apiVersion: v1
         |kind: Pod
         |metadata:
         |  name: $Name
         |  namespace: $namespace
         |  labels: { app: $Name }
         |spec:
         |  containers:
         |    - name: kafka
         |      image: apache/kafka:3.8.0
         |      env:
         |        - { name: KAFKA_NODE_ID, value: "1" }
         |        - { name: KAFKA_PROCESS_ROLES, value: "broker,controller" }
         |        - { name: KAFKA_LISTENERS, value: "PLAINTEXT://:9092,CONTROLLER://:9093" }
         |        - { name: KAFKA_ADVERTISED_LISTENERS, value: "PLAINTEXT://$address" }
         |        - { name: KAFKA_CONTROLLER_LISTENER_NAMES, value: CONTROLLER }
         |        - { name: KAFKA_LISTENER_SECURITY_PROTOCOL_MAP, value: "CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT" }
         |        - { name: KAFKA_CONTROLLER_QUORUM_VOTERS, value: "1@localhost:9093" }
         |        - { name: KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR, value: "1" }
         |        - { name: KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR, value: "1" }
         |        - { name: KAFKA_TRANSACTION_STATE_LOG_MIN_ISR, value: "1" }
         |        - { name: KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS, value: "0" }
         |      readinessProbe:
         |        tcpSocket: { port: 9092 }
         |        periodSeconds: 2
         |---
         |apiVersion: v1
         |kind: Service
         |metadata:
         |  name: $Name
         |  namespace: $namespace
         |spec:
         |  selector: { app: $Name }
         |  ports:
         |    - { name: kafka, port: 9092 }
         |""".stripMargin
    k3s.copyFileToContainer(
      Transferable.of(manifest.getBytes(StandardCharsets.UTF_8)),
      s"/tmp/$Name-$namespace.yaml"
    )
    PkiStack.kubectl(k3s, "apply", "-f", s"/tmp/$Name-$namespace.yaml"): Unit
    PkiStack.kubectl(
      k3s,
      "wait",
      "-n",
      namespace,
      s"pod/$Name",
      "--for=condition=Ready",
      s"--timeout=${3.minutes.toSeconds}s"
    ): Unit
    address

  /**
   * Up to `max` values on `topic`, from its beginning, as the broker's own console consumer prints
   * them; an empty vector when nothing arrives within `wait`.
   */
  def read(
      k3s: K3sContainer,
      namespace: String,
      topic: String,
      max: Int,
      wait: FiniteDuration
  ): Vector[String] =
    val result = k3s.execInContainer(
      "kubectl",
      "exec",
      "-n",
      namespace,
      Name,
      "--",
      "/opt/kafka/bin/kafka-console-consumer.sh",
      "--bootstrap-server",
      "localhost:9092",
      "--topic",
      topic,
      "--from-beginning",
      "--max-messages",
      max.toString,
      "--timeout-ms",
      wait.toMillis.toString
    )
    result.getStdout.linesIterator.map(_.trim).filter(_.nonEmpty).toVector
