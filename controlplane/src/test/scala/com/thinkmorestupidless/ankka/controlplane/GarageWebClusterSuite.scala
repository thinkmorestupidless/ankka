package com.thinkmorestupidless.ankka.controlplane

import com.thinkmorestupidless.ankka.crd.AnkkaSerialization
import com.thinkmorestupidless.ankka.operator.{GatewayStack, ObjectStoreStack, PkiStack}
import com.thinkmorestupidless.ankka.runtime.storage.SigV4
import io.fabric8.kubernetes.client.{
  Config,
  KubernetesClient,
  KubernetesClientBuilder,
  LocalPortForward
}
import org.testcontainers.images.builder.Transferable
import org.testcontainers.k3s.K3sContainer
import org.testcontainers.utility.DockerImageName

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import java.time.Instant
import scala.concurrent.duration.*

/**
 * Research's spike S2, kept (feature 055): a public bucket is read through the installation's
 * gateway by a route that rewrites the `Host` to the bucket's name and sends the request to the
 * store's web listener, with no credential anywhere on the path.
 *
 * The store, the gateway and their network policies are the shipped components, applied as
 * `ObjectStoreStack` and `GatewayStack` apply them; the route and its grant are written by hand in
 * the shape contracts/operator-and-cloud.md gives the operator. Nothing of the operator runs.
 */
class GarageWebClusterSuite extends munit.FunSuite:

  override val munitTimeout: FiniteDuration = 10.minutes

  override def munitIgnore: Boolean = sys.props.get("ankka.cluster.tests").contains("off")

  private val K3sImage    = "rancher/k3s:v1.35.1-k3s1"
  private val BaseDomain  = "web.example.test"
  private val Project     = "ankka-t"
  private val Photos      = "t.svc.photos"
  private val Closed      = "t.svc.closed"
  private val ObjectName  = "listings/42/front.png"
  private val Body        = "not really a png"
  private val StorageHost = s"storage.$BaseDomain"

  private var k3s: K3sContainer                 = null
  private var k8s: KubernetesClient             = null
  private var store: ObjectStoreStack.Installed = null
  private var s3: LocalPortForward              = null
  private var web: LocalPortForward             = null
  private var ca: Path                          = null
  private var httpsPort: Int                    = 0
  private var photosId: String                  = ""
  private val http                              = HttpClient.newHttpClient()

  private def repoRoot: Path =
    var dir = Paths.get("").toAbsolutePath
    while !Files.exists(dir.resolve("build.sbt")) do dir = dir.getParent
    dir

  override def beforeAll(): Unit =
    if !munitIgnore then
      k3s = new K3sContainer(DockerImageName.parse(K3sImage))
      k3s.withExposedPorts(6443, GatewayStack.HttpsNodePort, GatewayStack.HttpNodePort)
      k3s.start()
      httpsPort = k3s.getMappedPort(GatewayStack.HttpsNodePort)
      k8s = new KubernetesClientBuilder()
        .withConfig(Config.fromKubeconfig(k3s.getKubeConfigYaml))
        .withKubernetesSerialization(AnkkaSerialization())
        .build()
      GatewayStack.install(k3s, k8s, repoRoot, BaseDomain)
      ca = GatewayStack.exportCa(k8s)
      store = ObjectStoreStack.install(k3s, k8s, repoRoot)
      s3 = k8s
        .services()
        .inNamespace(ObjectStoreStack.Namespace)
        .withName(ObjectStoreStack.Service)
        .portForward(3900)
      web = k8s
        .services()
        .inNamespace(ObjectStoreStack.Namespace)
        .withName(ObjectStoreStack.Service)
        .portForward(3902)

      // Two buckets with one object each, kept through the S3 port by a key on both: one bucket's
      // website access is turned on, as the operator turns it on for a public bucket, the other's not.
      photosId = admin("/v2/CreateBucket", s"""{"globalAlias":"$Photos"}""", "id")
      val closedId = admin("/v2/CreateBucket", s"""{"globalAlias":"$Closed"}""", "id")
      val created  = adminBody("/v2/CreateKey", """{"name":"t.svc"}""")
      val key = SigV4.Credentials(field(created, "accessKeyId"), field(created, "secretAccessKey"))
      for id <- Vector(photosId, closedId) do
        adminBody(
          "/v2/AllowBucketKey",
          s"""{"bucketId":"$id","accessKeyId":"${key.accessKey}","permissions":{"read":true,"write":true,"owner":false}}"""
        ): Unit
      for bucket <- Vector(Photos, Closed) do put(key, bucket)
      adminBody(
        s"/v2/UpdateBucket?id=$photosId",
        """{"websiteAccess":{"enabled":true,"indexDocument":"index.html"}}"""
      ): Unit

      // A project's namespace as the control plane makes one: labelled, so the gateway admits its
      // routes. The grant and the routes are what the operator will render for a public bucket.
      applyOnNode(
        s"""apiVersion: v1
           |kind: Namespace
           |metadata:
           |  name: $Project
           |  labels: { app.kubernetes.io/managed-by: ankka }
           |---
           |apiVersion: gateway.networking.k8s.io/v1beta1
           |kind: ReferenceGrant
           |metadata:
           |  name: $Project
           |  namespace: ${ObjectStoreStack.Namespace}
           |spec:
           |  from: [{ group: gateway.networking.k8s.io, kind: HTTPRoute, namespace: $Project }]
           |  to: [{ group: "", kind: Service, name: ${ObjectStoreStack.Service} }]
           |---
           |${route("photos", Photos)}
           |---
           |${route("closed", Closed)}
           |""".stripMargin
      )
      for name <- Vector("photos", "closed") do
        waitFor(120.seconds, s"the $name route is accepted with its backend resolved") {
          val conditions = PkiStack.jsonPath(
            k3s,
            "-n",
            Project,
            "httproute",
            s"storage-$name",
            "{.status.parents[0].conditions[*].type}={.status.parents[0].conditions[*].status}"
          )
          conditions.contains("Accepted") && conditions.contains("ResolvedRefs") && !conditions
            .contains("False")
        }

  override def afterAll(): Unit =
    Option(web).foreach(_.close())
    Option(s3).foreach(_.close())
    Option(store).foreach(_.close())
    Option(k8s).foreach(_.close())
    Option(k3s).foreach(_.stop())

  /** The route contracts/operator-and-cloud.md gives a public bucket. */
  private def route(name: String, bucket: String): String =
    s"""apiVersion: gateway.networking.k8s.io/v1
       |kind: HTTPRoute
       |metadata:
       |  name: storage-$name
       |  namespace: $Project
       |spec:
       |  parentRefs:
       |    - { group: gateway.networking.k8s.io, kind: Gateway, name: ankka, namespace: ankka-gateway, sectionName: https }
       |  hostnames: [$StorageHost]
       |  rules:
       |    - matches: [{ path: { type: PathPrefix, value: /$bucket/ } }]
       |      filters:
       |        - type: URLRewrite
       |          urlRewrite:
       |            hostname: $bucket
       |            path: { type: ReplacePrefixMatch, replacePrefixMatch: / }
       |      backendRefs: [{ name: ${ObjectStoreStack.Service}, namespace: ${ObjectStoreStack.Namespace}, port: 3902 }]
       |      timeouts: { request: 0s }""".stripMargin

  test(
    "the store's web listener serves a bucket with website access by its whole dotted name as the Host"
  ) {
    val open = rawGet(web.getLocalPort, Photos, s"/$ObjectName")
    assert(open.startsWith("HTTP/1.1 200"), open)
    assert(open.toLowerCase.contains("content-type: image/png"), open)
    assert(open.endsWith(Body), open)
    val closed = rawGet(web.getLocalPort, Closed, s"/$ObjectName")
    assert(!closed.startsWith("HTTP/1.1 200") && !closed.contains(Body), closed)
  }

  test(
    "through the gateway, a route that rewrites the Host reads a public object with no credential"
  ) {
    var reply = (0, Map.empty[String, String], "")
    waitFor(120.seconds, "the gateway serves the object") {
      reply = browse(s"https://$StorageHost:$httpsPort/$Photos/$ObjectName")
      reply._1 == 200
    }
    assertEquals(reply._2.get("content-type"), Some("image/png"), reply)
    assertEquals(reply._3, Body)
  }

  test("through the gateway, a bucket whose website access is off serves nothing") {
    // The same route's shape and grant: what serves is the bucket's own switch, not the route.
    waitFor(120.seconds, "the gateway answers for the closed bucket") {
      browse(s"https://$StorageHost:$httpsPort/$Closed/$ObjectName")._1 != 0
    }
    val reply = browse(s"https://$StorageHost:$httpsPort/$Closed/$ObjectName")
    assertNotEquals(reply._1, 200, reply)
    assert(!reply._3.contains(Body), reply)
  }

  test("a workload reaches the store's S3 port and not its web listener") {
    // The prober is labelled a platform workload, which the S3 rule admits: an answer there is what
    // shows the refusal on the web port is the policy's and not a pod that reaches nothing.
    val pod = "prober"
    applyOnNode(
      s"""apiVersion: v1
         |kind: Pod
         |metadata:
         |  name: $pod
         |  namespace: $Project
         |  labels: { app.kubernetes.io/managed-by: ankka }
         |spec:
         |  containers:
         |    - { name: curl, image: "curlimages/curl:8.11.1", command: ["sleep", "infinity"] }
         |""".stripMargin
    )
    PkiStack.kubectl(
      k3s,
      "wait",
      "-n",
      Project,
      "--for=condition=Ready",
      s"pod/$pod",
      "--timeout=180s"
    ): Unit
    def status(port: Int) =
      k3s
        .execInContainer(
          "kubectl",
          "exec",
          "-n",
          Project,
          pod,
          "--",
          "curl",
          "-s",
          "-o",
          "/dev/null",
          "-w",
          "%{http_code}",
          "-m",
          "5",
          "-H",
          s"Host: $Photos",
          s"http://${ObjectStoreStack.Service}.${ObjectStoreStack.Namespace}.svc.cluster.local:$port/$ObjectName"
        )
        .getStdout
        .trim
    assertNotEquals(status(3900), "000", "the S3 port did not answer a workload")
    assertEquals(status(3902), "000", "the web listener answered a workload")
  }

  /** `curl` on the host, through the gateway's mapped port: status, headers, body. */
  private def browse(address: String): (Int, Map[String, String], String) =
    val command = Vector(
      "curl",
      "-sS",
      "-m",
      "20",
      "-D",
      "-",
      "--cacert",
      ca.toString,
      "--resolve",
      s"$StorageHost:$httpsPort:127.0.0.1",
      address
    )
    val process = new ProcessBuilder(command*).start()
    val output  = new String(process.getInputStream.readAllBytes(), StandardCharsets.UTF_8)
    process.waitFor()
    val split = output.indexOf("\r\n\r\n")
    if split < 0 then (0, Map.empty, output)
    else
      val head   = output.substring(0, split).split("\r\n").toVector
      val status = head.headOption.flatMap(_.split(" ").lift(1)).flatMap(_.toIntOption).getOrElse(0)
      val headers = head
        .drop(1)
        .flatMap(line =>
          line.split(":", 2) match
            case Array(k, v) => Some(k.trim.toLowerCase -> v.trim)
            case _           => None
        )
        .toMap
      (status, headers, output.substring(split + 4))

  /** A GET with a `Host` of our choosing, which the JDK's client will not send. */
  private def rawGet(port: Int, host: String, path: String): String =
    val socket = new java.net.Socket("127.0.0.1", port)
    try
      socket.getOutputStream.write(
        s"GET $path HTTP/1.1\r\nHost: $host\r\nConnection: close\r\n\r\n".getBytes(
          StandardCharsets.UTF_8
        )
      )
      new String(socket.getInputStream.readAllBytes(), StandardCharsets.UTF_8)
    finally socket.close()

  private def put(key: SigV4.Credentials, bucket: String): Unit =
    val endpoint = URI.create(s"http://127.0.0.1:${s3.getLocalPort}")
    val request  = SigV4.Request("PUT", endpoint, s"/$bucket/$ObjectName", Vector.empty)
    val extra = Map("Content-Type" -> "image/png", "x-amz-content-sha256" -> SigV4.UnsignedPayload)
    val headers =
      SigV4.sign(request, extra, SigV4.UnsignedPayload, key, "garage", "s3", Instant.now()) ++ extra
    val builder = HttpRequest.newBuilder(request.uri)
    headers.foreach((k, v) => builder.header(k, v))
    val response = http.send(
      builder.PUT(HttpRequest.BodyPublishers.ofString(Body)).build(),
      HttpResponse.BodyHandlers.ofString()
    )
    assertEquals(response.statusCode, 200, response.body)

  private def admin(path: String, json: String, wanted: String): String =
    field(adminBody(path, json), wanted)

  private def adminBody(path: String, json: String): String =
    val response = http.send(
      HttpRequest
        .newBuilder(URI.create(store.settings.adminUrl + path))
        .header("Authorization", s"Bearer ${store.settings.adminToken}")
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(json))
        .build(),
      HttpResponse.BodyHandlers.ofString()
    )
    if response.statusCode / 100 != 2 then
      fail(s"garage admin $path: ${response.statusCode} ${response.body}")
    response.body

  private def field(json: String, name: String): String =
    s""""$name"\\s*:\\s*"([^"]+)"""".r
      .findFirstMatchIn(json)
      .map(_.group(1))
      .getOrElse(fail(s"no $name in $json"))

  private def applyOnNode(manifest: String): Unit =
    k3s.copyFileToContainer(
      Transferable.of(manifest.getBytes(StandardCharsets.UTF_8)),
      "/tmp/garage-web.yaml"
    )
    val applied =
      k3s.execInContainer("kubectl", "apply", "--server-side", "-f", "/tmp/garage-web.yaml")
    if applied.getExitCode != 0 then fail(s"applying the routes failed: ${applied.getStderr}")

  private def waitFor(timeout: FiniteDuration, what: String)(check: => Boolean): Unit =
    val deadline = System.nanoTime() + timeout.toNanos
    var passed   = false
    while !passed && System.nanoTime() < deadline do
      passed =
        try check
        catch case _: Exception => false
      if !passed then Thread.sleep(1000)
    if !passed then fail(s"$what did not happen within $timeout")
