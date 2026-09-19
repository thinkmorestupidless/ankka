package com.thinkmorestupidless.ankka.operator

import io.fabric8.kubernetes.client.KubernetesClientBuilder
import com.thinkmorestupidless.ankka.crd.AnkkaSerialization
import org.slf4j.LoggerFactory

/**
 * The operator process.
 *
 * `main` is a one-line wrapper over `run`, which returns an exit code — the same shape the CLI
 * uses, and for the same reason: `sys.exit` inside the logic would kill a test JVM.
 */
object Main:

  def main(args: Array[String]): Unit = sys.exit(run(args))

  def run(args: Array[String]): Int =
    val log = LoggerFactory.getLogger("ankka.operator")
    val _   = args
    try
      val settings = Settings.fromEnvironment()
      // Credentials come from the operator's own in-cluster identity: fabric8 resolves the
      // service account, then KUBECONFIG, then ~/.kube/config. The control plane never
      // supplies one, which is the point of running the operator in the cluster.
      val client = new KubernetesClientBuilder()
        .withKubernetesSerialization(AnkkaSerialization())
        .build()

      val operator = new Operator(client, settings, ServiceReconciler(client, settings))
      Runtime.getRuntime.addShutdownHook(new Thread(() =>
        operator.close()
        client.close()
      ))

      operator.start()
      operator.awaitTermination()
      0
    catch
      case error: Throwable =>
        log.error("operator failed to start", error)
        1
