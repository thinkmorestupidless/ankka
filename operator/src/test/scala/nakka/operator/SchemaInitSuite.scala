package nakka.operator

import scala.jdk.CollectionConverters.*

/**
 * The init container and the Deployment wiring around it — pure, no cluster.
 *
 * See
 * [contracts/schema-init.md](../../../../../specs/002-cnpg-database-provisioning/contracts/schema-init.md).
 */
class SchemaInitSuite extends munit.FunSuite:

  test("the container references the service's own credential secret") {
    val c          = SchemaInit.container("cart")
    val secretName = c.getEnvFrom.asScala.head.getSecretRef.getName
    assertEquals(secretName, "cart-db")
  }

  test("the container mounts the schema volume read-only") {
    val c     = SchemaInit.container("cart")
    val mount = c.getVolumeMounts.asScala.head
    assertEquals(mount.getName, SchemaInit.VolumeName)
    assertEquals(mount.getMountPath, SchemaInit.MountPath)
    assertEquals(mount.getReadOnly.booleanValue, true)
  }

  test("the volume sources the shared per-project schema ConfigMap, not a per-service one") {
    val v = SchemaInit.volume()
    assertEquals(v.getConfigMap.getName, CnpgRendering.schemaConfigMapName)
  }

  test("the script waits before touching the database") {
    val script     = SchemaInit.container("cart").getCommand.asScala.last
    val waitLine   = script.linesIterator.indexWhere(_.contains("pg_isready"))
    val schemaLine = script.linesIterator.toVector.indexWhere(_.contains("for f in"))
    assert(waitLine >= 0, "expected a pg_isready wait")
    assert(schemaLine > waitLine, "the schema must be applied only after the wait succeeds")
  }

  test("the script applies every file in the mounted schema directory") {
    val script = SchemaInit.container("cart").getCommand.asScala.last
    assert(script.contains(s"${SchemaInit.MountPath}/*.sql"), script)
  }

  test("the script revokes PUBLIC connect on its own database, after the schema") {
    val script     = SchemaInit.container("cart").getCommand.asScala.last
    val lines      = script.linesIterator.toVector
    val schemaLine = lines.indexWhere(_.contains("for f in"))
    val revokeLine = lines.indexWhere(_.contains("REVOKE CONNECT"))
    assert(revokeLine >= 0, "expected the REVOKE step — see research R9")
    assert(revokeLine > schemaLine, "REVOKE must run after the schema is applied, not before")
  }

  test("the REVOKE statement is syntactically well-formed shell — the exact bug this guards") {
    // A stray extra backslash here once produced `\cart\` instead of `"cart"` in the emitted
    // SQL, which failed with a syntax error rather than revoking anything. This does not run a
    // shell, but it does assert the balance that made that bug possible is not there.
    val script = SchemaInit.container("cart").getCommand.asScala.last
    val revokeLine =
      script.linesIterator.find(_.contains("REVOKE CONNECT")).getOrElse(fail("no REVOKE line"))
    assertEquals(
      revokeLine.count(_ == '\\'),
      2,
      s"expected exactly one backslash per embedded quote: $revokeLine"
    )
  }

  test("the script never echoes a secret value") {
    val script = SchemaInit.container("cart").getCommand.asScala.last
    assert(
      !script.contains("echo"),
      "no reason for this script to echo anything, especially not a password"
    )
  }

  test("Rendering: the escape hatch renders no init container and no database envFrom") {
    val spec = nakka.crd.NakkaServiceSpec(
      projectId = "checkout",
      serviceName = "cart",
      image = "cart:1.0",
      provisionDatabase = false
    )
    val resource = {
      val r = new nakka.crd.NakkaService
      r.setMetadata(
        new io.fabric8.kubernetes.api.model.ObjectMetaBuilder()
          .withNamespace("nakka-checkout")
          .withName("cart")
          .build()
      )
      r.setSpec(spec)
      r
    }
    val deployment =
      Rendering.deployment(resource, spec, "nakka-checkout", ProvisioningPlan.Supplied)
    val podSpec = deployment.getSpec.getTemplate.getSpec
    assert(podSpec.getInitContainers.isEmpty, "no schema-init container on the escape hatch")
    assert(
      podSpec.getContainers.get(0).getEnvFrom.isEmpty,
      "no database envFrom on the escape hatch — the descriptor's own env is all there is"
    )
  }

  test(
    "Rendering: a provisioned service gets exactly one init container and the credential envFrom"
  ) {
    val spec = nakka.crd.NakkaServiceSpec(
      projectId = "checkout",
      serviceName = "cart",
      image = "cart:1.0",
      provisionDatabase = true
    )
    val resource = {
      val r = new nakka.crd.NakkaService
      r.setMetadata(
        new io.fabric8.kubernetes.api.model.ObjectMetaBuilder()
          .withNamespace("nakka-checkout")
          .withName("cart")
          .build()
      )
      r.setSpec(spec)
      r
    }
    val plan       = ProvisioningPlan.Waiting(true, true, true, true, None)
    val deployment = Rendering.deployment(resource, spec, "nakka-checkout", plan)
    val podSpec    = deployment.getSpec.getTemplate.getSpec

    assertEquals(podSpec.getInitContainers.size, 1)
    assertEquals(podSpec.getInitContainers.get(0).getName, "nakka-schema")
    assertEquals(
      podSpec.getContainers.get(0).getEnvFrom.asScala.head.getSecretRef.getName,
      "cart-db"
    )
  }

  test(
    "the schema is applied under an advisory lock taken in the SAME psql session, before the first file"
  ) {
    // Several pods of one service now cold-start together, and CREATE TABLE IF NOT EXISTS races.
    val script  = SchemaInit.container("cart").getCommand.asScala.last
    val lines   = script.linesIterator.toVector
    val lockAt  = lines.indexWhere(_.contains("pg_advisory_lock"))
    val filesAt = lines.indexWhere(_.contains("-f %s"))
    assert(lockAt >= 0, "no advisory lock")
    assert(lockAt < filesAt, "the lock must come before the files")
    // One session: every line between the psql invocation and the REVOKE is a continuation.
    val psqlAt   = lines.lastIndexWhere(_.trim.startsWith("psql "))
    val revokeAt = lines.indexWhere(_.contains("REVOKE CONNECT"))
    for i <- psqlAt until revokeAt do
      assert(
        lines(i).endsWith("\\"),
        s"line $i is not a continuation, so the lock is in a different session: ${lines(i)}"
      )
      assert(
        !lines(i).endsWith("\\\\"),
        s"line $i ends in a double backslash — triple-quoted strings do no escaping (feature 003)"
      )
  }
