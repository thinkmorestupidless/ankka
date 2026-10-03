package com.thinkmorestupidless.ankka.controlplane

import com.thinkmorestupidless.ankka.controlplane.api.ProjectId
import com.thinkmorestupidless.ankka.operator.Names

import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters.*

/**
 * No tenant may be issued an identity the platform's own workloads use.
 *
 * A workload's identity is `ankka://<project>/<service>`, and the platform's own are in the project
 * `platform`. The control plane refuses to create that project and the operator refuses to render a
 * resource in it, each from a list of its own: `controlplane-api` and `operator` share no module,
 * on purpose. This is the one place that can see both lists and the manifests that ask for the
 * platform's certificates, so it is where they are held to each other.
 */
final class ReservedProjectIdsSuite extends munit.FunSuite:

  private def repoRoot: Path =
    Iterator
      .iterate(Paths.get("").toAbsolutePath)(_.getParent)
      .takeWhile(_ != null)
      .find(p => Files.isDirectory(p.resolve("kustomization")))
      .getOrElse(fail("could not find the repository root"))

  /** Every `ankka://<project>/<service>` a platform manifest asks cert-manager to issue. */
  private def platformIdentities: Vector[(Path, String, String)] =
    val Identity = """ankka://([a-z0-9-]+)/([a-z0-9-]+)""".r
    val stream   = Files.walk(repoRoot.resolve("kustomization"))
    try
      stream.iterator.asScala
        .filter(p => Files.isRegularFile(p) && p.toString.endsWith(".yaml"))
        .toVector
        .flatMap { file =>
          Identity
            .findAllMatchIn(Files.readString(file))
            .map(m => (repoRoot.relativize(file), m.group(1), m.group(2)))
        }
    finally stream.close()

  test("the control plane and the operator reserve the same project ids") {
    assertEquals(ProjectId.Reserved, Names.ReservedProjectIds)
  }

  test("the platform's manifests ask for identities, so this suite is reading something") {
    val services = platformIdentities.map((_, _, service) => service).toSet
    assert(services.contains("controlplane"), s"found only $services")
  }

  test("every identity the platform's own workloads carry is in a reserved project") {
    val open = platformIdentities.filterNot((_, project, _) => ProjectId.Reserved.contains(project))
    assert(
      open.isEmpty,
      open
        .map((file, project, service) =>
          s"$file asks for ankka://$project/$service, and a tenant could create the project " +
            s"'$project': add it to ProjectId.Reserved and Names.ReservedProjectIds"
        )
        .mkString("\n")
    )
  }
