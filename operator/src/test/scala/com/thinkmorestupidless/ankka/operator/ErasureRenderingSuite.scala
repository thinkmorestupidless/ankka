package com.thinkmorestupidless.ankka.operator

import com.thinkmorestupidless.ankka.core.PlatformVariables
import com.thinkmorestupidless.ankka.crd.{AnkkaService, AnkkaServiceSpec, Buckets}
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder

import scala.jdk.CollectionConverters.*

/**
 * What the operator renders for erasure (feature 042): its two settings, the keyring's address and
 * the namespace's label for a service that declares personal fields and for nothing else, and the
 * platform's credentials on the erasure log and the keyring's database.
 */
class ErasureRenderingSuite extends munit.FunSuite:

  private val address = "https://keyring.ankka-keyring.svc:8443"
  private val on      = Settings.default.copy(erasure = Some(KeyringSettings(address)))

  private def lookup(values: Map[String, String]): (String, String) => Option[String] =
    (_, variable) => values.get(variable)

  private val spec = AnkkaServiceSpec(
    projectId = "checkout",
    serviceName = "cart",
    generation = 4L,
    image = "registry.example.com/acme/cart:1.4.2"
  )

  private def render(s: AnkkaServiceSpec, settings: Settings): Vector[Action] =
    val r = new AnkkaService
    r.setMetadata(
      new ObjectMetaBuilder().withNamespace("ankka-checkout").withName("cart").withUid("u").build()
    )
    r.setSpec(s)
    Rendering.render(r, settings, ProvisioningPlan.Supplied) match
      case Right(actions) => actions
      case Left(problems) => fail(problems.mkString("; "))

  private def platformEnv(actions: Vector[Action]): Map[String, String] =
    val deployment = actions.collectFirst { case Action.ApplyDeployment(d) => d }.get
    deployment.getSpec.getTemplate.getSpec.getContainers.asScala.head.getEnv.asScala
      .filter(_.getValue != null)
      .map(e => e.getName -> e.getValue)
      .toMap

  test("erasure is off unless it is on, and on needs the keyring's address") {
    assertEquals(KeyringSettings.read(lookup(Map.empty)), None)
    assertEquals(KeyringSettings.read(lookup(Map(PlatformVariables.Erasure -> "off"))), None)
    assertEquals(
      KeyringSettings.read(
        lookup(
          Map(PlatformVariables.Erasure -> "on", PlatformVariables.KeyringAddress -> address)
        )
      ),
      Some(KeyringSettings(address))
    )
    val halves = intercept[IllegalStateException](
      KeyringSettings.read(lookup(Map(PlatformVariables.Erasure -> "on")))
    )
    assert(halves.getMessage.contains(PlatformVariables.KeyringAddress), halves.getMessage)
    val nonsense = intercept[IllegalStateException](
      KeyringSettings.read(lookup(Map(PlatformVariables.Erasure -> "yes")))
    )
    assert(nonsense.getMessage.contains("'on' or 'off'"), nonsense.getMessage)
  }

  test("a service that declares personal fields is told where the keyring is, and admitted to it") {
    val actions = render(spec.copy(personal = true), on)
    assertEquals(platformEnv(actions).get(PlatformVariables.KeyringAddress), Some(address))
    assert(
      actions.contains(
        Action.LabelNamespace("ankka-checkout", Map("ankka.cloud/personal" -> "true"))
      ),
      actions.map(_.describe).mkString("\n")
    )
  }

  test("nothing changes for a service that declares none, or with erasure off") {
    for (s, settings) <- Vector(spec -> on, spec.copy(personal = true) -> Settings.default) do
      val actions = render(s, settings)
      assertEquals(render(s, settings.copy(erasure = None)), actions)
      assert(!platformEnv(actions).contains(PlatformVariables.KeyringAddress))
      assert(!actions.exists(_.isInstanceOf[Action.LabelNamespace]))
  }

  private val store = ObjectStoreSettings(
    "http://garage:3903",
    "token",
    "http://garage:3900",
    "garage",
    ObjectStoreSettings.ServiceRef("garage-system", "garage", 3900)
  )

  test("the erasure log has a bucket, a writer's key and a reader's, only with erasure on") {
    assertEquals(
      PlatformErasure.actions(Settings.default.copy(objectStore = Some(store))),
      Vector.empty
    )
    val actions = PlatformErasure.actions(on.copy(objectStore = Some(store)))
    assert(actions.contains(Action.EnsureBucket(Buckets.ErasureLog)), actions.toString)
    assertEquals(
      actions.collect { case c: Action.EnsurePlatformCredential => c },
      Vector(
        Action.EnsurePlatformCredential(
          "ankka-controlplane",
          "ankka-erasure-log",
          "platform.erasure-log",
          "erasure-log-controlplane",
          BucketPermission.ReadWrite
        ),
        Action.EnsurePlatformCredential(
          "ankka-keyring",
          "ankka-erasure-log",
          "platform.erasure-log",
          "erasure-log-keyring",
          BucketPermission.ReadOnly
        )
      )
    )
    // The keyring's secret key, which its root key is kept under on Postgres, store or no store.
    for settings <- Vector(on, on.copy(objectStore = Some(store))) do
      assert(
        PlatformErasure
          .actions(settings)
          .exists {
            case Action.EnsureSecretKey("ankka-keyring", "ankka-keyring-secret-key", _) => true
            case _                                                                      => false
          }
      )
  }

  test("the keyring's database is archived beside the control plane's, after it") {
    val backedUp = Settings.default.copy(
      objectStore = Some(store),
      backups = BackupSettings(target = Some(BackupTarget.ObjectStore))
    )
    assert(!PlatformBackups.actions(backedUp).exists(_.describe.contains("keyring")))
    val actions = PlatformBackups.actions(backedUp.copy(erasure = on.erasure))
    assertEquals(actions.head, Action.EnsureBucket("platform.backups-controlplane"))
    assert(actions.contains(Action.EnsureBucket("platform.backups-keyring")), actions.toString)
    assert(
      actions.contains(
        Action.EnsureBackupCredential(
          "ankka-keyring",
          "platform.backups-keyring",
          "platform.backups-keyring",
          BucketPermission.ReadWrite,
          0
        )
      )
    )
    assert(actions.contains(Action.RetryBaseBackup("ankka-keyring", "keyring-db")))
    assertEquals(PlatformBackups.actions(on.copy(objectStore = Some(store))), Vector.empty)
  }
