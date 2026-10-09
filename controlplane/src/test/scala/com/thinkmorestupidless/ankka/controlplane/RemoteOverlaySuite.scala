package com.thinkmorestupidless.ankka.controlplane

import com.thinkmorestupidless.ankka.testkit.LogCapturing
import munit.FunSuite

import scala.jdk.CollectionConverters.*

import java.nio.file.{Files, Path, Paths}
import scala.sys.process.*
import scala.util.Try

/**
 * That `overlays/cloud` — the example production overlay an installation copies — still renders,
 * and still differs from `overlays/local` in exactly the ways it is supposed to.
 *
 * The two overlays share all eight components, which is the point of the split — the CRD, the
 * operator, the control plane, its RBAC, CNPG, cert-manager, Envoy Gateway and the Gateway are not
 * local-specific, and a remote installation is a different overlay rather than a fork. The risk
 * that creates is drift: a change made to `overlays/local` that the remote one needed too (a new
 * replacement target, say) leaves the remote overlay rendering a placeholder into a real cluster,
 * and nothing notices until something is deployed on the internet.
 *
 * So these assertions are mostly *negative* — what must not appear in the remote render. The
 * positive half, that it deploys and serves, cannot be had without a cluster and a domain; this is
 * the half that can run in seconds, every time.
 *
 * **Skips when `kubectl` is absent**, like `AnthropicProviderSuite` skips without an API key.
 * Rendering needs kustomize, which ships inside kubectl, and the repository does not otherwise
 * require it on the host — the k3s suites use the *node's* kubectl, not this machine's.
 */
final class RemoteOverlaySuite extends FunSuite with LogCapturing:

  private val kubectl = Try("kubectl version --client".!(ProcessLogger(_ => ()))).getOrElse(1) == 0

  override def munitIgnore: Boolean = !kubectl

  private def repoRoot: Path =
    Iterator
      .iterate(Paths.get("").toAbsolutePath)(_.getParent)
      .takeWhile(_ != null)
      .find(p => Files.isDirectory(p.resolve("kustomization")))
      .getOrElse(fail("could not find the repository root"))

  private def render(overlay: String): String =
    val dir    = repoRoot.resolve(s"kustomization/overlays/$overlay")
    val errors = StringBuilder()
    val out =
      Process(Seq("kubectl", "kustomize", dir.toString))
        .!!(ProcessLogger(line => errors.append(line): Unit))
    assert(out.nonEmpty, s"$overlay rendered nothing: $errors")
    out

  private lazy val remote = render("cloud")
  private lazy val local  = render("local")

  /**
   * The rendered documents of one kind.
   *
   * Searching the whole render for a word is not good enough, and this suite found that out: an
   * early assertion that "selfSigned" must not appear in the remote render failed, because
   * cert-manager's *own CRD schemas* define a `selfSigned` issuer field — legitimately installed,
   * and nothing to do with which issuer ankka asks for. Anything naming a Kubernetes concept has to
   * be asked of the resources, not of the text.
   */
  private def documentsOfKind(render: String, kind: String): Vector[String] =
    render
      .split("(?m)^---$")
      .toVector
      // Unindented, so this is the document's own `kind` and not a nested one. Trimming instead
      // matched `kind: Issuer` sitting inside a CustomResourceDefinition's `spec.names` — the
      // second time in this suite that a substring found a Kubernetes word in a schema rather
      // than in a resource.
      .filter(_.linesIterator.exists(_ == s"kind: $kind"))

  test("the remote overlay renders at all") {
    // kustomize is strict about patch targets: a component that renames or moves the object a
    // patch names fails here rather than at apply time against a real cluster.
    assert(remote.contains("kind: Deployment"), "no workloads rendered")
    assert(remote.contains("ankka-controlplane"), "the control plane is missing")
    assert(remote.contains("ankka-operator"), "the operator is missing")
  }

  test("the operator is told which sidecar image to inject, from the registry (feature 009)") {
    // The sidecar is injected by the operator, so no manifest names it and the `images:`
    // transformer cannot reach it: the remote overlay sets ANKKA_SIDECAR_IMAGE by patch, and the
    // local one keeps the locally built tag that deploy-local.sh loads.
    val remoteOperator =
      documentsOfKind(remote, "Deployment").find(_.contains("name: ankka-operator")).get
    assert(
      remoteOperator.contains(
        "ghcr.io/thinkmorestupidless/ankka-sidecar:"
      ),
      "the remote operator does not name the registry's sidecar image"
    )
    // Naming the image somewhere in the document is not enough. A patch that names a container the
    // operator does not have adds a second container, with this variable and no image, and leaves
    // the real one on the local default. That rendered cleanly and passed the check above; the API
    // server refused the Deployment. So: the variable is set once, and the local default is gone.
    assertEquals(
      "ANKKA_SIDECAR_IMAGE".r.findAllIn(remoteOperator).size,
      1,
      "ANKKA_SIDECAR_IMAGE is set more than once: the patch names a container the operator does not have"
    )
    assert(
      !remoteOperator.contains("ankka-sidecar:latest"),
      "the remote operator's container still carries the local sidecar image"
    )
    val localOperator =
      documentsOfKind(local, "Deployment").find(_.contains("name: ankka-operator")).get
    assert(
      localOperator.contains("ankka-sidecar:latest"),
      "the local operator does not name the local sidecar image"
    )
  }

  test("the shared bearer token is gone from both overlays (feature 008)") {
    // Not deleted by the remote overlay any more: removed. Every caller is a Keycloak user.
    for render <- Vector(local, remote) do
      assert(!render.contains("ANKKA_CONTROLPLANE_TOKEN"), "the token variable is still rendered")
      assert(!render.contains("dev-local-token"), "the development token is still rendered")
      assert(documentsOfKind(render, "Secret").forall(!_.contains("ankka-controlplane-token")))
  }

  test("Keycloak's development administrator cannot reach a remote cluster") {
    // The single most damaging thing that could leak out of this overlay now. `components/keycloak`
    // ships `ankka-keycloak-admin` as admin/admin so a fresh kind cluster has a console to add users
    // in; the remote overlay deletes the Secret outright, and the Keycloak resource names it, so the
    // operator cannot create the instance until a real one exists out of band.
    val remoteSecrets = documentsOfKind(remote, "Secret")
    assert(
      !remoteSecrets.exists(_.contains("name: ankka-keycloak-admin")),
      "the admin secret is in the remote render"
    )
    val localSecrets = documentsOfKind(local, "Secret")
    assert(
      localSecrets.exists(_.contains("name: ankka-keycloak-admin")),
      "...but it should still be in the local one"
    )
    // Both still *reference* it — that reference is what makes the deletion bite.
    for render <- Vector(local, remote) do
      assert(documentsOfKind(render, "Keycloak").exists(_.contains("secret: ankka-keycloak-admin")))
  }

  test("the identity provider is rendered whole in both overlays, in its own namespace") {
    for render <- Vector(local, remote) do
      val deployments = documentsOfKind(render, "Deployment")
      assert(
        deployments.exists(d =>
          d.contains("name: keycloak-operator") && d.contains("namespace: ankka-auth")
        ),
        "the operator"
      )
      assert(
        documentsOfKind(render, "Keycloak").exists(_.contains("namespace: ankka-auth")),
        "the instance"
      )
      assert(
        documentsOfKind(render, "Cluster").exists(_.contains("name: ankka-keycloak-db")),
        "its database"
      )
      assert(
        documentsOfKind(render, "Namespace").exists(_.contains("name: ankka-auth")),
        "the namespace"
      )
      // The one thing the namespace transformer does not reach, patched by hand upstream of here.
      val binding = documentsOfKind(render, "ClusterRoleBinding")
        .find(_.contains("name: keycloak-operator-clusterrole-binding"))
        .getOrElse(fail("the operator's ClusterRoleBinding is missing"))
      assert(
        binding.contains("namespace: ankka-auth"),
        "the binding's subject still names the upstream namespace"
      )
      assert(!binding.contains("namespace: keycloak\n"), binding)
      // The realm import is a resource of the component — the one copy of the realm — so an
      // overlay applied by anything other than deploy-local.sh brings the realm with it. The
      // first Flux reconcile of a real cluster found a Keycloak with no realm when it was not.
      val realmImports = documentsOfKind(render, "KeycloakRealmImport")
      assertEquals(realmImports.size, 1, "exactly one realm import, checked in")
      assert(realmImports.head.contains("namespace: ankka-auth"), realmImports.head.take(300))
      assert(
        realmImports.head.contains("keycloakCRName: ankka-keycloak"),
        realmImports.head.take(300)
      )
      assert(realmImports.head.contains("realm: ankka"), "the realm named in the import")
  }

  test(
    "auth.<base domain> reaches the route, the instance's hostname and the control plane's derivation"
  ) {
    val routes = documentsOfKind(remote, "HTTPRoute")
    assert(
      routes.exists(r => r.contains("name: ankka-keycloak") && r.contains("- auth.example.com")),
      "the identity provider's route"
    )
    assert(
      documentsOfKind(remote, "Keycloak").exists(_.contains("hostname: auth.example.com")),
      "the instance's own hostname"
    )
    assert(
      documentsOfKind(local, "Keycloak").exists(_.contains("hostname: auth.127.0.0.1.sslip.io"))
    )
    // The control plane derives its issuer from ANKKA_BASE_DOMAIN and reads keys in-cluster: no
    // issuer variable to replace, one key URL that names the service, never the gateway.
    val controlPlane =
      documentsOfKind(remote, "Deployment").find(_.contains("name: ankka-controlplane")).get
    assert(!controlPlane.contains("ANKKA_AUTH_ISSUER"), "the issuer is derived, not rendered")
    assert(
      controlPlane.contains(
        "https://ankka-keycloak-service.ankka-auth.svc:8443/realms/ankka/protocol/openid-connect/certs"
      )
    )
  }

  test("the Keycloak version is written once for code and agrees with the manifests and compose") {
    val version = sys.props.getOrElse(
      "ankka.keycloak.version",
      fail("build.sbt did not forward keycloakVersion")
    )
    val manifests = Files.readString(
      repoRoot.resolve("kustomization/components/keycloak-operator/manifests/kustomization.yaml")
    )
    assert(
      manifests.contains(s"keycloak-k8s-resources/$version/"),
      s"the operator reference does not pin $version"
    )
    val compose = Files.readString(repoRoot.resolve("docker-compose.yml"))
    assert(
      compose.contains(s"quay.io/keycloak/keycloak:$version"),
      s"docker-compose does not run $version"
    )
    assert(!compose.contains("keycloak:latest"))
  }

  test("no local-only address survives into the remote render") {
    // 8443 is also the identity provider's own TLS port inside the cluster, which every overlay
    // shares; what must not survive is kind's host port anywhere else.
    val rendered = remote.replace("ankka-keycloak-service.ankka-auth.svc:8443", "")
    for leaked <- Vector("sslip.io", "127.0.0.1", ":8443", "\"8443\"") do
      assert(!rendered.contains(leaked), s"'$leaked' leaked into the remote overlay")
  }

  test("the real domain reaches every place that must agree about it") {
    // Five places, one source (platform-configmap.yaml). They cannot be allowed to drift: the
    // certificate covers one of them, the listener matches on another, and a mismatch is a
    // gateway that serves a certificate for a name nobody asked for.
    val domain = "example.com"
    assert(remote.contains(s"'*.$domain'"), "the wildcard certificate and listener")
    assert(remote.contains(s"api.$domain"), "the control plane's own route")
    assert(
      remote.linesIterator.count(_.contains(domain)) >= 5,
      "the domain should reach the certificate, the listener, the route and both Deployments"
    )
  }

  test("the gateway asks for a load balancer, and pins no node ports") {
    assert(remote.contains("type: LoadBalancer"), "a cloud cluster should get a LoadBalancer")
    for pinned <- Vector("30080", "30443") do
      assert(!remote.contains(pinned), s"node port $pinned is still pinned in the remote overlay")
    // And the local overlay keeps them: kind has no load balancer to give, and those two ports are
    // what it maps to the host.
    assert(local.contains("30443"), "the local overlay must keep its node ports")
  }

  test("every base-domain placeholder is substituted") {
    // `BASE_DOMAIN` is the literal the components carry; `ANKKA_BASE_DOMAIN` is an environment
    // variable's *name* and legitimately contains it, so match the placeholder on its own.
    val unreplaced = remote.linesIterator
      .filter(_.contains("BASE_DOMAIN"))
      .filterNot(_.contains("name: ANKKA_BASE_DOMAIN"))
      .toVector
    assertEquals(unreplaced, Vector.empty, "a placeholder reached the remote render")
  }

  test("the certificate the gateway serves is issued by a real authority") {
    // The contract between the issuer and the gateway is one secret name; anything that produces
    // it will do, which is why the local overlay can use a self-signed root and this one cannot.
    assert(remote.contains("ankka-wildcard-tls"), "the gateway's certificate secret must not move")

    // The installation's own authorities (feature 014) are private roots on purpose: no public
    // authority issues an `ankka://` identity or a cluster-internal name, and nothing outside the
    // cluster is asked to trust them. What must be public is the issuer of the certificate the
    // world sees.
    // trust-manager's chart brings its own self-signed Issuer for its webhook's serving
    // certificate, which is a dependency's business and serves nothing on the domain.
    val internal = Set("ankka-selfsigned", "ankka-cluster", "ankka-service")
    val issuers = (documentsOfKind(remote, "Issuer") ++ documentsOfKind(remote, "ClusterIssuer"))
      .filterNot(d => internal.exists(n => d.linesIterator.contains(s"  name: $n")))
      .filterNot(_.contains("app.kubernetes.io/name: trust-manager"))
    assert(issuers.nonEmpty, "the remote overlay issues no public certificates at all")
    assert(
      issuers.forall(_.contains("acme:")),
      s"every public-facing remote issuer must come from a real authority, got: $issuers"
    )
    assert(
      !issuers.exists(_.contains("selfSigned")),
      "a self-signed root has no business on a real domain"
    )

    // And the local overlay is the opposite, which is what makes the split worth having.
    val localIssuers = documentsOfKind(local, "Issuer")
    assert(localIssuers.exists(_.contains("selfSigned")), "the local overlay signs its own")
  }

  test("the wildcard is issued by a ClusterIssuer, which the webhook's RBAC requires") {
    // Not interchangeable with a namespaced Issuer, and the failure if it were would be obscure.
    // The DNSimple solver reads its API token with the *webhook's* ServiceAccount, in the
    // namespace of the challenge; the webhook chart grants that only in cert-manager's namespace,
    // pinned by resourceNames to its own secret. A namespaced Issuer in ankka-gateway therefore
    // sends it looking for a secret it cannot read, and issuance fails with `forbidden` on the
    // token — which reads nothing like "the wildcard certificate is the problem".
    val cert = documentsOfKind(remote, "Certificate")
      .find(_.contains("name: ankka-wildcard"))
      .getOrElse(fail("the wildcard certificate is missing from the remote overlay"))
    assert(cert.contains("kind: ClusterIssuer"), s"must reference a ClusterIssuer: $cert")
    // And one that exists. The issuer is renamed whenever the CA changes (that is what makes
    // cert-manager reissue), so a rename made in one place leaves a certificate naming nothing,
    // which cert-manager reports only as a certificate that never becomes Ready.
    val issuerRef = "(?s)issuerRef:.*?name: (\\S+)".r
      .findFirstMatchIn(cert)
      .map(_.group(1))
      .getOrElse(fail(s"no issuerRef name: $cert"))
    assert(
      documentsOfKind(remote, "ClusterIssuer").exists(_.contains(s"\n  name: $issuerRef\n")),
      s"the wildcard names ClusterIssuer $issuerRef, which the remote overlay does not define"
    )

    // The local overlay is the mirror image, and for the mirror-image reason: its CA secret sits
    // beside the Certificate, so a ClusterIssuer would look in the wrong namespace
    // (.claude/rules/kubernetes.md).
    val localCert = documentsOfKind(local, "Certificate")
      .find(_.contains("name: ankka-wildcard"))
      .getOrElse(fail("the local wildcard certificate is missing"))
    assert(localCert.contains("kind: Issuer"), "the local one must stay namespaced")
    assert(!localCert.contains("kind: ClusterIssuer"), "...and must not become a ClusterIssuer")
  }

  test("the DNSimple solver is named exactly as the webhook registers it") {
    // cert-manager matches a webhook solver by (groupName, solverName) and silently finds nothing
    // if either is wrong — the Certificate simply never progresses. Both come from the chart:
    // groupName is its `groupName` value, solverName is the solver's own Name() in its source.
    val issuers = documentsOfKind(remote, "ClusterIssuer")
    assert(
      issuers.exists(i =>
        i.contains("groupName: acme.dnsimple.com") && i.contains("solverName: dnsimple")
      ),
      "the webhook solver must match what cert-manager-webhook-dnsimple registers"
    )
  }

  // ── Zero trust (feature 014) ─────────────────────────────────────────────────────────────

  test("both overlays install the installation's two authorities and the bundle that shares one") {
    for (name, render) <- Vector("local" -> local, "cloud" -> remote) do
      val issuers = documentsOfKind(render, "ClusterIssuer")
      for issuer <- Vector("ankka-selfsigned", "ankka-cluster", "ankka-service") do
        assert(issuers.exists(_.contains(s"name: $issuer")), s"$name: no ClusterIssuer $issuer")
      val roots = documentsOfKind(render, "Certificate").filter(_.contains("isCA: true"))
      for root <- Vector("ankka-cluster-ca", "ankka-service-ca") do
        assert(
          roots.exists(d => d.contains(s"name: $root") && d.contains("namespace: cert-manager")),
          s"$name: no root $root in cert-manager's namespace, where a ClusterIssuer looks"
        )
      val bundle = documentsOfKind(render, "Bundle").find(_.contains("name: ankka-service-ca"))
      assert(bundle.exists(_.contains("app.kubernetes.io/managed-by: ankka")), s"$name: $bundle")
      assert(
        documentsOfKind(render, "Deployment").exists(_.contains("quay.io/jetstack/trust-manager:")),
        s"$name: trust-manager is not installed"
      )
  }

  test("the gateway presents its own client certificate to services, set exactly once") {
    for (name, render) <- Vector("local" -> local, "cloud" -> remote) do
      val proxy = documentsOfKind(render, "EnvoyProxy").find(_.contains("name: ankka")).get
      assertEquals(
        "clientCertificateRef".r.findAllIn(proxy).size,
        1,
        s"$name: the EnvoyProxy's backendTLS is missing or set twice"
      )
      assert(proxy.contains("name: ankka-gateway-client-tls"), s"$name: $proxy")
      val certificate = documentsOfKind(render, "Certificate")
        .find(_.contains("name: ankka-gateway-client"))
        .getOrElse(fail(s"$name: no gateway client certificate"))
      assert(certificate.contains("namespace: ankka-gateway"), certificate)
      assert(certificate.contains("ankka://gateway"), certificate)
      assert(certificate.contains("name: ankka-service"), certificate)
  }

  // ── Web hosting (feature 021) ─────────────────────────────────────────────

  // ── the installation's broker (features/broker/installation.feature) ────────

  private def yamlOf(document: String): java.util.Map[String, Any] =
    org.yaml.snakeyaml.Yaml().load[java.util.Map[String, Any]](document)

  private def at(node: Any, path: String*): Any =
    path.foldLeft(node) {
      case (m: java.util.Map[?, ?], key) => m.asInstanceOf[java.util.Map[String, Any]].get(key)
      case (other, key)                  => fail(s"no '$key' in $other")
    }

  private def kafka(render: String): java.util.Map[String, Any] =
    yamlOf(documentsOfKind(render, "Kafka").headOption.getOrElse(fail("no Kafka rendered")))

  test("an installation in a cluster has the broker a local platform has") {
    // The same listener, the same authorization and the same configuration: only its size differs.
    for path <- Seq(
        Seq("spec", "kafka", "listeners"),
        Seq("spec", "kafka", "authorization"),
        Seq("spec", "kafka", "config")
      )
    do assertEquals(at(kafka(remote), path*), at(kafka(local), path*), path.mkString("."))
    // Told of it the same way: each of the operator's three settings once, in its one container.
    for overlay <- Seq(remote -> "cloud", local -> "local") do
      val operator = operatorDeployment(overlay._1, overlay._2)
      for variable <- Seq(
          "ANKKA_BROKER_BOOTSTRAP",
          "ANKKA_BROKER_NAMESPACE",
          "ANKKA_BROKER_CLUSTER"
        )
      do assertEquals(variable.r.findAllIn(operator).size, 1, s"${overlay._2}: $variable")
      val parsed = io.fabric8.kubernetes.client.utils.Serialization
        .unmarshal(operator, classOf[io.fabric8.kubernetes.api.model.apps.Deployment])
      assertEquals(parsed.getSpec.getTemplate.getSpec.getContainers.size, 1, overlay._2)
    assertEquals(
      environmentValue(operatorDeployment(remote, "cloud"), "ANKKA_BROKER_BOOTSTRAP"),
      environmentValue(operatorDeployment(local, "local"), "ANKKA_BROKER_BOOTSTRAP")
    )
  }

  test("the size of the broker is left for whoever installs it to state") {
    val source = Files.readString(repoRoot.resolve("kustomization/overlays/cloud/broker-size.yaml"))
    // Every value that sizes the broker is marked to be set.
    for key <- Seq("replicas:", "size:", "-Xms:", "-Xmx:", "memory:", "cpu:") do
      val lines = source.linesIterator.filter(_.trim.startsWith(key)).toVector
      assert(lines.nonEmpty, s"no $key in broker-size.yaml")
      assert(lines.forall(_.contains("# SET")), s"$key is not marked SET: $lines")
    // And the patch reaches the node pool the component renders.
    val pool = documentsOfKind(remote, "KafkaNodePool")
    assertEquals(pool.size, 1)
    assert(pool.head.contains("name: dual"), pool.head)
  }

  test("the broker's certificate is the platform's, from an issuer that exists, in both overlays") {
    for (render, name) <- Seq(remote -> "cloud", local -> "local") do
      val certificate = documentsOfKind(render, "Certificate")
        .map(yamlOf)
        .find(c => at(c, "metadata", "name") == "ankka-broker")
        .getOrElse(fail(s"$name: no broker certificate"))
      assertEquals(at(certificate, "spec", "uris"), java.util.List.of("ankka://platform/broker"))
      val issuer = at(certificate, "spec", "issuerRef", "name")
      assert(
        documentsOfKind(render, "ClusterIssuer")
          .map(yamlOf)
          .exists(i => at(i, "metadata", "name") == issuer),
        s"$name: the broker's certificate names '$issuer', which is not rendered"
      )
  }

  test("the operator is told which proxy image to run, from the registry, in its own container") {
    val remoteOperator = operatorDeployment(remote, "cloud")
    assert(
      remoteOperator.contains("ghcr.io/thinkmorestupidless/ankka-proxy:"),
      "the remote operator does not name the registry's proxy image"
    )
    // Set once, and the local default gone: a patch naming a container the operator does not have
    // would add a second container carrying only this variable.
    assertEquals("ANKKA_PROXY_IMAGE".r.findAllIn(remoteOperator).size, 1)
    assert(!remoteOperator.contains("ankka-proxy:latest"), "the local proxy image survived")
    val parsed = io.fabric8.kubernetes.client.utils.Serialization
      .unmarshal(remoteOperator, classOf[io.fabric8.kubernetes.api.model.apps.Deployment])
    assertEquals(
      parsed.getSpec.getTemplate.getSpec.getContainers.size,
      1,
      "the operator's Deployment has more than one container"
    )
    assert(operatorDeployment(local, "local").contains("ankka-proxy:latest"))
  }

  private def operatorDeployment(render: String, name: String): String =
    documentsOfKind(render, "Deployment")
      .find(_.contains("name: ankka-operator"))
      .getOrElse(fail(s"$name: no operator Deployment"))

  private def environmentValue(deployment: String, variable: String): String =
    s"""- name: $variable\\s+value: "?([^"\\s]+)"?""".r
      .findFirstMatchIn(deployment)
      .map(_.group(1))
      .getOrElse(fail(s"$variable is not set"))

  test("the operator is told the HTTPS port the gateway is reached on, as the control plane is") {
    // A web-hosted service's proxy tells the process the address a browser used, port included,
    // and the operator derives it; Envoy forwards the scheme and not the port. One value, from
    // ankka-platform, replaced into both Deployments, so the two cannot disagree.
    for (name, render, expected) <- Vector(("local", local, "8443"), ("cloud", remote, "443")) do
      val operator = operatorDeployment(render, name)
      assertEquals(environmentValue(operator, "ANKKA_HTTPS_PORT"), expected, name)
      assertEquals(
        environmentValue(operator, "ANKKA_HTTPS_PORT"),
        platformValue(render, "httpsPort"),
        name
      )
      val controlPlane = documentsOfKind(render, "Deployment")
        .find(_.contains("name: ankka-controlplane"))
        .getOrElse(fail(s"$name: no control plane"))
      assertEquals(
        environmentValue(controlPlane, "ANKKA_HTTPS_PORT"),
        environmentValue(operator, "ANKKA_HTTPS_PORT"),
        s"$name: the operator and the control plane disagree about the HTTPS port"
      )
  }

  // ── The console (feature 017) ─────────────────────────────────────────────

  private def platformValue(render: String, key: String): String =
    val configMap = documentsOfKind(render, "ConfigMap")
      .find(_.contains("name: ankka-platform"))
      .getOrElse(fail("no ankka-platform ConfigMap"))
    s"""(?m)^  $key: "?([^"\\s#]+)"?""".r
      .findFirstMatchIn(configMap)
      .map(_.group(1))
      .getOrElse(fail(s"ankka-platform has no $key"))

  private def consoleClient(render: String): String =
    val realm =
      documentsOfKind(render, "KeycloakRealmImport").headOption.getOrElse(fail("no realm import"))
    val start = realm.indexOf("clientId: ankka-console")
    assert(start >= 0, "the realm has no ankka-console client")
    // The client's own block: from the item that opens it to the next client, or the list's end.
    val itemStart = realm.lastIndexOf("\n    - ", start)
    val next      = realm.indexOf("\n    - ", start)
    realm.substring(itemStart, if next < 0 then realm.length else next)

  test("the console's authority is console.<base domain>, with the port unless it is 443") {
    // Derivable from the other two keys and written out only because a replacement cannot join
    // them; this is what keeps the three from disagreeing.
    for (name, render) <- Vector("local" -> local, "cloud" -> remote) do
      val base     = platformValue(render, "baseDomain")
      val port     = platformValue(render, "httpsPort")
      val expected = if port == "443" then s"console.$base" else s"console.$base:$port"
      assertEquals(platformValue(render, "consoleAuthority"), expected, name)
      val deployment = documentsOfKind(render, "Deployment")
        .find(_.contains("name: ankka-console"))
        .getOrElse(fail(s"$name: no console"))
      assert(
        deployment.contains(s"value: $expected"),
        s"$name: the console is not told its own address"
      )
      assert(
        consoleClient(render).contains(s"https://$expected/*"),
        s"$name: the realm client cannot redirect to the console"
      )
      val route = documentsOfKind(render, "HTTPRoute")
        .find(_.contains("name: ankka-console"))
        .getOrElse(fail(s"$name: no route"))
      assert(route.contains(s"- console.$base"), s"$name: the route is not at console.$base")
  }

  test("the console's development secrets reach only the local render") {
    val local_ = consoleClient(local)
    assert(local_.contains("secret: dev"), "the local realm client keeps the development secret")
    assert(local_.contains("http://localhost:3000/*"), "a laptop's console signs in locally")
    assert(documentsOfKind(local, "Secret").exists(_.contains("name: ankka-console-secrets")))

    val cloud = consoleClient(remote)
    assert(
      !cloud.linesIterator.exists(_.trim.startsWith("secret:")),
      s"the remote realm client carries a secret: $cloud"
    )
    assert(!cloud.contains("localhost"), s"the remote realm client redirects to localhost: $cloud")
    assert(
      !documentsOfKind(remote, "Secret").exists(_.contains("name: ankka-console-secrets")),
      "the console's development Secret reached the remote render"
    )
  }

  test("the console is a platform workload: registry image, probe port, preStop, no grant") {
    val deployment = documentsOfKind(remote, "Deployment")
      .find(_.contains("name: ankka-console"))
      .getOrElse(fail("no console"))
    assert(deployment.contains("image: ghcr.io/thinkmorestupidless/ankka-console:"), deployment)
    assert(deployment.contains("imagePullPolicy: IfNotPresent"), deployment)
    assert(deployment.contains("name: probe"), deployment)
    assert(!deployment.contains("name: management"), "the console is not an ankka cluster")
    assert(deployment.contains("preStop"), deployment)
    assert(deployment.contains("automountServiceAccountToken: false"), deployment)
    for kind <- Vector("RoleBinding", "ClusterRoleBinding") do
      assert(
        !documentsOfKind(remote, kind).exists(_.contains("name: ankka-console")),
        s"the console must hold no $kind"
      )
    val certificate = documentsOfKind(remote, "Certificate")
      .find(_.contains("name: ankka-console-service"))
      .getOrElse(fail("no console certificate"))
    assert(certificate.contains("ankka://platform/console"), certificate)
  }

  // ── Telemetry (feature 026) ─────────────────────────────────────────────────

  private lazy val collectorOnly = render("../tests/otel-collector")

  /** The parsed Deployment named `name`, from a render. */
  private def deploymentNamed(render: String, name: String) =
    io.fabric8.kubernetes.client.utils.Serialization.unmarshal(
      documentsOfKind(render, "Deployment")
        .find(_.linesIterator.exists(_.trim == s"name: $name"))
        .getOrElse(fail(s"no Deployment $name")),
      classOf[io.fabric8.kubernetes.api.model.apps.Deployment]
    )

  /** The telemetry variables on one named container of one named Deployment. */
  private def telemetryOn(render: String, deployment: String) =
    val container =
      deploymentNamed(render, deployment).getSpec.getTemplate.getSpec.getContainers.asScala
        .find(_.getName == deployment)
        .getOrElse(fail(s"$deployment has no container of its own name"))
    val env = container.getEnv.asScala.toVector
    (
      env.filter(_.getName == "ANKKA_OTLP_ENDPOINT"),
      env.filter(_.getName == "ANKKA_OTLP_HEADERS")
    )

  private def namespaceOf(document: String) =
    """(?m)^  namespace: (\S+)$""".r.findFirstMatchIn(document).map(_.group(1))

  test("an installation that is not a local platform names a collector of its own") {
    for deployment <- Vector("ankka-operator", "ankka-controlplane") do
      val (endpoint, headers) = telemetryOn(remote, deployment)
      assertEquals(endpoint.size, 1, s"$deployment: the address is set once")
      assertEquals(
        Option(endpoint.head.getValue).getOrElse(""),
        "",
        s"$deployment names a collector"
      )
      assertEquals(headers.size, 1, deployment)
      val ref = headers.head.getValueFrom.getSecretKeyRef
      assertEquals(
        (ref.getName, ref.getKey, ref.getOptional.booleanValue),
        ("ankka-telemetry", "headers", true)
      )
    // Neither the platform's collector nor the telemetry store, and nothing of either.
    assert(
      !remote.split("(?m)^---$").exists(d => namespaceOf(d).contains("ankka-telemetry")),
      "the remote overlay renders something in ankka-telemetry"
    )
    assert(!remote.contains("ankka-telemetry.svc"), "the remote overlay names a local collector")
    assert(!remote.contains("grafana/otel-lgtm"), "the remote overlay names the store's image")
    assert(
      !remote.contains("opentelemetry-collector"),
      "the remote overlay names a collector's image"
    )
    // Asked of the workloads, not the text: CRD schemas name hostPath too.
    for kind <- Vector("Deployment", "StatefulSet", "DaemonSet"); d <- documentsOfKind(remote, kind)
    do assert(!d.contains("hostPath:"), s"a remote $kind mounts a node's files")
    assert(
      documentsOfKind(remote, "DaemonSet").isEmpty,
      "the remote overlay runs an agent on every node"
    )
  }

  test("a local platform has a telemetry store, its route, its agent, and its address everywhere") {
    val address = "http://lgtm.ankka-telemetry.svc.cluster.local:4318"
    for deployment <- Vector("ankka-operator", "ankka-controlplane") do
      val (endpoint, headers) = telemetryOn(local, deployment)
      assertEquals(endpoint.map(_.getValue), Vector(address), deployment)
      assertEquals(headers.size, 1, deployment)
    val store = deploymentNamed(local, "lgtm")
    assertEquals(store.getMetadata.getNamespace, "ankka-telemetry")
    assert(
      store.getSpec.getTemplate.getSpec.getContainers.asScala.head.getImage
        .startsWith("grafana/otel-lgtm:")
    )
    val route = documentsOfKind(local, "HTTPRoute")
      .find(_.contains("name: grafana"))
      .getOrElse(fail("no route"))
    assert(route.contains("- grafana.127.0.0.1.sslip.io"), route)
    assert(!route.contains("BASE_DOMAIN"), "a placeholder survived")
    assert(route.contains("request: 0s"), "the gateway would cut Grafana at fifteen seconds")
    val agent = documentsOfKind(local, "DaemonSet")
      .find(_.contains("name: log-agent"))
      .getOrElse(fail("no agent"))
    assert(agent.contains("path: /var/log/pods"), agent)
    assert(documentsOfKind(local, "ConfigMap").exists(_.contains("name: log-agent")))
    val policy = documentsOfKind(local, "NetworkPolicy")
      .find(_.contains("name: lgtm"))
      .getOrElse(fail("no policy"))
    assert(policy.contains("app.kubernetes.io/managed-by: ankka"), policy)
    assert(policy.contains("envoy-gateway-system"), policy)
    // And not the platform's collector: the two make one namespace.
    assert(!local.contains("otel/opentelemetry-collector:"), "the local overlay lists both")
  }

  test("the platform's collector, which no overlay lists, still renders") {
    val names = collectorOnly
      .split("(?m)^---$")
      .toVector
      .flatMap(d => """(?m)^kind: (\S+)$""".r.findFirstMatchIn(d).map(_.group(1)))
      .toSet
    assertEquals(names, Set("Namespace", "ConfigMap", "Deployment", "Service", "NetworkPolicy"))
    val policy = documentsOfKind(collectorOnly, "NetworkPolicy").head
    assertEquals("app.kubernetes.io/managed-by: ankka".r.findAllIn(policy).size, 2, policy)
    assert(policy.contains("port: 4318") && policy.contains("port: 4317"), policy)
    val config = documentsOfKind(collectorOnly, "ConfigMap").head
    assert(config.contains("verbosity: detailed"), "the collector would print no ids")
  }

  // ── Object storage (feature 034) ──────────────────────────────────────────

  private val StoreVariables = Vector(
    "ANKKA_OBJECT_STORE_ADMIN_URL",
    "ANKKA_OBJECT_STORE_ADMIN_TOKEN",
    "ANKKA_OBJECT_STORE_ENDPOINT",
    "ANKKA_OBJECT_STORE_REGION",
    "ANKKA_OBJECT_STORE_SERVICE"
  )

  test(
    "both overlays tell the operator where the object store is, once each, in its one container"
  ) {
    for (render, name) <- Vector(remote -> "cloud", local -> "local") do
      val operator = operatorDeployment(render, name)
      for variable <- StoreVariables do
        assertEquals(variable.r.findAllIn(operator).size, 1, s"$name: $variable")
      val parsed = io.fabric8.kubernetes.client.utils.Serialization
        .unmarshal(operator, classOf[io.fabric8.kubernetes.api.model.apps.Deployment])
      assertEquals(parsed.getSpec.getTemplate.getSpec.getContainers.size, 1, name)
      // The token is referenced, never written into the Deployment.
      assert(operator.contains("name: ankka-object-store-admin"), name)
  }

  test("the object store's development secrets reach only the local render") {
    def secretNamed(render: String, secret: String) =
      documentsOfKind(render, "Secret").exists(_.contains(s"name: $secret"))
    for secret <- Vector("garage-secrets", "ankka-object-store-admin") do
      assert(secretNamed(local, secret), s"local has no $secret")
      assert(!secretNamed(remote, secret), s"the cloud render carries $secret")
  }

  test("the object store is the pinned image, in a namespace no project can have") {
    for (render, name) <- Vector(remote -> "cloud", local -> "local") do
      val store = documentsOfKind(render, "StatefulSet")
        .find(_.contains("name: garage"))
        .getOrElse(fail(s"$name: no object store"))
      assert(store.contains("image: dxflrs/garage:v2.3.0"), store)
      assert(store.contains("namespace: garage-system"), store)
      val namespace = documentsOfKind(render, "Namespace")
        .find(_.contains("name: garage-system"))
        .getOrElse(fail(s"$name: no garage-system namespace"))
      // The label that admits a namespace's routes and pods everywhere; the store needs neither.
      assert(!namespace.contains("app.kubernetes.io/managed-by"), namespace)
  }

  test("the operator's one grant in the store's namespace is on reference grants, and no more") {
    val role = documentsOfKind(local, "Role")
      .find(_.contains("name: ankka-operator-grants"))
      .getOrElse(fail("no role for the operator in the store's namespace"))
    assert(role.contains("namespace: garage-system"), role)
    assert(role.contains("referencegrants"), role)
    assertEquals("- apiGroups:".r.findAllIn(role).size, 1, role)
    assert(!role.contains("delete") && !role.contains("list") && !role.contains("watch"), role)
  }

  // ── The secret store (feature 038) ────────────────────────────────────────

  /**
   * The `ankka-platform` ConfigMap's `data`, parsed: a value may be empty, which no regex reads.
   */
  private def platformData(render: String): Map[String, String] =
    val document = documentsOfKind(render, "ConfigMap")
      .find(_.contains("name: ankka-platform"))
      .getOrElse(fail("no ankka-platform ConfigMap"))
    at(yamlOf(document), "data")
      .asInstanceOf[java.util.Map[String, Any]]
      .asScala
      .map((k, v) => k -> Option(v).fold("")(_.toString))
      .toMap

  private val secretSettings = Vector(
    ("secretBackend", "ANKKA_SECRET_BACKEND", Vector("ankka-controlplane", "ankka-operator")),
    ("secretMove", "ANKKA_SECRET_MOVE", Vector("ankka-operator")),
    ("secretVersionsKept", "ANKKA_SECRET_VERSIONS_KEPT", Vector("ankka-operator")),
    ("secretRecordRetention", "ANKKA_SECRET_RECORD_RETENTION", Vector("ankka-controlplane"))
  )

  test(
    "the secret store's settings reach the operator and the control plane once each, from the ConfigMap"
  ) {
    for (name, render) <- Vector("local" -> local, "cloud" -> remote) do
      val data = platformData(render)
      for (key, variable, deployments) <- secretSettings; deployment <- deployments do
        val container =
          deploymentNamed(render, deployment).getSpec.getTemplate.getSpec.getContainers.asScala
            .find(_.getName == deployment)
            .getOrElse(fail(s"$name: $deployment has no container of its own name"))
        val set = container.getEnv.asScala.filter(_.getName == variable).toVector
        assertEquals(set.size, 1, s"$name: $deployment sets $variable once")
        assertEquals(
          Option(set.head.getValue).getOrElse(""),
          data.getOrElse(key, fail(s"$name: ankka-platform has no $key")),
          s"$name: $deployment's $variable is the ConfigMap's $key"
        )
    assertEquals(platformData(local)("secretBackend"), "postgres")
  }

  test(
    "both overlays keep the record of secret reads in a database of its own, which the control plane is told of"
  ) {
    for (name, render) <- Vector("local" -> local, "cloud" -> remote) do
      val cluster = documentsOfKind(render, "Cluster")
        .find(_.contains("name: ankka-secret-reads-db"))
        .getOrElse(fail(s"$name: no database for the record of secret reads"))
      assert(cluster.contains("namespace: ankka-controlplane"), cluster)
      val container =
        deploymentNamed(
          render,
          "ankka-controlplane"
        ).getSpec.getTemplate.getSpec.getContainers.asScala
          .find(_.getName == "ankka-controlplane")
          .getOrElse(fail(s"$name: the control plane has no container of its own name"))
      for variable <- Vector("HOST", "PORT", "NAME", "USER", "PASSWORD") do
        val set = container.getEnv.asScala.filter(_.getName == s"ANKKA_SECRET_RECORDS_DB_$variable")
        assertEquals(set.size, 1, s"$name: ANKKA_SECRET_RECORDS_DB_$variable is set once")
        assertEquals(set.head.getValueFrom.getSecretKeyRef.getName, "ankka-secret-reads-db-app")
      assertEquals(
        deploymentNamed(
          render,
          "ankka-controlplane"
        ).getSpec.getTemplate.getSpec.getContainers.size,
        1,
        s"$name: the patch added a container"
      )
  }

  // ── The installation's cloud (feature 044) ────────────────────────────────

  private val CloudVariables = Vector(
    "cloudProvider"             -> "ANKKA_CLOUD_PROVIDER",
    "cloudAccount"              -> "ANKKA_CLOUD_ACCOUNT",
    "cloudLocation"             -> "ANKKA_CLOUD_LOCATION",
    "cloudKmsKey"               -> "ANKKA_CLOUD_KMS_KEY",
    "cloudAcknowledgementBound" -> "ANKKA_CLOUD_ACKNOWLEDGEMENT_BOUND",
    "cloudRotationGrace"        -> "ANKKA_CLOUD_ROTATION_GRACE"
  )

  /** The control plane reads the four that say where; the two durations are not its to use. */
  private val ControlPlaneCloudVariables = CloudVariables.take(4).map(_._2)

  private def containerEnv(render: String, deployment: String): Map[String, String] =
    deploymentNamed(render, deployment).getSpec.getTemplate.getSpec.getContainers.asScala
      .flatMap(_.getEnv.asScala)
      .filter(_.getValue != null)
      .map(e => e.getName -> e.getValue)
      .toMap

  private def cloudSettingsData(render: String, name: String): Map[String, String] =
    io.fabric8.kubernetes.client.utils.Serialization
      .unmarshal(
        documentsOfKind(render, "ConfigMap")
          .find(_.linesIterator.exists(_.trim == "name: ankka-cloud"))
          .getOrElse(fail(s"$name: no ankka-cloud ConfigMap")),
        classOf[io.fabric8.kubernetes.api.model.ConfigMap]
      )
      .getData
      .asScala
      .toMap

  test("both overlays name each cloud setting once on the operator and the control plane") {
    for (render, name) <- Vector(remote -> "cloud", local -> "local") do
      val operator = operatorDeployment(render, name)
      for (_, variable) <- CloudVariables do
        assertEquals(s"- name: $variable\\b".r.findAllIn(operator).size, 1, s"$name: $variable")
      val controlPlane = documentsOfKind(render, "Deployment")
        .find(_.linesIterator.exists(_.trim == "name: ankka-controlplane"))
        .getOrElse(fail(s"$name: no control plane"))
      for variable <- ControlPlaneCloudVariables do
        assertEquals(
          s"- name: $variable\\b".r.findAllIn(controlPlane).size,
          1,
          s"$name: $variable"
        )
      assertEquals(
        cloudSettingsData(render, name).keySet,
        CloudVariables.map(_._2).toSet,
        s"$name: the provider's settings"
      )
  }

  test("both overlays ship no cloud provider, so an installation names one on purpose") {
    for (render, name) <- Vector(remote -> "cloud", local -> "local") do
      assertEquals(containerEnv(render, "ankka-operator")("ANKKA_CLOUD_PROVIDER"), "none", name)
      assertEquals(cloudSettingsData(render, name)("ANKKA_CLOUD_PROVIDER"), "none", name)
  }

  test("a cloud setting written once in ankka-platform reaches every process that reads it") {
    // The defaults agree with the components' literals, so a render of the overlays as shipped
    // would pass with every replacement missing. A copy of the local overlay beside it, so its
    // relative paths still resolve, says something else in each key, and each must arrive.
    val probe =
      repoRoot.resolve(s"kustomization/overlays/.cloud-probe-${ProcessHandle.current.pid}")
    val source = repoRoot.resolve("kustomization/overlays/local")
    Files.createDirectories(probe)
    try
      Files.list(source).iterator.asScala.foreach(f => Files.copy(f, probe.resolve(f.getFileName)))
      val distinct = CloudVariables.map((key, _) => key -> s"probe-$key").toMap +
        ("cloudProvider"      -> "gcp") + ("cloudAcknowledgementBound" -> "17s") +
        ("cloudRotationGrace" -> "19s")
      val configMap = probe.resolve("platform-configmap.yaml")
      val rewritten = Files.readString(configMap).linesIterator.map { line =>
        distinct
          .collectFirst { case (k, v) if line.startsWith(s"  $k:") => s"  $k: \"$v\"" }
          .getOrElse(line)
      }
      Files.writeString(configMap, rewritten.mkString("\n") + "\n")
      val rendered = Process(Seq("kubectl", "kustomize", probe.toString)).!!
      val operator = containerEnv(rendered, "ankka-operator")
      val cp       = containerEnv(rendered, "ankka-controlplane")
      val provider = cloudSettingsData(rendered, "probe")
      for (key, variable) <- CloudVariables do
        assertEquals(operator(variable), distinct(key), s"operator: $variable")
        assertEquals(provider(variable), distinct(key), s"provider: $variable")
      for variable <- ControlPlaneCloudVariables do
        val key = CloudVariables.find(_._2 == variable).get._1
        assertEquals(cp(variable), distinct(key), s"control plane: $variable")
    finally
      Files.list(probe).iterator.asScala.foreach(Files.delete)
      Files.delete(probe)
  }

  test("the cloud provider's grant has no delete and cannot read a Secret") {
    for (render, name) <- Vector(remote -> "cloud", local -> "local") do
      val role = documentsOfKind(render, "ClusterRole")
        .find(_.linesIterator.exists(_.trim == "name: ankka-cloud-provider"))
        .getOrElse(fail(s"$name: no ClusterRole for the cloud provider"))
      val parsed = io.fabric8.kubernetes.client.utils.Serialization
        .unmarshal(role, classOf[io.fabric8.kubernetes.api.model.rbac.ClusterRole])
      val rules = parsed.getRules.asScala.toVector
      assert(rules.forall(!_.getVerbs.contains("delete")), s"$name: a delete in $role")
      val onSecrets = rules.filter(_.getResources.contains("secrets")).flatMap(_.getVerbs.asScala)
      assertEquals(onSecrets.toSet, Set("create", "patch"), name)
      val onRequests =
        rules.filter(_.getResources.contains("cloudresources")).flatMap(_.getVerbs.asScala)
      assertEquals(onRequests.toSet, Set("get", "list", "watch"), s"$name: the request is not its")
      assertEquals(
        rules.flatMap(_.getResources.asScala).toSet,
        Set("cloudresources", "cloudresources/status", "secrets"),
        s"$name: nothing else"
      )
  }

  test("the operator writes a cloud request and never its answer, and deletes none") {
    for (render, name) <- Vector(remote -> "cloud", local -> "local") do
      val role = documentsOfKind(render, "ClusterRole")
        .find(_.linesIterator.exists(_.trim == "name: ankka-operator"))
        .getOrElse(fail(s"$name: no operator ClusterRole"))
      val rules = io.fabric8.kubernetes.client.utils.Serialization
        .unmarshal(role, classOf[io.fabric8.kubernetes.api.model.rbac.ClusterRole])
        .getRules
        .asScala
      def verbs(resource: String) =
        rules.filter(_.getResources.contains(resource)).flatMap(_.getVerbs.asScala).toSet
      assertEquals(verbs("cloudresources"), Set("get", "list", "watch", "create", "patch"), name)
      assertEquals(verbs("cloudresources/status"), Set("get"), name)
  }
