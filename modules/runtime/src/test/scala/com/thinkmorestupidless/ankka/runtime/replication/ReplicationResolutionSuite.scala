package com.thinkmorestupidless.ankka.runtime.replication

import com.thinkmorestupidless.ankka.core.{
  ComponentDescriptor,
  ComponentId,
  ComponentKind,
  ComponentRegistry,
  EntityId
}
import com.thinkmorestupidless.ankka.runtime.StartRefusal
import com.typesafe.config.{Config, ConfigFactory}
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.{Done, NotUsed}

import java.nio.file.Files
import scala.concurrent.Future

/**
 * Which replication transport a service gets (feature 051): none for a service that names no other
 * installation, the one a provider on the classpath supplies, else the runtime's own when every
 * installation names its database, else a refusal to start that names what is missing.
 */
class ReplicationResolutionSuite extends munit.FunSuite:

  private val system: ActorSystem[Nothing] = ActorSystem(
    Behaviors.empty,
    "replication-resolution",
    ConfigFactory
      .parseString("""
        pekko.actor.provider = local
        pekko.remote.artery.canonical.port = 0
      """)
      .withFallback(ConfigFactory.load())
  )

  private val termination = Files.createTempFile("termination", ".log")

  override def beforeAll(): Unit =
    System.setProperty(StartRefusal.PathProperty, termination.toString): Unit

  override def afterAll(): Unit =
    System.clearProperty(StartRefusal.PathProperty)
    Files.deleteIfExists(termination)
    system.terminate()

  private def descriptor(id: String, of: ComponentKind, isReplicated: Boolean) =
    new ComponentDescriptor:
      def componentId: ComponentId     = ComponentId(id)
      def kind: ComponentKind          = of
      override def replicated: Boolean = isReplicated

  private val vehicle = descriptor("vehicle", ComponentKind.EventSourcedEntity, true)

  private def config(text: String): Config =
    ConfigFactory.parseString(text).withFallback(system.settings.config).resolve()

  private def resolve(text: String, supplied: Option[ReplicationTransportFactory] = None) =
    ReplicationRuntime.resolve(
      system,
      config(text),
      Seq(vehicle),
      supplied,
      LoopbackReplication.none
    )

  private val twoWithDatabases = """
    ankka.replication.installation = leipzig
    ankka.replication.installations {
      vienna.db { host = "127.0.0.1", port = 1, name = "v", user = "u", password = "p" }
    }
  """

  test("a service that names no installations has no transport, and starts") {
    assertEquals(resolve(""), None)
  }

  test("a lone installation is not replicating") {
    assertEquals(resolve("ankka.replication.installation = leipzig"), None)
  }

  test("installations that each name a database take the runtime's own transport") {
    val replication = resolve(twoWithDatabases).getOrElse(fail("no replication"))
    try
      assert(replication.transport.isInstanceOf[DatabaseReplicationTransport])
      assertEquals(replication.self, "leipzig")
      assertEquals(replication.components, Set(ComponentId("vehicle")))
    finally replication.transport.stop()
  }

  test("an installation with no database and no provider refuses to start, naming both") {
    val refused = intercept[IllegalStateException](resolve("""
      ankka.replication.installation = leipzig
      ankka.replication.installations {
        vienna.db { host = "127.0.0.1", port = 1, name = "v", user = "u", password = "p" }
        bratislava {}
      }
    """))
    val message = refused.getMessage
    assert(message.contains("replication transport"), message)
    assert(message.contains("vienna") && message.contains("bratislava"), message)
    assert(message.contains("ANKKA_INSTALLATION_<NAME>_DB_HOST"), message)
    assert(message.contains("ANKKA_INSTALLATIONS"), "the hint names both forms: " + message)
    // The refusal is written where the platform reads why a container stopped.
    assert(Files.readString(termination).contains("replication transport"))
  }

  test("a provider on the classpath wins over the databases named") {
    val replication =
      resolve(twoWithDatabases + "\nankka.test.replication-provider = on").getOrElse(fail("none"))
    assertEquals(replication.transport.name, TestProvider.Name)
  }

  test("a provider is given the components the service declares replicated, and no others") {
    val plain = descriptor("plain", ComponentKind.EventSourcedEntity, false)
    val replication = ReplicationRuntime
      .resolve(
        system,
        config(twoWithDatabases + "\nankka.test.replication-provider = on"),
        Seq(vehicle, plain),
        None,
        LoopbackReplication.none
      )
      .getOrElse(fail("none"))
    assertEquals(replication.transport.name, TestProvider.Name)
    assertEquals(TestProvider.seen.get(), Set(ComponentId("vehicle")))
  }

  test("a provider that is not configured leaves the choice to the runtime") {
    val replication = resolve(twoWithDatabases).getOrElse(fail("none"))
    try assertNotEquals(replication.transport.name, TestProvider.Name)
    finally replication.transport.stop()
  }

  test("a transport the test kit supplies wins over everything") {
    val supplied: ReplicationTransportFactory = (_, _, _) => TestProvider.Nobody("supplied")
    val replication = resolve(
      twoWithDatabases + "\nankka.test.replication-provider = on",
      Some(supplied)
    ).getOrElse(fail("none"))
    assertEquals(replication.transport.name, "supplied")
  }

  test("a component other than an event sourced entity declared replicated is refused, named") {
    val refused = ComponentRegistry.from(
      Seq(
        vehicle,
        descriptor("profile", ComponentKind.KeyValueEntity, true),
        descriptor("checkout", ComponentKind.Workflow, true),
        descriptor("plain", ComponentKind.KeyValueEntity, false)
      )
    )
    assertEquals(
      refused.left.toOption.getOrElse(Vector.empty).sorted,
      Vector(
        "KeyValueEntity 'profile' is declared replicated; only an event sourced entity may be",
        "Workflow 'checkout' is declared replicated; only an event sourced entity may be"
      ).sorted
    )
  }

/** Named in `META-INF/services`: supplies a transport only when the test configuration asks. */
final class TestProvider extends ReplicationTransportProvider:
  def transport(
      system: ActorSystem[?],
      config: Config,
      settings: ReplicationSettings,
      replicated: Set[ComponentId]
  ): Option[ReplicationTransport] =
    Option.when(
      config.hasPath("ankka.test.replication-provider") &&
        config.getBoolean("ankka.test.replication-provider")
    ) {
      TestProvider.seen.set(replicated)
      TestProvider.Nobody(TestProvider.Name)
    }

object TestProvider:
  val Name = "the test provider's transport"

  /** The replicated components the provider was last given. */
  val seen: java.util.concurrent.atomic.AtomicReference[Set[ComponentId]] =
    java.util.concurrent.atomic.AtomicReference(Set.empty)

  /** A transport that reaches nobody. */
  final case class Nobody(name: String) extends ReplicationTransport:
    def records(
        installation: String,
        componentId: ComponentId,
        entityId: EntityId,
        from: Long
    ): Source[Replicated, NotUsed] = Source.empty
    def forward(
        installation: String,
        componentId: ComponentId,
        entityId: EntityId,
        call: ForwardedCall
    ): Future[ForwardedReply] = Future.failed(IllegalStateException("nobody"))
    def report(
        installation: String,
        componentId: ComponentId,
        entityId: EntityId,
        position: Long
    ): Future[Done] = Future.successful(Done)
    def locate(componentId: ComponentId, entityId: EntityId): Future[Option[Located]] =
      Future.successful(None)
    def advanced: Source[Advanced, NotUsed]      = Source.empty
    def reachable(installation: String): Boolean = false
