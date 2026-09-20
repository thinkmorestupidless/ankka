package com.thinkmorestupidless.ankka.controlplane

import munit.FunSuite

import java.nio.file.{Files, Path, Paths}
import scala.sys.process.*
import scala.util.Try

/**
 * That `overlays/remote` still renders, and still differs from `overlays/local` in exactly the ways
 * it is supposed to.
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

  private lazy val remote = render("remote")
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

  test("the development bearer token cannot reach a remote cluster") {
    // The single most damaging thing that could leak out of this overlay. `components/controlplane`
    // ships `dev-local-token` so a fresh kind cluster needs no extra step, and that value is public
    // in this repository; the remote overlay deletes the Secret outright rather than overriding it,
    // so the control plane will not start until a real one is created out of band.
    assert(!remote.contains("dev-local-token"), "the development token is in the remote render")
    assert(local.contains("dev-local-token"), "...but it should still be in the local one")
  }

  test("no local-only address survives into the remote render") {
    for leaked <- Vector("sslip.io", "127.0.0.1", ":8443", "\"8443\"") do
      assert(!remote.contains(leaked), s"'$leaked' leaked into the remote overlay")
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
