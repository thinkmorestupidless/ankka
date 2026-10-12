package com.thinkmorestupidless.ankka.operator

import com.thinkmorestupidless.ankka.crd.AnkkaSerialization
import io.fabric8.kubernetes.client.{Config, KubernetesClientBuilder}
import org.testcontainers.images.builder.Transferable
import org.testcontainers.k3s.K3sContainer
import org.testcontainers.utility.DockerImageName

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.concurrent.duration.*

/**
 * Feature 045's spike S1: whether the pinned Envoy Gateway serves a custom hostname as research R1,
 * R5, R6, R9 and R15 assume. One cluster, in order:
 *
 *   1. the shipped gateway component admits a ListenerSet from a managed namespace; a listener in
 *      it, with a certificate from the local `ankka-ca` ClusterIssuer in that namespace, is
 *      Programmed; one route with two parents serves the derived hostname and the custom one;
 *   2. a set's listener for `*.<base>` or `api.<base>` is Conflicted and the base domain still
 *      answers from the wildcard;
 *   3. Pebble, with challtestsrv as every resolver, issues for a name that resolves to the gateway
 *      through cert-manager's Gateway API HTTP-01 solver; the Challenge's reason for a name that
 *      does not resolve is recorded verbatim.
 *
 * It prints what it finds; research.md's "Verified during implementation" records it.
 *
 * sbt -Dankka.spikes=on 'operator/testOnly *ListenerSetSpike'
 */
class ListenerSetSpike extends munit.FunSuite:

  override def munitIgnore: Boolean = !sys.props.get("ankka.spikes").contains("on")
  override val munitTimeout         = 30.minutes

  private val Base      = "example.test"
  private val Namespace = "ankka-spike"
  private val Pebble    = "ghcr.io/letsencrypt/pebble:2.10.1"
  private val Resolver  = "ghcr.io/letsencrypt/pebble-challtestsrv:2.10.1"

  test("a ListenerSet serves a custom hostname, loses every conflict, and Pebble issues for it") {
    val k3s = new K3sContainer(DockerImageName.parse("rancher/k3s:v1.35.1-k3s1"))
    k3s.withExposedPorts(6443, GatewayStack.HttpsNodePort, GatewayStack.HttpNodePort)
    k3s.start()
    try
      val k8s = new KubernetesClientBuilder()
        .withConfig(Config.fromKubeconfig(k3s.getKubeConfigYaml))
        .withKubernetesSerialization(AnkkaSerialization())
        .build()
      val https = k3s.getMappedPort(GatewayStack.HttpsNodePort)
      val root  = PkiStack.repoRoot
      GatewayStack.install(k3s, k8s, root, Base)
      val ca = GatewayStack.exportCa(k8s)

      // ── 1. the set, the certificate from the local authority, the two-parent route ──────────
      apply(
        k3s,
        s"""apiVersion: v1
           |kind: Namespace
           |metadata:
           |  name: $Namespace
           |  labels: { app.kubernetes.io/managed-by: ankka }
           |---
           |apiVersion: apps/v1
           |kind: Deployment
           |metadata: { name: whoami, namespace: $Namespace }
           |spec:
           |  selector: { matchLabels: { app: whoami } }
           |  template:
           |    metadata: { labels: { app: whoami } }
           |    spec:
           |      containers:
           |        - name: whoami
           |          image: traefik/whoami:v1.10.3
           |          ports: [{ containerPort: 80 }]
           |---
           |apiVersion: v1
           |kind: Service
           |metadata: { name: whoami, namespace: $Namespace }
           |spec:
           |  selector: { app: whoami }
           |  ports: [{ name: http, port: 80, targetPort: 80 }]
           |---
           |apiVersion: cert-manager.io/v1
           |kind: Certificate
           |metadata: { name: local.example.com, namespace: $Namespace }
           |spec:
           |  secretName: local.example.com
           |  dnsNames: [local.example.com]
           |  issuerRef: { name: ankka-ca, kind: ClusterIssuer, group: cert-manager.io }
           |""".stripMargin
      )
      waitFor(120.seconds, "the local authority issues local.example.com") {
        get(k3s, "certificate", "local.example.com", Ready) == "True"
      }
      apply(k3s, listenerSet(Vector("local.example.com")))
      apply(k3s, route(Vector(s"whoami-spike.$Base", "local.example.com")))
      waitFor(120.seconds, "the set's listener is Programmed") {
        get(
          k3s,
          "listenerset",
          "whoami-hostnames",
          listener("local.example.com", "Programmed")
        ) == "True"
      }
      println(s"S1 set status: ${get(k3s, "listenerset", "whoami-hostnames", "{.status}")}")
      println(s"S1 route parents: ${get(k3s, "httproute", "whoami", "{.status.parents}")}")
      println(
        s"S1 gateway attachedListenerSets: ${PkiStack.jsonPath(k3s, "-n", "ankka-gateway", "gateway", "ankka", "{.status.attachedListenerSets}")}"
      )
      waitFor(60.seconds, "the custom hostname answers with its own certificate") {
        val (code, body) = curl(https, "local.example.com", ca)
        code == 200 && body.contains("Host: local.example.com")
      }
      println(s"S1 local.example.com subject: ${subject(https, "local.example.com", ca)}")
      waitFor(60.seconds, "the derived hostname still answers") {
        curl(https, s"whoami-spike.$Base", ca)._1 == 200
      }

      // ── 2. conflicts: the Gateway's own listeners win ────────────────────────────────────────
      apply(
        k3s,
        listenerSet(
          Vector("local.example.com"),
          extra = Vector("wild" -> s"*.$Base", "api" -> s"api.$Base")
        )
      )
      waitFor(60.seconds, "the wildcard listener in the set is not Accepted") {
        get(k3s, "listenerset", "whoami-hostnames", listener("wild", "Accepted")) == "False"
      }
      for name <- Vector("wild", "api") do
        println(
          s"S1 conflict $name: ${get(k3s, "listenerset", "whoami-hostnames", s"""{.status.listeners[?(@.name=="$name")].conditions}""")}"
        )
      assertEquals(curl(https, s"whoami-spike.$Base", ca)._1, 200)
      assert(subject(https, s"whoami-spike.$Base", ca).contains(Base), "the wildcard still serves")
      assertEquals(curl(https, "local.example.com", ca)._1, 200)
      apply(k3s, listenerSet(Vector("local.example.com")))

      // ── 3. Pebble, challtestsrv as every resolver, cert-manager's gateway solver ─────────────
      importImages(k3s, Vector(Pebble, Resolver))
      apply(k3s, acmeStack)
      waitFor(180.seconds, "Pebble and its resolver run") {
        get(k3s, "-n", "acme-test", "deployment", "pebble", "{.status.readyReplicas}") == "1" &&
        get(k3s, "-n", "acme-test", "deployment", "resolver", "{.status.readyReplicas}") == "1"
      }
      val resolverIp = get(k3s, "-n", "acme-test", "service", "resolver", "{.spec.clusterIP}")
      val envoyIp = kubectl(
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
      println(s"S1 resolver $resolverIp, envoy $envoyIp")
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
      )
      kubectl(
        k3s,
        "-n",
        "cert-manager",
        "rollout",
        "status",
        "deployment/cert-manager",
        "--timeout=180s"
      )
      manage(k3s, "/add-a", s"""{"host":"app.example.com","addresses":["$envoyIp"]}""")
      apply(
        k3s,
        s"""apiVersion: cert-manager.io/v1
           |kind: ClusterIssuer
           |metadata: { name: pebble }
           |spec:
           |  acme:
           |    server: https://pebble.acme-test.svc:14000/dir
           |    skipTLSVerify: true
           |    privateKeySecretRef: { name: pebble-account }
           |    solvers:
           |      - http01:
           |          gatewayHTTPRoute:
           |            parentRefs:
           |              - { group: gateway.networking.k8s.io, kind: Gateway, name: ankka, namespace: ankka-gateway, sectionName: http }
           |""".stripMargin
      )
      waitFor(120.seconds, "the pebble issuer registers") {
        get(k3s, "clusterissuer", "pebble", Ready) == "True"
      }
      apply(k3s, certificate("app.example.com", "pebble"))
      var sawSolver = ""
      waitFor(240.seconds, "Pebble issues app.example.com") {
        if sawSolver.isEmpty then
          sawSolver = kubectl(
            k3s,
            "-n",
            Namespace,
            "get",
            "httproute",
            "-o",
            "jsonpath={range .items[*]}{.metadata.name} {.spec.parentRefs} {.spec.hostnames}{\"\\n\"}{end}"
          ).linesIterator
            .find(_.startsWith("cm-acme-http-solver"))
            .getOrElse("")
        get(k3s, "certificate", "app.example.com", Ready) == "True"
      }
      println(s"S1 solver route: $sawSolver")
      apply(k3s, listenerSet(Vector("local.example.com", "app.example.com")))
      apply(k3s, route(Vector(s"whoami-spike.$Base", "local.example.com", "app.example.com")))
      val pebbleRoot = Files.createTempFile("pebble-root", ".pem")
      Files.writeString(
        pebbleRoot,
        kubectl(
          k3s,
          "-n",
          "acme-test",
          "run",
          "fetch-root",
          "--rm",
          "-i",
          "--restart=Never",
          "--image=curlimages/curl:8.10.1",
          "--",
          "-sk",
          "https://pebble.acme-test.svc:15000/roots/0"
        ).linesIterator.takeWhile(!_.startsWith("pod \"")).mkString("\n") + "\n"
      )
      waitFor(90.seconds, "app.example.com answers with Pebble's certificate") {
        val (code, body) = curl(https, "app.example.com", pebbleRoot)
        code == 200 && body.contains("Host: app.example.com")
      }
      println(s"S1 app.example.com subject: ${subject(https, "app.example.com", pebbleRoot)}")

      // A name nothing resolves: the reason the member will read.
      apply(k3s, certificate("other.example.com", "pebble"))
      Thread.sleep(75_000)
      println(
        s"S1 unresolved challenge: ${kubectl(k3s, "-n", Namespace, "get", "challenges", "-o", "jsonpath={range .items[*]}{.spec.dnsName}|{.status.state}|{.status.reason}{\"\\n\"}{end}")}"
      )
      println(
        s"S1 unresolved certificate: ${get(k3s, "certificate", "other.example.com", "{.status.conditions}")}"
      )
      println(
        s"S1 unresolved order: ${kubectl(k3s, "-n", Namespace, "get", "orders", "-o", "jsonpath={range .items[*]}{.spec.dnsNames}|{.status.state}|{.status.reason}{\"\\n\"}{end}")}"
      )

      // A name that resolves to an address nothing answers on 80: an authority's refusal.
      manage(k3s, "/add-a", s"""{"host":"refused.example.com","addresses":["$resolverIp"]}""")
      apply(k3s, certificate("refused.example.com", "pebble"))
      Thread.sleep(75_000)
      println(
        s"S1 refused challenge: ${kubectl(k3s, "-n", Namespace, "get", "challenges", "-o", "jsonpath={range .items[*]}{.spec.dnsName}|{.status.state}|{.status.reason}{\"\\n\"}{end}")}"
      )
    finally k3s.stop()
  }

  // ── what the operator will render, by hand ───────────────────────────────────────────────

  private def listenerSet(
      hosts: Vector[String],
      extra: Vector[(String, String)] = Vector.empty
  ): String =
    val listeners = hosts.map(h => h -> h) ++ extra
    val rendered = listeners.map { (name, host) =>
      val secret = if host.startsWith("*") || host.startsWith("api.") then hosts.head else host
      s"""    - name: $name
         |      hostname: "$host"
         |      port: 443
         |      protocol: HTTPS
         |      tls:
         |        mode: Terminate
         |        certificateRefs: [{ kind: Secret, name: $secret }]
         |      allowedRoutes: { namespaces: { from: Same } }""".stripMargin
    }
    s"""apiVersion: gateway.networking.k8s.io/v1
       |kind: ListenerSet
       |metadata: { name: whoami-hostnames, namespace: $Namespace }
       |spec:
       |  parentRef: { group: gateway.networking.k8s.io, kind: Gateway, name: ankka, namespace: ankka-gateway }
       |  listeners:
       |${rendered.mkString("\n")}
       |""".stripMargin

  private def route(hosts: Vector[String]): String =
    s"""apiVersion: gateway.networking.k8s.io/v1
       |kind: HTTPRoute
       |metadata: { name: whoami, namespace: $Namespace }
       |spec:
       |  parentRefs:
       |    - { group: gateway.networking.k8s.io, kind: Gateway, name: ankka, namespace: ankka-gateway, sectionName: https }
       |    - { group: gateway.networking.k8s.io, kind: ListenerSet, name: whoami-hostnames }
       |  hostnames: [${hosts.map(h => s"\"$h\"").mkString(", ")}]
       |  rules:
       |    - backendRefs: [{ name: whoami, port: 80 }]
       |""".stripMargin

  private def certificate(host: String, issuer: String): String =
    s"""apiVersion: cert-manager.io/v1
       |kind: Certificate
       |metadata: { name: $host, namespace: $Namespace }
       |spec:
       |  secretName: $host
       |  dnsNames: [$host]
       |  issuerRef: { name: $issuer, kind: ClusterIssuer, group: cert-manager.io }
       |""".stripMargin

  private val acmeStack: String =
    s"""apiVersion: v1
       |kind: Namespace
       |metadata: { name: acme-test }
       |---
       |apiVersion: apps/v1
       |kind: Deployment
       |metadata: { name: resolver, namespace: acme-test }
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
       |metadata: { name: resolver, namespace: acme-test }
       |spec:
       |  selector: { app: resolver }
       |  ports:
       |    - { name: dns, port: 8053, protocol: UDP }
       |    - { name: dns-tcp, port: 8053, protocol: TCP }
       |    - { name: management, port: 8055, protocol: TCP }
       |---
       |apiVersion: v1
       |kind: ConfigMap
       |metadata: { name: pebble, namespace: acme-test }
       |data:
       |  pebble-config.json: |
       |    {"pebble": {"listenAddress": "0.0.0.0:14000", "managementListenAddress": "0.0.0.0:15000",
       |      "certificate": "test/certs/localhost/cert.pem", "privateKey": "test/certs/localhost/key.pem",
       |      "httpPort": 80, "tlsPort": 443, "ocspResponderURL": "", "externalAccountBindingRequired": false}}
       |---
       |apiVersion: apps/v1
       |kind: Deployment
       |metadata: { name: pebble, namespace: acme-test }
       |spec:
       |  selector: { matchLabels: { app: pebble } }
       |  template:
       |    metadata: { labels: { app: pebble } }
       |    spec:
       |      initContainers: []
       |      containers:
       |        - name: pebble
       |          image: $Pebble
       |          imagePullPolicy: IfNotPresent
       |          args: ["-config", "/etc/pebble/pebble-config.json", "-dnsserver", "resolver.acme-test.svc.cluster.local:8053"]
       |          env:
       |            - { name: PEBBLE_VA_NOSLEEP, value: "1" }
       |            - { name: PEBBLE_WFE_NONCEREJECT, value: "0" }
       |          volumeMounts: [{ name: config, mountPath: /etc/pebble }]
       |      volumes: [{ name: config, configMap: { name: pebble } }]
       |---
       |apiVersion: v1
       |kind: Service
       |metadata: { name: pebble, namespace: acme-test }
       |spec:
       |  selector: { app: pebble }
       |  ports:
       |    - { name: acme, port: 14000 }
       |    - { name: management, port: 15000 }
       |""".stripMargin

  // ── plumbing ─────────────────────────────────────────────────────────────────────────────

  private val Ready = """{.status.conditions[?(@.type=="Ready")].status}"""

  private def listener(name: String, condition: String): String =
    s"""{.status.listeners[?(@.name=="$name")].conditions[?(@.type=="$condition")].status}"""

  private def apply(k3s: K3sContainer, yaml: String): Unit =
    k3s.copyFileToContainer(
      Transferable.of(yaml.getBytes(StandardCharsets.UTF_8)),
      "/tmp/spike.yaml"
    )
    var last = ""
    val ok = (1 to 10).exists { _ =>
      val r = k3s.execInContainer(
        "kubectl",
        "apply",
        "--server-side",
        "--force-conflicts",
        "-f",
        "/tmp/spike.yaml"
      )
      last = r.getStderr
      if r.getExitCode != 0 then Thread.sleep(3000)
      r.getExitCode == 0
    }
    if !ok then throw new AssertionError(s"apply failed: $last\n$yaml")

  private def kubectl(k3s: K3sContainer, args: String*): String = PkiStack.kubectl(k3s, args*)

  private def get(k3s: K3sContainer, args: String*): String =
    val scoped = if args.head == "-n" then args.toVector else Vector("-n", Namespace) ++ args
    PkiStack.jsonPath(k3s, scoped*)

  private def manage(k3s: K3sContainer, path: String, body: String): Unit =
    val out = kubectl(
      k3s,
      "-n",
      "acme-test",
      "run",
      s"manage-${System.nanoTime() % 100000}",
      "--rm",
      "-i",
      "--restart=Never",
      "--image=curlimages/curl:8.10.1",
      "--",
      "-sS",
      "-X",
      "POST",
      "-d",
      body,
      s"http://resolver.acme-test.svc:8055$path"
    )
    println(s"challtestsrv $path: ${out.trim}")

  private def importImages(k3s: K3sContainer, images: Vector[String]): Unit =
    for image <- images do
      val tar = Files.createTempFile("image", ".tar")
      val saved = new ProcessBuilder("docker", "save", "-o", tar.toString, image)
        .inheritIO()
        .start()
        .waitFor()
      if saved != 0 then throw new AssertionError(s"docker save $image failed")
      k3s.copyFileToContainer(
        org.testcontainers.utility.MountableFile.forHostPath(tar),
        "/tmp/image.tar"
      )
      val r = k3s.execInContainer(
        "ctr",
        "-a",
        "/run/k3s/containerd/containerd.sock",
        "-n",
        "k8s.io",
        "images",
        "import",
        "/tmp/image.tar"
      )
      if r.getExitCode != 0 then throw new AssertionError(s"import $image: ${r.getStderr}")
      Files.delete(tar)

  private def curl(port: Int, host: String, ca: Path): (Int, String) =
    val args = Vector(
      "curl",
      "-sS",
      "--cacert",
      ca.toString,
      "--resolve",
      s"$host:$port:127.0.0.1",
      "-m",
      "10",
      "-o",
      "-",
      "-w",
      "\n%{http_code}",
      s"https://$host:$port/"
    )
    val p      = new ProcessBuilder(args*).redirectErrorStream(true).start()
    val output = new String(p.getInputStream.readAllBytes(), StandardCharsets.UTF_8)
    p.waitFor()
    val lines = output.linesIterator.toVector
    (lines.lastOption.flatMap(_.trim.toIntOption).getOrElse(0), lines.dropRight(1).mkString("\n"))

  private def subject(port: Int, host: String, ca: Path): String =
    val args = Vector(
      "curl",
      "-sS",
      "-v",
      "--cacert",
      ca.toString,
      "--resolve",
      s"$host:$port:127.0.0.1",
      "-m",
      "10",
      "-o",
      "/dev/null",
      s"https://$host:$port/"
    )
    val p      = new ProcessBuilder(args*).redirectErrorStream(true).start()
    val output = new String(p.getInputStream.readAllBytes(), StandardCharsets.UTF_8)
    p.waitFor()
    output.linesIterator
      .filter(l => l.contains("subject:") || l.contains("subjectAltName") || l.contains("issuer:"))
      .mkString(" | ")

  private def waitFor(timeout: FiniteDuration, what: String)(check: => Boolean): Unit =
    val deadline = System.nanoTime() + timeout.toNanos
    var passed   = false
    while !passed && System.nanoTime() < deadline do
      passed =
        try check
        catch case _: Exception => false
      if !passed then Thread.sleep(2000)
    if !passed then throw new AssertionError(s"$what did not happen within $timeout")
