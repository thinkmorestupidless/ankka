package com.thinkmorestupidless.ankka.controlplane

import com.thinkmorestupidless.ankka.operator.{BrokerStack, PkiStack}
import org.testcontainers.images.builder.Transferable
import org.testcontainers.k3s.K3sContainer

import java.nio.charset.StandardCharsets

/**
 * Something holding the credential of a service: a pod in the service's namespace, of the broker's
 * own image, mounting the service's certificate and nothing else, with the broker's own tools.
 *
 * It is how a suite asks the broker a question as a service would, without the service: what is on
 * a topic, whether a topic or a group is refused, which topics exist. Each answer is what the tools
 * printed, with their exit code, so a refusal is asserted in the broker's own words.
 *
 * It is labelled as a platform workload of an ankka namespace, which is what the broker's listener
 * admits; it is not a member of any service.
 */
final class BrokerProbe private (k3s: K3sContainer, namespace: String, val pod: String):

  private val Bin       = "/opt/kafka/bin"
  private val Config    = "/tmp/client.properties"
  private val Bootstrap = BrokerStack.settings.bootstrap

  /** Runs `script` in the probe's shell. */
  private def run(script: String): BrokerProbe.Result =
    val r = k3s.execInContainer("kubectl", "exec", "-n", namespace, pod, "--", "bash", "-c", script)
    BrokerProbe.Result(r.getExitCode, (r.getStdout + r.getStderr).trim)

  private def quoted(s: String) = "'" + s.replace("'", "'\\''") + "'"

  /**
   * Publishes `value` to `topic`, the name the broker holds. Waits at most `waitMs` for the topic,
   * since a producer of a topic that does not exist waits for it rather than failing.
   */
  def publish(topic: String, value: String, waitMs: Int = 15000): BrokerProbe.Result =
    run(
      s"echo ${quoted(value)} | $Bin/kafka-console-producer.sh --bootstrap-server $Bootstrap " +
        s"--topic ${quoted(topic)} --producer.config $Config " +
        s"--producer-property max.block.ms=$waitMs --producer-property request.timeout.ms=$waitMs"
    )

  /** Reads up to `max` messages of `topic` from its beginning, as the group `group`. */
  def read(topic: String, group: String, max: Int = 100, waitMs: Int = 20000): BrokerProbe.Result =
    run(
      s"$Bin/kafka-console-consumer.sh --bootstrap-server $Bootstrap --topic ${quoted(topic)} " +
        s"--group ${quoted(group)} --from-beginning --max-messages $max --timeout-ms $waitMs " +
        s"--consumer.config $Config"
    )

  /** The topics the broker lets this credential see. */
  def topics(): BrokerProbe.Result =
    run(s"$Bin/kafka-topics.sh --bootstrap-server $Bootstrap --list --command-config $Config")

object BrokerProbe:

  /** What the broker's tools printed, and how they exited. */
  final case class Result(code: Int, output: String):
    /** The broker's own refusal, by the name Kafka's clients give it. */
    def refused: Boolean = output.contains("AuthorizationException")

    /** The lines that are messages: everything the tools printed that is not a log line. */
    def messages: Vector[String] =
      output.linesIterator
        .map(_.trim)
        .filter(l => l.nonEmpty && !l.startsWith("[") && !l.contains("Processed a total of"))
        .filterNot(l => l.contains("WARN") || l.contains("ERROR") || l.contains("Exception"))
        .toVector

  /**
   * Starts (or keeps) the probe holding `service`'s certificate in `namespace`, named for the
   * service, and waits for it to be ready. The image is the one the broker runs, read from its pod,
   * so the node already holds it and the tools speak the broker's version.
   */
  def holding(k3s: K3sContainer, namespace: String, service: String): BrokerProbe =
    val image = PkiStack.jsonPath(
      k3s,
      "pods",
      "-n",
      BrokerStack.Namespace,
      "-l",
      s"strimzi.io/cluster=${BrokerStack.Cluster},strimzi.io/name=${BrokerStack.Cluster}-kafka",
      "{.items[0].spec.containers[0].image}"
    )
    if image.isEmpty then throw new AssertionError("no broker pod to take the image from")
    val pod = s"broker-probe-$service"
    // The keystore is the service's key and certificate in one PEM file, which Kafka's clients read
    // as they are; the truststore is the authority's certificate the service already mounts. Both
    // are copied at start, so the probe still speaks as the service once the service is deleted.
    val start =
      "cat /tls/tls.key /tls/tls.crt > /tmp/keystore.pem && cp /tls/ca.crt /tmp/ca.crt && " +
        "printf '%s\\n' 'security.protocol=SSL' 'ssl.keystore.type=PEM' " +
        "'ssl.keystore.location=/tmp/keystore.pem' 'ssl.truststore.type=PEM' " +
        "'ssl.truststore.location=/tmp/ca.crt' > /tmp/client.properties && sleep infinity"
    val manifest =
      s"""apiVersion: v1
         |kind: Pod
         |metadata:
         |  name: $pod
         |  namespace: $namespace
         |  labels: { app.kubernetes.io/managed-by: ankka }
         |spec:
         |  containers:
         |    - name: probe
         |      image: $image
         |      imagePullPolicy: IfNotPresent
         |      command: ["bash", "-c", ${"\"" + start
          .replace("\\", "\\\\")
          .replace("\"", "\\\"") + "\""}]
         |      env:
         |        - { name: KAFKA_HEAP_OPTS, value: "-Xmx128m" }
         |      volumeMounts:
         |        - { name: tls, mountPath: /tls, readOnly: true }
         |  volumes:
         |    - name: tls
         |      secret: { secretName: $service-service-tls }
         |""".stripMargin
    val file = s"/tmp/broker-probe-$namespace-$service.yaml"
    k3s.copyFileToContainer(Transferable.of(manifest.getBytes(StandardCharsets.UTF_8)), file)
    PkiStack.kubectl(k3s, "apply", "-f", file): Unit
    PkiStack.kubectl(
      k3s,
      "wait",
      "-n",
      namespace,
      "--for=condition=Ready",
      s"pod/$pod",
      "--timeout=180s"
    ): Unit
    new BrokerProbe(k3s, namespace, pod)
