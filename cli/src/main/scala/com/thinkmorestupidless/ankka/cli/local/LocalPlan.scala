package com.thinkmorestupidless.ankka.cli.local

import com.thinkmorestupidless.ankka.controlplane.api.{InstanceType, ServiceDescriptor}
import com.thinkmorestupidless.ankka.core.PlatformVariables

import scala.collection.immutable.ListMap

/**
 * What `ankka local up` brings up for a project, decided before anything is touched (feature 066,
 * research R2): the compose document, the databases and topics to make, the order things start in,
 * what each is waited for by, and the console entries to write. Pure: the project, the selection,
 * the kept state and what the machine said are its only inputs, and every problem it finds is
 * answered together.
 */
final case class LocalPlan(
    project: String,
    compose: Compose,
    databases: Vector[LocalPlan.Database],
    topics: Vector[LocalPlan.BrokerTopic],
    infrastructure: Vector[String],
    companions: Vector[String],
    services: Vector[String],
    ready: Map[String, LocalPlan.ReadyCheck],
    entries: Vector[LocalPlan.ConsoleEntry],
    leftOut: Vector[String],
    notices: Vector[String],
    /** Each service container's environment, kept for a suite to assert; never printed. */
    environment: Map[String, ListMap[String, String]]
)

object LocalPlan:

  /** Which of a project's services a command brings up. */
  enum Selection:
    case All
    case Only(names: Vector[String])
    case Without(names: Vector[String])

    /** Every service but `name`, which the developer runs from its source. */
    case ForRun(name: String)

  /** What holds a port on the machine, when something does. */
  enum Holder:
    /** This project's own container, which the command started. */
    case Ours(name: String)

    /** This project's service, run from its source. */
    case FromSource(name: String)
    case Other

  /** What the plan must be told of the host and cannot find out for itself. */
  final case class Machine(
      cliVersion: String,
      /** The default bridge's gateway address, on Linux, where ports are also published on it. */
      gateway: Option[String] = None,
      sidecarImage: Option[String] = None,
      proxyImage: Option[String] = None,
      holder: Int => Option[Holder] = _ => None
  )

  final case class Database(name: String, schemaVersion: Option[String])

  final case class BrokerTopic(name: String, partitions: Int, config: Map[String, String])

  enum ReadyCheck:
    /** The runtime's observability document answers, and its HTTP port when it serves one. */
    case Observability(url: String, servesHttp: Boolean)
    case Port(port: Int)
    case Http(port: Int, path: String)
    case Tcp(port: Int)

  final case class ConsoleEntry(name: String, observabilityAddress: String)

  val Postgres: String = "ankka-postgres"
  val Kafka: String    = "ankka-kafka"

  val PostgresImage: String = "postgres:17-alpine"
  val KafkaImage: String    = "apache/kafka:3.8.0"

  /** The database server's user and password, and the address containers reach it at. */
  val DatabaseUser: String     = "ankka"
  val DatabasePassword: String = "ankka"
  val PostgresPort: Int        = 5432

  /** The broker's listener for containers, and the one for the host inside its container. */
  val KafkaContainersPort: Int = 9094
  val KafkaHostPort: Int       = 9092

  /** Where a runtime container's observability endpoint listens. */
  val ObservabilityPort: Int = 7630

  /** The name containers reach the host by. */
  val HostAddress: String = "host.docker.internal"

  def composeProject(project: String): String = s"ankka-$project"

  /** The plan for `project`, or every problem that stops it. `state` holds every port chosen. */
  def of(
      project: LocalProject,
      selection: Selection,
      state: LocalState,
      machine: Machine
  ): Either[Vector[String], LocalPlan] =
    val all      = project.services
    val selected = selection match
      case Selection.All            => all
      case Selection.Only(names)    => all.filter(names.contains)
      case Selection.Without(names) => all.filterNot(names.contains)
      case Selection.ForRun(name)   => all.filterNot(_ == name)
    val leftOut = all.filterNot(selected.contains)

    val dropped = selected.flatMap { name =>
      val names = literals(project.descriptor(name)).keys
        .filter(LocalVariables.isInstallationOnly)
        .toVector
      Option.when(names.nonEmpty)(s"$name ${names.mkString(", ")}")
    }
    val droppedNotice = Option
      .when(dropped.nonEmpty)(
        "not used on this machine, where the command line supplies the database and the broker: " +
          dropped.mkString("; ")
      )
      .toVector
    val elsewhere = project.map.crossings
      .flatMap(c => Vector(c.from, c.to))
      .filter(_.contains('/'))
      .distinct
      .map(other =>
        s"the context map names \"$other\", a service of another project; it is not run here"
      )

    val environments = selected.map(name => name -> environment(project, name, state)).toMap
    val services     = ListMap.from(selected.map { name =>
      val spec = project.descriptor(name).service
      name -> Compose.Service(
        image = spec.image,
        environment = environments(name),
        ports = published(machine, servicePorts(project.descriptor(name))) ++
          published(machine, Vector(state.observability(name) -> ObservabilityPort)),
        dependsOn = ListMap(
          Postgres -> Compose.Dependency(Compose.Healthy),
          Kafka    -> Compose.Dependency(Compose.Healthy)
        ),
        extraHosts = Vector(Compose.HostGateway),
        memLimit = Some(memory(project.descriptor(name)))
      )
    })
    val compose = Compose(
      composeProject(project.name),
      ListMap(
        Postgres -> postgres(machine, state),
        Kafka    -> kafka(machine, state)
      ) ++ services,
      ListMap("pgdata" -> Compose.Volume(), "kafkadata" -> Compose.Volume())
    )
    val databases = selected.collect {
      case name if !project.descriptor(name).service.hasNoDatabase =>
        Database(name, project.descriptor(name).service.runtime)
    }
    val ready = selected.map { name =>
      name -> ReadyCheck.Observability(
        s"http://127.0.0.1:${state.observability(name)}/observability/service",
        project.descriptor(name).service.http
      )
    }.toMap
    val entries =
      selected.map(name => ConsoleEntry(name, s"http://127.0.0.1:${state.observability(name)}"))
    Right(
      LocalPlan(
        project = project.name,
        compose = compose,
        databases = databases,
        topics = Vector.empty,
        infrastructure = Vector(Postgres, Kafka),
        companions = Vector.empty,
        services = selected,
        ready = ready,
        entries = entries,
        leftOut = leftOut,
        notices = elsewhere ++ droppedNotice,
        environment = environments
      )
    )

  /** A descriptor's variables that are written out, in its order. */
  private def literals(descriptor: ServiceDescriptor): ListMap[String, String] =
    ListMap.from(descriptor.service.env.flatMap(e => e.value.map(e.name -> _)))

  /** The HTTP and gRPC ports a service serves, each published at its own number. */
  private def servicePorts(descriptor: ServiceDescriptor): Vector[(Int, Int)] =
    val spec = descriptor.service
    Option.when(spec.http)(spec.port -> spec.port).toVector ++
      Option.when(spec.grpc)(spec.grpcPort -> spec.grpcPort).toVector

  private def published(machine: Machine, ports: Vector[(Int, Int)]): Vector[String] =
    for
      (host, container) <- ports
      address           <- "127.0.0.1" +: machine.gateway.toVector
    yield Compose.publish(address, host, container)

  private def memory(descriptor: ServiceDescriptor): String =
    val mib = InstanceType
      .byName(descriptor.service.resources.instanceType)
      .getOrElse(InstanceType.Small)
      .memoryMiB
    s"${mib}m"

  /**
   * A service container's environment (research R9): the descriptor's literals, then the command's
   * own, which nobody else may give, then `JAVA_TOOL_OPTIONS` with every other service's address.
   */
  private[local] def environment(
      project: LocalProject,
      name: String,
      state: LocalState
  ): ListMap[String, String] =
    val descriptor = project.descriptor(name)
    val spec       = descriptor.service
    val declared   = literals(descriptor).filterNot((k, _) => LocalVariables.isInstallationOnly(k))
    val database   =
      if spec.hasNoDatabase then ListMap("ANKKA_DATABASE" -> "none")
      else
        ListMap(
          "ANKKA_DB_HOST"     -> Postgres,
          "ANKKA_DB_PORT"     -> PostgresPort.toString,
          "ANKKA_DB_NAME"     -> name,
          "ANKKA_DB_USER"     -> DatabaseUser,
          "ANKKA_DB_PASSWORD" -> DatabasePassword
        )
    val own = ListMap(PlatformVariables.ServiceName -> name) ++
      Option.when(spec.http)(PlatformVariables.HttpPort -> spec.port.toString) ++
      Option.when(spec.grpc)(PlatformVariables.GrpcPort -> spec.grpcPort.toString) ++
      database ++
      ListMap(
        "ANKKA_KAFKA_BOOTSTRAP_SERVERS"        -> s"$Kafka:$KafkaContainersPort",
        "ANKKA_KAFKA_TOPIC_PREFIX"             -> s"${project.name}.",
        PlatformVariables.SecretKey            -> state.secretKeys(name),
        PlatformVariables.LocalEndpointAddress -> s"0.0.0.0:$ObservabilityPort"
      )
    val options = project.services.filterNot(_ == name).flatMap { other =>
      val o = project.descriptor(other).service
      Vector(s"-Dankka.local-services.$other=http://$HostAddress:${o.port}") ++
        Option.when(o.grpc)(s"-Dankka.local-grpc-services.$other=$HostAddress:${o.grpcPort}")
    }
    val tool = (declared.get(JavaToolOptions).toVector ++ options).mkString(" ")
    val base = (declared -- own.keys - JavaToolOptions) ++ own
    if tool.isEmpty then base else base + (JavaToolOptions -> tool)

  val JavaToolOptions: String = "JAVA_TOOL_OPTIONS"

  private def postgres(machine: Machine, state: LocalState): Compose.Service =
    Compose.Service(
      image = PostgresImage,
      environment = ListMap(
        "POSTGRES_USER"     -> DatabaseUser,
        "POSTGRES_PASSWORD" -> DatabasePassword
      ),
      ports = published(machine, state.postgresPort.toVector.map(_ -> PostgresPort)),
      volumes = Vector("pgdata:/var/lib/postgresql/data"),
      healthcheck = Some(Compose.Healthcheck(Vector("CMD-SHELL", s"pg_isready -U $DatabaseUser")))
    )

  private def kafka(machine: Machine, state: LocalState): Compose.Service =
    val hostPort = state.kafkaPort.getOrElse(KafkaHostPort)
    Compose.Service(
      image = KafkaImage,
      environment = ListMap(
        "KAFKA_NODE_ID"       -> "1",
        "KAFKA_PROCESS_ROLES" -> "broker,controller",
        "KAFKA_LISTENERS"     ->
          s"HOST://:$KafkaHostPort,CONTAINERS://:$KafkaContainersPort,CONTROLLER://:9093",
        "KAFKA_ADVERTISED_LISTENERS" ->
          s"HOST://localhost:$hostPort,CONTAINERS://$Kafka:$KafkaContainersPort",
        "KAFKA_LISTENER_SECURITY_PROTOCOL_MAP" ->
          "CONTROLLER:PLAINTEXT,HOST:PLAINTEXT,CONTAINERS:PLAINTEXT",
        "KAFKA_CONTROLLER_LISTENER_NAMES"                -> "CONTROLLER",
        "KAFKA_INTER_BROKER_LISTENER_NAME"               -> "CONTAINERS",
        "KAFKA_CONTROLLER_QUORUM_VOTERS"                 -> "1@localhost:9093",
        "KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR"         -> "1",
        "KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR" -> "1",
        "KAFKA_TRANSACTION_STATE_LOG_MIN_ISR"            -> "1",
        "KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS"         -> "0",
        "KAFKA_AUTO_CREATE_TOPICS_ENABLE"                -> "false",
        "KAFKA_HEAP_OPTS"                                -> "-Xmx512m -Xms512m",
        "KAFKA_LOG_DIRS"                                 -> "/var/lib/kafka/data"
      ),
      ports = published(machine, Vector(hostPort -> KafkaHostPort)),
      volumes = Vector("kafkadata:/var/lib/kafka/data"),
      healthcheck = Some(
        Compose.Healthcheck(
          Vector(
            "CMD-SHELL",
            s"/opt/kafka/bin/kafka-broker-api-versions.sh --bootstrap-server localhost:$KafkaContainersPort > /dev/null 2>&1"
          ),
          startPeriod = Some("10s")
        )
      )
    )
