package com.thinkmorestupidless.ankka.cli.local

import com.thinkmorestupidless.ankka.controlplane.api.{InstanceType, ServiceDescriptor, ServiceSpec}
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
    environment: Map[String, ListMap[String, String]],
    /** Each companion started, and the selected services that wait for it. */
    neededBy: Map[String, Vector[String]] = Map.empty,
    /** The compose services each selected service is, in the order they are brought up. */
    containers: Map[String, Vector[String]] = Map.empty
):
  /** The compose services `names` are. */
  def containersOf(names: Vector[String]): Vector[String] =
    names.flatMap(n => containers.getOrElse(n, Vector(n)))

object LocalPlan:

  /** Which of a project's services a command brings up. */
  enum Selection:
    case All

    /** The services named, and what they need; `without` is only ever a usage mistake beside it. */
    case Only(names: Vector[String], without: Vector[String] = Vector.empty)
    case Without(names: Vector[String])

    /** What `name` needs to run from its source on the host, and no service container. */
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

  /** A database to make; `schemaVersion` absent is the CLI's own; a companion's has no schema. */
  final case class Database(name: String, schemaVersion: Option[String], schema: Boolean = true)

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
      case Selection.All                  => all
      case Selection.Only(names, without) =>
        all.filter(n => names.contains(n) && !without.contains(n))
      case Selection.Without(names) => all.filterNot(names.contains)
      case Selection.ForRun(name)   => all.filter(_ == name)
    val leftOut = all.filterNot(selected.contains)
    val refused = problems(project, selection, selected, machine)
    if refused.nonEmpty then Left(refused)
    else
      selection match
        case Selection.ForRun(name) =>
          // What the service needs and nothing else (research R12): its database, the topics and
          // its companions. What of the service is the platform's runs as a container still: a
          // process's sidecar, a module's runtime; the developer's own code runs on the host.
          build(project, selected, Vector.empty, state, machine).map(plan =>
            forRun(project, name, state, machine, plan)
          )
        case _ => build(project, selected, leftOut, state, machine)

  /** The static problems (research R14), every one found. */
  private def problems(
      project: LocalProject,
      selection: Selection,
      selected: Vector[String],
      machine: Machine
  ): Vector[String] =
    val all   = project.services
    val named = selection match
      case Selection.All                  => Vector.empty
      case Selection.Only(names, without) => names ++ without
      case Selection.Without(names)       => names
      case Selection.ForRun(name)         => Vector(name)
    val unknown = named.distinct
      .filterNot(all.contains)
      .map(n => s"\"$n\" is not a service of ${project.name}")
    val both = selection match
      case Selection.Only(names, without) =>
        names.distinct.filter(without.contains).map(n => s"\"$n\" is both named and left out")
      case _ => Vector.empty
    val ports = all.flatMap(n => servicePorts(project.descriptor(n)).map((p, _) => p -> n))
    val twice = ports
      .groupBy(_._1)
      .toVector
      .sortBy(_._1)
      .flatMap { (port, holders) =>
        val names = holders.map(_._2).distinct
        names.zip(names.drop(1)).headOption.map((a, b) => s"\"$a\" and \"$b\" both name port $port")
      }
    val taken = selected.flatMap { name =>
      servicePorts(project.descriptor(name)).map(_._1).distinct.flatMap { port =>
        machine.holder(port) match
          case None | Some(Holder.Ours(_)) => None
          case Some(Holder.FromSource(_))  =>
            Some(
              s"\"$name\" names port $port, which is in use on this machine; it is running from its source"
            )
          case Some(Holder.Other) =>
            Some(s"\"$name\" names port $port, which is in use on this machine")
      }
    }
    val reserved = all
      .filter(Reserved.contains)
      .map(n =>
        s"\"$n\" is the name of what the command line runs for every project; name the service something else"
      )
    // Each declaration held to the rules the control plane holds it to, as `topics apply` sends it.
    val declared = project.topics.topics.flatMap(t =>
      TopicDeclarationRequests.of(project.root, t).left.getOrElse(Vector.empty)
    )
    project.checked ++ unknown ++ both ++ twice ++ taken ++ reserved ++ valueProblems(project) ++
      declared ++ Companions.problems(project, machine)

  /** What is wrong with the local values (contracts/project-files.md). */
  private def valueProblems(project: LocalProject): Vector[String] =
    val all     = project.services
    val local   = project.local
    val nowhere = (local.values.keys.filterNot(_ == LocalFile.EveryService) ++ local.run.keys)
      .filterNot(all.contains)
      .toVector
      .distinct
      .sorted
      .map(n => s"local.json gives values to \"$n\", which is not a service of ${project.name}")
    val owned = all.flatMap { name =>
      local.valuesFor(name).keys.toVector.sorted.collect {
        case v if LocalVariables.isCommandOwned(v) =>
          s"local.json gives \"$name\" $v, which the command line sets itself"
      }
    }
    val secrets = all.flatMap { name =>
      val values = local.valuesFor(name)
      project.descriptor(name).service.env.collect {
        case e if e.secretKeyRef.isDefined && !values.contains(e.name) =>
          s"\"$name\" reads ${e.name} from a secret and local.json gives it no value"
      }
    }
    nowhere ++ owned ++ secrets.distinct

  /** What a plan of `project` needs chosen: every service, and each companion's unnamed ports. */
  def wanted(project: LocalProject): LocalState.Wanted =
    LocalState.Wanted(
      project.services,
      // A process service run from its source listens on a port of the host's, and its sidecar
      // calls back on another; both are chosen once, as every other port is.
      processServices = project.services
        .filter(n => project.descriptor(n).service.isProcessHosted)
        .flatMap(n => Vector(n, sidecarKey(n))),
      companionPorts = Companions.unnamedPorts(project)
    )

  /** The key of a process service's sidecar's host port in `state.json`'s process ports. */
  def sidecarKey(name: String): String = s"$name-sidecar"

  /** Where `cargo module` writes a Rust service's module, as the Rust template's build says. */
  def builtModule(project: LocalProject, name: String): java.nio.file.Path =
    val dir   = project.root.resolve("services").resolve(name)
    val crate = scala.util
      .Try(
        java.nio.file.Files.readAllLines(dir.resolve("Cargo.toml")).toArray.toVector.map(_.toString)
      )
      .toOption
      .flatMap(_.collectFirst { case s"name = \"$n\"" => n })
      .getOrElse(name)
    dir.resolve(s"target/wasm32-unknown-unknown/release/${crate.replace('-', '_')}.wasm")

  /** The names the command line gives its own containers. */
  val Reserved: Set[String] = Set(Postgres, Kafka)

  private def build(
      project: LocalProject,
      selected: Vector[String],
      leftOut: Vector[String],
      state: LocalState,
      machine: Machine
  ): Either[Vector[String], LocalPlan] =

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

    // Every service of the project is in the document, and only the selected are brought up: compose
    // removes a service the document does not name as an orphan, and one left out is not that.
    val environments =
      project.services.map(name => name -> environment(project, name, state)).toMap
    val services = ListMap.from(project.services.flatMap { name =>
      containers(project, name, state, machine, environments(name))
    })
    // Every companion is in the document too, for the reason every service is.
    val companionServices = ListMap.from(project.local.companions.distinctBy(_.name).map { c =>
      c.name -> Companions.service(project, c, state, published(machine, _))
    })
    val needed  = Companions.needed(project, selected)
    val compose = Compose(
      composeProject(project.name),
      ListMap(
        Postgres -> postgres(machine, state),
        Kafka    -> kafka(machine, state)
      ) ++ companionServices ++ services,
      ListMap("pgdata" -> Compose.Volume(), "kafkadata" -> Compose.Volume()) ++
        ListMap.from(
          project.services
            .filter(n => project.descriptor(n).service.hosting == ServiceSpec.Wasm)
            .map(n => moduleVolume(n) -> Compose.Volume())
        )
    )
    val databases = selected.collect {
      case name if hasDatabase(project.descriptor(name)) =>
        Database(name, project.descriptor(name).service.runtime)
    } ++ needed.filter(_.database).map(c => Database(c.name, None, schema = false))
    val ready = selected.map { name =>
      val spec = project.descriptor(name).service
      // A web-hosted service's proxy has no runtime to ask: it is ready when its port answers.
      if spec.isWebHosted then name -> ReadyCheck.Port(spec.port)
      else
        name -> ReadyCheck.Observability(
          s"http://127.0.0.1:${state.observability(name)}/observability/service",
          spec.http
        )
    }.toMap ++ needed.flatMap(c => Companions.ready(c, state).map(c.name -> _))
    val entries = selected
      .filterNot(name => project.descriptor(name).service.isWebHosted)
      .map(name => ConsoleEntry(name, s"http://127.0.0.1:${state.observability(name)}"))
    Right(
      LocalPlan(
        project = project.name,
        compose = compose,
        databases = databases,
        topics = BrokerTopics.wanted(project.name, project.topics),
        infrastructure = Vector(Postgres, Kafka),
        companions = needed.map(_.name),
        services = selected,
        ready = ready,
        entries = entries,
        leftOut = leftOut,
        notices = elsewhere ++ droppedNotice,
        environment = environments,
        containers = selected.map(n => n -> containerNames(project.descriptor(n))).toMap,
        neededBy = needed.map(c => c.name -> selected.filter(c.needed)).toMap
      )
    )

  private def forRun(
      project: LocalProject,
      name: String,
      state: LocalState,
      machine: Machine,
      plan: LocalPlan
  ): LocalPlan =
    val spec      = project.descriptor(name).service
    val companion = plan.ready.filter((n, _) => plan.companions.contains(n))
    val none      = plan.copy(services = Vector.empty, entries = Vector.empty, ready = companion)
    spec.hosting match
      case ServiceSpec.Process =>
        // The sidecar dials the process on the host and is called back on a port of its own. It is
        // not waited for: it is ready only once the process it waits for has started.
        val sidecar = plan.compose.services(name)
        val process = state.processPorts(name)
        val back    = state.processPorts(sidecarKey(name))
        val running = sidecar.copy(
          environment = sidecar.environment ++ ListMap(
            "ANKKA_PROCESS_ADDRESS" -> s"$HostAddress:$process",
            "ANKKA_SIDECAR_BIND"    -> "0.0.0.0",
            "ANKKA_SIDECAR_PORT"    -> SidecarPort.toString
          ),
          ports = sidecar.ports ++ published(machine, Vector(back -> SidecarPort))
        )
        val key = sidecarKey(name)
        none.copy(
          compose = plan.compose.copy(services = plan.compose.services + (key -> running)),
          services = Vector(name),
          containers = Map(name -> Vector(key)),
          entries = plan.entries.filter(_.name == name)
        )
      case ServiceSpec.Wasm =>
        // The module `cargo module` built, bound read-only where the runtime loads one from.
        val runtime = plan.compose.services(name)
        val loaded  = runtime.copy(
          volumes = Vector(s"${builtModule(project, name).toAbsolutePath}:$ModuleFile:ro"),
          dependsOn = runtime.dependsOn - s"$name-module"
        )
        none.copy(
          compose = plan.compose.copy(services = plan.compose.services + (name -> loaded)),
          services = Vector(name),
          containers = Map(name -> Vector(name)),
          ready = companion ++ plan.ready.get(name).map(name -> _),
          entries = plan.entries.filter(_.name == name)
        )
      case _ => none

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
      state: LocalState,
      onHost: Boolean = false
  ): ListMap[String, String] =
    val descriptor = project.descriptor(name)
    val spec       = descriptor.service
    // The descriptor's literals, then the local values over them (research R9). Where the service
    // runs decides every address it is given: a container reaches the database server and the
    // broker by their names, a process on the host by what the command published.
    val declared = declaredValues(project, name, state, onHost)
    val database =
      if !hasDatabase(descriptor) then ListMap("ANKKA_DATABASE" -> "none")
      else
        ListMap(
          "ANKKA_DB_HOST" -> (if onHost then "localhost" else Postgres),
          "ANKKA_DB_PORT" ->
            (if onHost then state.postgresPort.getOrElse(PostgresPort) else PostgresPort).toString,
          "ANKKA_DB_NAME"     -> name,
          "ANKKA_DB_USER"     -> DatabaseUser,
          "ANKKA_DB_PASSWORD" -> DatabasePassword
        )
    val own = ListMap(PlatformVariables.ServiceName -> name) ++
      Option.when(spec.http)(PlatformVariables.HttpPort -> spec.port.toString) ++
      Option.when(spec.grpc)(PlatformVariables.GrpcPort -> spec.grpcPort.toString) ++
      database ++
      ListMap(
        "ANKKA_KAFKA_BOOTSTRAP_SERVERS" ->
          (if onHost then s"localhost:${state.kafkaPort.getOrElse(KafkaHostPort)}"
           else s"$Kafka:$KafkaContainersPort"),
        "ANKKA_KAFKA_TOPIC_PREFIX"  -> s"${project.name}.",
        PlatformVariables.SecretKey -> state.secretKeys(name)
      ) ++
      // On the host the runtime chooses its own console port and announces itself, as it always has.
      Option.when(!onHost)(PlatformVariables.LocalEndpointAddress -> s"0.0.0.0:$ObservabilityPort")
    // On the host the services the command started are found through the console's entries.
    val options = if onHost then Vector.empty
    else
      project.services.filterNot(_ == name).flatMap { other =>
        val o = project.descriptor(other).service
        Vector(s"-Dankka.local-services.$other=http://$HostAddress:${o.port}") ++
          Option.when(o.grpc)(s"-Dankka.local-grpc-services.$other=$HostAddress:${o.grpcPort}")
      }
    val tool = (declared.get(JavaToolOptions).toVector ++ options).mkString(" ")
    val base = (declared -- own.keys - JavaToolOptions) ++ own
    if tool.isEmpty then base else base + (JavaToolOptions -> tool)

  val JavaToolOptions: String = "JAVA_TOOL_OPTIONS"

  /** A web-hosted service has no database on an installation, and has none here either. */
  private def hasDatabase(descriptor: ServiceDescriptor): Boolean =
    !descriptor.service.hasNoDatabase && !descriptor.service.isWebHosted

  /** The compose services a service is (contracts/local-project.md), in the order they start. */
  private def containerNames(descriptor: ServiceDescriptor): Vector[String] =
    val name = descriptor.name
    descriptor.service.hosting match
      case ServiceSpec.Process | ServiceSpec.Web => Vector(name, s"$name-app")
      case ServiceSpec.Wasm                      => Vector(s"$name-module", name)
      case _                                     => Vector(name)

  private def moduleVolume(name: String): String = s"$name-module"

  /** The pod's loopback ports between a sidecar and its process, the operator's. */
  val ProcessPort: Int = 9010
  val SidecarPort: Int = 9011

  /** Where a module is written by its image and loaded from by the runtime, the operator's. */
  val ModuleMount: String = "/ankka/module"
  val ModuleFile: String  = s"$ModuleMount/service.wasm"

  /** The proxy's local listener for its program's calls, the operator's calling port. */
  val CallingPort: Int = 7630

  /** Each compose service of `name`, as the operator composes its pod (research R10). */
  private def containers(
      project: LocalProject,
      name: String,
      state: LocalState,
      machine: Machine,
      environment: ListMap[String, String]
  ): Vector[(String, Compose.Service)] =
    val descriptor = project.descriptor(name)
    val spec       = descriptor.service
    val runtime    = Compose.Service(
      image = spec.image,
      environment = environment,
      ports = published(machine, servicePorts(descriptor)) ++
        published(machine, Vector(state.observability(name) -> ObservabilityPort)),
      dependsOn = ListMap(
        Postgres -> Compose.Dependency(Compose.Healthy),
        Kafka    -> Compose.Dependency(Compose.Healthy)
      ),
      extraHosts = Vector(Compose.HostGateway),
      memLimit = Some(memory(descriptor))
    )
    val sidecar =
      Schemas.sidecarImage(spec.runtime.getOrElse(machine.cliVersion), machine.sidecarImage)
    spec.hosting match
      case ServiceSpec.Process =>
        // The descriptor's own variables are split as the operator splits them: the platform's
        // program's to the sidecar, the broker's to both, everything else to the process.
        val declared               = declaredValues(project, name, state, onHost = false)
        def processOnly(k: String) = declared.contains(k) && !PlatformVariables.runtimeOnly(k)
        val forSidecar             =
          environment.filterNot((k, _) => processOnly(k) && !PlatformVariables.shared(k))
        val forProcess = environment.filter((k, _) => PlatformVariables.shared(k)) ++
          declared.filter((k, _) => !PlatformVariables.runtimeOnly(k))
        Vector(
          name -> runtime.copy(
            image = sidecar,
            environment = forSidecar ++ ListMap(
              "ANKKA_PROCESS_ADDRESS" -> s"127.0.0.1:$ProcessPort",
              "ANKKA_SIDECAR_PORT"    -> SidecarPort.toString
            )
          ),
          s"$name-app" -> Compose.Service(
            image = spec.image,
            environment = forProcess ++ ListMap(
              "ANKKA_PROCESS_PORT"    -> ProcessPort.toString,
              "ANKKA_SIDECAR_ADDRESS" -> s"127.0.0.1:$SidecarPort"
            ),
            networkMode = Some(s"service:$name"),
            memLimit = Some(
              spec.resources.process
                .map(p => composeMemory(p.memory))
                .getOrElse(DefaultProcessMemory)
            )
          )
        )
      case ServiceSpec.Wasm =>
        // The module's image has one job, to write the module and exit; the runtime starts after.
        Vector(
          s"$name-module" -> Compose.Service(
            image = spec.image,
            volumes = Vector(s"${moduleVolume(name)}:$ModuleMount"),
            restart = Some("no")
          ),
          name -> runtime.copy(
            image = sidecar,
            environment = environment + ("ANKKA_WASM_MODULE" -> ModuleFile),
            volumes = Vector(s"${moduleVolume(name)}:$ModuleMount:ro"),
            dependsOn = runtime.dependsOn +
              (s"$name-module" -> Compose.Dependency(Compose.CompletedSuccessfully))
          )
        )
      case ServiceSpec.Web =>
        val processPort = spec.processPort.getOrElse(ServiceSpec.DefaultProcessPort)
        val others      = project.services.filterNot(_ == name).flatMap { other =>
          val o = project.descriptor(other).service
          Option.when(o.http)(s"$other=http://$HostAddress:${o.port}")
        }
        Vector(
          name -> Compose.Service(
            image = proxyImage(machine),
            environment = ListMap(
              "ANKKA_PROXY_LOCAL"        -> "on",
              "ANKKA_PROXY_SERVICE"      -> name,
              "ANKKA_PROXY_PORT"         -> spec.port.toString,
              "ANKKA_PROXY_PROCESS_PORT" -> processPort.toString,
              "ANKKA_PROXY_MOUNTS" -> spec.mounts.map(m => s"${m.path}=${m.service}").mkString(","),
              "ANKKA_PROXY_LOCAL_SERVICES" -> others.mkString(",")
            ),
            ports = published(machine, Vector(spec.port -> spec.port)),
            extraHosts = Vector(Compose.HostGateway),
            memLimit = Some(ProxyMemory)
          ),
          s"$name-app" -> Compose.Service(
            image = spec.image,
            environment = declaredValues(project, name, state, onHost = false) ++ ListMap(
              PlatformVariables.WebPort     -> processPort.toString,
              PlatformVariables.ServicesUrl -> s"http://127.0.0.1:$CallingPort"
            ),
            networkMode = Some(s"service:$name")
          )
        )
      case _ => Vector(name -> runtime)

  private val DefaultProcessMemory = "128m"

  /** A Kubernetes quantity as compose writes one: `256Mi` is `256m`, `1Gi` is `1g`. */
  private[local] def composeMemory(quantity: String): String =
    quantity match
      case s"${n}Mi" => s"${n}m"
      case s"${n}Gi" => s"${n}g"
      case s"${n}Ki" => s"${n}k"
      case s"${n}M"  =>
        s"${(BigDecimal(n) * 1000000 / 1048576).setScale(0, BigDecimal.RoundingMode.CEILING)}m"
      case s"${n}G" =>
        s"${(BigDecimal(n) * 1000 * 1000000 / 1048576).setScale(0, BigDecimal.RoundingMode.CEILING)}m"
      case other => other
  private val ProxyMemory = "192m"

  /** The proxy's image: named by the developer, else the CLI's release, else a local build's. */
  def proxyImage(machine: Machine): String =
    machine.proxyImage.getOrElse(
      if com.thinkmorestupidless.ankka.cli.Release.parse(machine.cliVersion).isEmpty then
        "ankka-proxy:latest"
      else s"ghcr.io/thinkmorestupidless/ankka-proxy:${machine.cliVersion}"
    )

  /** The descriptor's literals with the local values over them, the command's own taken out. */
  private[local] def declaredValues(
      project: LocalProject,
      name: String,
      state: LocalState,
      onHost: Boolean
  ): ListMap[String, String] =
    val reach = if onHost then "localhost" else HostAddress
    val local = project.local
      .valuesFor(name)
      .map((k, v) => k -> LocalFile.fill(v, Companions.forService(project, state, reach)))
    (literals(project.descriptor(name)) ++ local)
      .filterNot((k, _) => LocalVariables.isInstallationOnly(k))

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
