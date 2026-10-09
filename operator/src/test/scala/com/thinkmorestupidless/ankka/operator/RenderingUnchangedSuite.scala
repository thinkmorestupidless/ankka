package com.thinkmorestupidless.ankka.operator

import com.fasterxml.jackson.databind.SerializationFeature
import com.thinkmorestupidless.ankka.crd.{
  AnkkaSerialization,
  AnkkaService,
  AnkkaServiceSpec,
  EnvEntry
}
import io.fabric8.kubernetes.api.model.{KubernetesResource, ObjectMetaBuilder}

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, Paths}

/**
 * What the operator renders for a service of each hosting that existed before web hosting, pinned
 * as it was rendered before web hosting was added (`features/web-hosting/unchanged.feature`).
 *
 * A Kubernetes object that is applied unchanged changes nothing that is running, so identical
 * objects are what "adding web hosting restarts no service" rests on. A cluster running the
 * operator as it was before cannot be built inside a test, so the comparison is made here, on what
 * is rendered, for every object `render` produces and in its order.
 *
 * The fixtures were written from the operator as it was before web hosting and are never rewritten
 * as a side effect of anything else: only `-Dankka.rendering.pin=true` rewrites them, deliberately,
 * and `ankka.docs.update`, which other suites share, does not. They have been repinned twice since,
 * on purpose: the secret store (feature 023) gives every service a secret key, its Secret and the
 * variable naming it, and the table in its schema — a change meant to reach every service. And
 * again when the operator lost `get` on Secrets: a provisioned database's credential is ensured on
 * every pass, by a `create` an existing Secret refuses, so a ready service's actions gained that
 * one action, and no object changed. And once more, for object storage (feature 034): one action
 * and no object, the removal of a bucket's route if one is owned, rendered for every service so
 * that dropping a bucket leaves no route behind. And for personal data erasure (feature 042): one
 * more table in every service's schema, `ankka_erasures_applied`, so the schema ConfigMap gains its
 * file and no other object changes.
 */
class RenderingUnchangedSuite extends munit.FunSuite:

  private val settings = Settings.default.copy(
    sidecarImage = "ankka-sidecar:9.9.9",
    baseDomain = Some("example.test")
  )

  /**
   * The mapper the operator's client serializes with, so a Scala collection inside an object is
   * written as the API server receives it; keys sorted, so a map's order is not the test's concern.
   */
  private val writer =
    AnkkaSerialization
      .mapper()
      .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
      .writerWithDefaultPrettyPrinter()

  private val fixtures: Path = Paths.get("src/test/resources/unchanged")

  private val pinning: Boolean = sys.props.get("ankka.rendering.pin").contains("true")

  private def resource(spec: AnkkaServiceSpec): AnkkaService =
    val r = new AnkkaService
    r.setMetadata(
      new ObjectMetaBuilder()
        .withNamespace("ankka-shop")
        .withName(spec.serviceName)
        .withUid("00000000-0000-0000-0000-000000000001")
        .build()
    )
    r.setSpec(spec)
    r

  private val base = AnkkaServiceSpec(
    projectId = "shop",
    serviceName = "cart",
    generation = 3L,
    image = "registry.example.test/shop/cart:1.4.2",
    env = List(
      EnvEntry("GREETING", Some("hello"), None, None),
      EnvEntry("ANTHROPIC_API_KEY", None, Some("models"), Some("anthropic"))
    ),
    labels = Map("team" -> "checkout"),
    annotations = Map("example.test/owner" -> "checkout-team"),
    port = Some(9000),
    restarts = 2,
    exposed = true,
    imagePullSecret = Some("ankka-registry")
  )

  private val supplied =
    List(EnvEntry("ANKKA_DB_HOST", Some("db.example.test"), None, None))

  /** One row per row of the feature's outline, each with the plan the reconciler would decide. */
  private val cases: Vector[(String, AnkkaServiceSpec, ProvisioningPlan)] = Vector(
    (
      "embedded",
      base,
      ProvisioningPlan.Ready(recovered = false)
    ),
    (
      "embedded-with-a-database-of-its-own",
      base.copy(env = base.env ++ supplied, provisionDatabase = false),
      ProvisioningPlan.Supplied
    ),
    (
      "process",
      base.copy(hosting = "process"),
      ProvisioningPlan.Ready(recovered = false)
    ),
    (
      "wasm",
      base.copy(hosting = "wasm"),
      ProvisioningPlan.Ready(recovered = false)
    )
  )

  /**
   * Every action, in order, as text: a Kubernetes object as the JSON the API server is sent, and
   * anything else (a name, an owner's uid, a CNPG object of ankka's own model) as its fields.
   */
  private def text(actions: Vector[Action]): String =
    actions
      .map { action =>
        val fields = action.productIterator.map {
          case k: KubernetesResource => writer.writeValueAsString(k) + "\n"
          case other                 => s"$other\n"
        }
        s"# ${action.productPrefix}\n${fields.mkString}"
      }
      .mkString("---\n")

  for (name, spec, plan) <- cases do
    test(
      s"a platform that gains web hosting changes nothing it makes for a service that is not " +
        s"web-hosted: $name"
    ) {
      val rendered = Rendering.render(resource(spec), settings, plan) match
        case Right(actions) => text(actions)
        case Left(problems) => fail(s"$name did not render: ${problems.mkString("; ")}")
      val file = fixtures.resolve(s"$name.json.txt")
      if pinning then
        Files.createDirectories(fixtures)
        Files.writeString(file, rendered, UTF_8)
      else
        assert(
          Files.exists(file),
          s"no fixture $file; it is written once, by -Dankka.rendering.pin=true, from the " +
            "operator as it was before web hosting"
        )
        assertNoDiff(rendered, Files.readString(file, UTF_8), s"$name renders differently")
    }
