package com.thinkmorestupidless.ankka.operator

import org.testcontainers.images.builder.Transferable
import org.testcontainers.k3s.K3sContainer

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.concurrent.duration.*

/**
 * An ACME authority inside a k3s node, for custom hostnames (feature 045, research R15): Let's
 * Encrypt's test server Pebble, and `pebble-challtestsrv` as every resolver that matters —
 * Pebble's, when it checks a challenge, and cert-manager's, when it checks its own first. A name
 * resolves to the installation when a suite says so (`addA`) and to nothing otherwise, so a
 * challenge passes or fails for the reason a real one would.
 *
 * `ClusterIssuer pebble` answers HTTP-01 through the installation's Gateway's plain listener, as
 * the cloud overlay's `letsencrypt-hostnames` does. Install after `GatewayStack`, whose
 * cert-manager this patches with the resolver. Spike S1 (`ListenerSetSpike`) is what showed each
 * piece works.
 */
object AcmeStack:

  val Namespace: String = "acme-test"
  val Issuer: String    = "pebble"
  val Pebble: String    = "ghcr.io/letsencrypt/pebble:2.10.1"
  val Resolver: String  = "ghcr.io/letsencrypt/pebble-challtestsrv:2.10.1"

  /**
   * @param resolverIp
   *   challtestsrv's cluster address, whose port 8053 answers DNS
   * @param gatewayIp
   *   the cluster address of the Gateway's Envoy Service, what a name "resolving to the
   *   installation" resolves to
   * @param root
   *   Pebble's root, in a temp file, for `curl --cacert` and a gRPC client's trust
   */
  final case class Installed(k3s: K3sContainer, resolverIp: String, gatewayIp: String, root: Path):

    /** The name resolves to the installation's gateway. */
    def resolveToInstallation(host: String): Unit = addA(host, gatewayIp)

    /** The name resolves to an address nothing answers on port 80: every challenge for it fails. */
    def resolveToNothingThatAnswers(host: String): Unit = addA(host, resolverIp)

    def addA(host: String, ip: String): Unit =
      clearA(host)
      manage("/add-a", s"""{"host":"$host","addresses":["$ip"]}""")

    def clearA(host: String): Unit = manage("/clear-a", s"""{"host":"$host"}""")

    private def manage(path: String, body: String): Unit =
      val out = kubectl(
        k3s,
        "-n",
        Namespace,
        "exec",
        "deploy/curl",
        "--",
        "curl",
        "-sS",
        "-f",
        "-X",
        "POST",
        "-d",
        body,
        s"http://resolver.$Namespace.svc:8055$path"
      )
      val _ = out

  def install(k3s: K3sContainer): Installed =
    // Third-party images this build does not make: pulled into the local daemon first, since the
    // import exports what the daemon holds and pulls nothing.
    for image <- Vector(Pebble, Resolver) do
      val pulled = new ProcessBuilder("docker", "pull", "-q", image).inheritIO().start().waitFor()
      if pulled != 0 then throw new AssertionError(s"docker pull $image failed")
      ClusterImages.importInto(k3s, image)
    apply(k3s, stack)
    waitFor(240.seconds, "Pebble, its resolver and a curl pod run") {
      Vector("pebble", "resolver", "curl").forall { d =>
        PkiStack.jsonPath(k3s, "-n", Namespace, "deployment", d, "{.status.readyReplicas}") == "1"
      }
    }
    val resolverIp =
      PkiStack.jsonPath(k3s, "-n", Namespace, "service", "resolver", "{.spec.clusterIP}")
    val gatewayIp = kubectl(
      k3s,
      "-n",
      "envoy-gateway-system",
      "get",
      "service",
      "-l",
      "gateway.envoyproxy.io/owning-gateway-name=ankka",
      "-o",
      "jsonpath={.items[0].spec.clusterIP}"
    ).trim
    // cert-manager checks a challenge itself before telling the authority to; it must ask the same
    // resolver Pebble asks, or it would never see a name resolve.
    kubectl(
      k3s,
      "-n",
      "cert-manager",
      "patch",
      "deployment",
      "cert-manager",
      "--type",
      "json",
      "-p",
      s"""[{"op":"add","path":"/spec/template/spec/containers/0/args/-","value":"--acme-http01-solver-nameservers=$resolverIp:8053"}]"""
    ): Unit
    kubectl(
      k3s,
      "-n",
      "cert-manager",
      "rollout",
      "status",
      "deployment/cert-manager",
      "--timeout=180s"
    ): Unit
    apply(k3s, issuer)
    waitFor(120.seconds, "ClusterIssuer pebble registers its account") {
      PkiStack.jsonPath(
        k3s,
        "clusterissuer",
        Issuer,
        """{.status.conditions[?(@.type=="Ready")].status}"""
      ) == "True"
    }
    val pem = kubectl(
      k3s,
      "-n",
      Namespace,
      "exec",
      "deploy/curl",
      "--",
      "curl",
      "-sSk",
      s"https://pebble.$Namespace.svc:15000/roots/0"
    )
    val root = Files.createTempFile("pebble-root", ".pem")
    Files.writeString(root, pem)
    Installed(k3s, resolverIp, gatewayIp, root)

  /** One file trusting both roots: the local authority's (the derived hostname) and Pebble's. */
  def bundle(roots: Path*): Path =
    val file = Files.createTempFile("ankka-roots", ".pem")
    Files.writeString(file, roots.map(Files.readString(_)).mkString("\n"))
    file

  private val stack: String =
    s"""apiVersion: v1
       |kind: Namespace
       |metadata: { name: $Namespace }
       |---
       |apiVersion: apps/v1
       |kind: Deployment
       |metadata: { name: resolver, namespace: $Namespace }
       |spec:
       |  selector: { matchLabels: { app: resolver } }
       |  template:
       |    metadata: { labels: { app: resolver } }
       |    spec:
       |      containers:
       |        - name: resolver
       |          image: $Resolver
       |          imagePullPolicy: IfNotPresent
       |          args: ["-dnsserver", ":8053", "-management", ":8055", "-defaultIPv4", "", "-defaultIPv6", "",
       |                 "-http01", "", "-https01", "", "-tlsalpn01", "", "-doh", ""]
       |---
       |apiVersion: v1
       |kind: Service
       |metadata: { name: resolver, namespace: $Namespace }
       |spec:
       |  selector: { app: resolver }
       |  ports:
       |    - { name: dns, port: 8053, protocol: UDP }
       |    - { name: dns-tcp, port: 8053, protocol: TCP }
       |    - { name: management, port: 8055, protocol: TCP }
       |---
       |apiVersion: v1
       |kind: ConfigMap
       |metadata: { name: pebble, namespace: $Namespace }
       |data:
       |  pebble-config.json: |
       |    {"pebble": {"listenAddress": "0.0.0.0:14000", "managementListenAddress": "0.0.0.0:15000",
       |      "certificate": "test/certs/localhost/cert.pem", "privateKey": "test/certs/localhost/key.pem",
       |      "httpPort": 80, "tlsPort": 443, "ocspResponderURL": "", "externalAccountBindingRequired": false}}
       |---
       |apiVersion: apps/v1
       |kind: Deployment
       |metadata: { name: pebble, namespace: $Namespace }
       |spec:
       |  selector: { matchLabels: { app: pebble } }
       |  template:
       |    metadata: { labels: { app: pebble } }
       |    spec:
       |      containers:
       |        - name: pebble
       |          image: $Pebble
       |          imagePullPolicy: IfNotPresent
       |          args: ["-config", "/etc/pebble/pebble-config.json", "-dnsserver", "resolver.$Namespace.svc.cluster.local:8053"]
       |          env:
       |            - { name: PEBBLE_VA_NOSLEEP, value: "1" }
       |            - { name: PEBBLE_WFE_NONCEREJECT, value: "0" }
       |            # Every order is challenged afresh: an authority reuses a valid authorization for
       |            # the same account (Let's Encrypt for 30 days), so a name validated by one scenario
       |            # would be issued again in the next without resolving at all.
       |            - { name: PEBBLE_AUTHZREUSE, value: "0" }
       |          volumeMounts: [{ name: config, mountPath: /etc/pebble }]
       |      volumes: [{ name: config, configMap: { name: pebble } }]
       |---
       |apiVersion: v1
       |kind: Service
       |metadata: { name: pebble, namespace: $Namespace }
       |spec:
       |  selector: { app: pebble }
       |  ports:
       |    - { name: acme, port: 14000 }
       |    - { name: management, port: 15000 }
       |---
       |apiVersion: apps/v1
       |kind: Deployment
       |metadata: { name: curl, namespace: $Namespace }
       |spec:
       |  selector: { matchLabels: { app: curl } }
       |  template:
       |    metadata: { labels: { app: curl } }
       |    spec:
       |      containers:
       |        - name: curl
       |          image: curlimages/curl:8.10.1
       |          command: ["sleep", "infinity"]
       |""".stripMargin

  private val issuer: String =
    s"""apiVersion: cert-manager.io/v1
       |kind: ClusterIssuer
       |metadata: { name: $Issuer }
       |spec:
       |  acme:
       |    server: https://pebble.$Namespace.svc:14000/dir
       |    skipTLSVerify: true
       |    privateKeySecretRef: { name: pebble-account }
       |    solvers:
       |      - http01:
       |          gatewayHTTPRoute:
       |            parentRefs:
       |              - { group: gateway.networking.k8s.io, kind: Gateway, name: ankka, namespace: ankka-gateway, sectionName: http }
       |""".stripMargin

  private def apply(k3s: K3sContainer, yaml: String): Unit =
    k3s.copyFileToContainer(
      Transferable.of(yaml.getBytes(StandardCharsets.UTF_8)),
      "/tmp/acme.yaml"
    )
    waitFor(60.seconds, "the ACME stack applies") {
      k3s
        .execInContainer(
          "kubectl",
          "apply",
          "--server-side",
          "--force-conflicts",
          "-f",
          "/tmp/acme.yaml"
        )
        .getExitCode == 0
    }

  private def kubectl(k3s: K3sContainer, args: String*): String = PkiStack.kubectl(k3s, args*)

  private def waitFor(timeout: FiniteDuration, what: String)(check: => Boolean): Unit =
    val deadline = System.nanoTime() + timeout.toNanos
    var passed   = false
    while !passed && System.nanoTime() < deadline do
      passed =
        try check
        catch case _: Exception => false
      if !passed then Thread.sleep(2000)
    if !passed then throw new AssertionError(s"$what did not happen within $timeout")
