package com.thinkmorestupidless.ankka.controlplane.api

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters.*

/**
 * The two scenarios of `features/documentation/grpc.feature`, one test each, reading the published
 * pages. Every assertion names a sentence a reader would need, so a page rewritten without it fails
 * here rather than in a developer's hands.
 */
class GrpcDocumentationSuite extends munit.FunSuite:

  private val repoRoot: Path =
    Iterator
      .iterate(Paths.get("").toAbsolutePath)(_.getParent)
      .takeWhile(_ != null)
      .find(p => Files.isDirectory(p.resolve("docs")) && Files.exists(p.resolve("mkdocs.yml")))
      .getOrElse(fail("no repository root above the working directory"))

  private def page(path: String): String =
    Files.readString(repoRoot.resolve("docs").resolve(path), UTF_8)

  private def says(path: String, text: String): Unit =
    assert(page(path).contains(text), s"$path does not say: $text")

  test("the documentation describes building and testing a gRPC endpoint") {
    // how a gRPC endpoint is built from a service definition
    says("build/grpc-endpoints.md", "## The service definition")
    says("build/grpc-endpoints.md", "compilerplugin")
    says("build/grpc-endpoints.md", "extends GrpcEndpoint(CartServiceGrpc.SERVICE)")
    // how a test calls a gRPC endpoint with the test kit
    says("build/grpc-endpoints.md", "AnkkaTestKit.start")
    says("build/grpc-endpoints.md", "GrpcChannels.plaintext")
    // how an exposed service's gRPC endpoint is called from outside the cluster
    says("deploy/expose.md", "## gRPC at the same hostname")
    says("deploy/expose.md", "grpcurl -cacert ~/.ankka/local-ca.crt")
    // how a service opts into reflection, and that it lists the methods of every endpoint
    says("build/grpc-endpoints.md", ".withReflection(")
    says(
      "build/grpc-endpoints.md",
      "every endpoint's methods are listed to whoever reflection's ACL"
    )
  }

  test("the documentation says what gRPC does not do") {
    says("reference/limitations.md", "gRPC endpoints are for Scala services.")
    says("reference/limitations.md", "A web page cannot call a gRPC endpoint")
    says("reference/limitations.md", "The local console does not call gRPC methods.")
    // ...and no page says a service serves HTTP only, as the documentation once did.
    val pages = Files
      .walk(repoRoot.resolve("docs"))
      .iterator
      .asScala
      .filter(_.toString.endsWith(".md"))
      .toVector
    val stale = Seq(
      "One port, HTTP only",
      "It forwards no gRPC or HTTP/2 to services",
      "with no gRPC or HTTP/2 to services"
    )
    for p <- pages; s <- stale do
      assert(!Files.readString(p, UTF_8).contains(s), s"${repoRoot.relativize(p)} still says: $s")
  }
