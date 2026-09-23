package com.thinkmorestupidless.ankka.controlplane

import munit.FunSuite

import java.nio.file.{Files, Path, Paths}
import scala.sys.process.*
import scala.util.Try

/**
 * That `overlays/arrakis` — the first production cluster, once `overlays/remote` — still renders,
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
final class RemoteOverlaySuite extends FunSuite:

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

  private lazy val remote = render("arrakis")
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
      remoteOperator.contains("europe-west2-docker.pkg.dev/ankka-ops/ankka/ankka-sidecar:"),
      "the remote operator does not name the registry's sidecar image"
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
      // The realm is not a resource: it is imported after the instance is Ready.
      assert(
        documentsOfKind(render, "KeycloakRealmImport").isEmpty,
        "the realm import must be rendered by the deploy script, not checked in"
      )
  }

  test(
    "auth.<base domain> reaches the route, the instance's hostname and the control plane's derivation"
  ) {
    val routes = documentsOfKind(remote, "HTTPRoute")
    assert(
      routes.exists(r => r.contains("name: ankka-keycloak") && r.contains("- auth.ankka.cloud")),
      "the identity provider's route"
    )
    assert(
      documentsOfKind(remote, "Keycloak").exists(_.contains("hostname: auth.ankka.cloud")),
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
        "ankka-keycloak-service.ankka-auth.svc:8080/realms/ankka/protocol/openid-connect/certs"
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
    for leaked <- Vector("sslip.io", "127.0.0.1", ":8443", "\"8443\"") do
      assert(!remote.contains(leaked), s"'$leaked' leaked into the remote overlay")
  }

  test("the real domain reaches every place that must agree about it") {
    // Five places, one source (platform-configmap.yaml). They cannot be allowed to drift: the
    // certificate covers one of them, the listener matches on another, and a mismatch is a
    // gateway that serves a certificate for a name nobody asked for.
    val domain = "ankka.cloud"
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

    val issuers = documentsOfKind(remote, "Issuer") ++ documentsOfKind(remote, "ClusterIssuer")
    assert(issuers.nonEmpty, "the remote overlay issues no certificates at all")
    assert(
      issuers.forall(_.contains("acme:")),
      s"every remote issuer must come from a real authority, got: $issuers"
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

    // The local overlay is the mirror image, and for the mirror-image reason: its CA secret sits
    // beside the Certificate, so a ClusterIssuer would look in the wrong namespace (CLAUDE.md).
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
