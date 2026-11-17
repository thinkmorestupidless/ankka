package com.thinkmorestupidless.ankka.cli.local

import com.github.plokhotnyuk.jsoniter_scala.core.{JsonReaderException, readFromString}
import com.thinkmorestupidless.ankka.cli.Release

import java.io.PrintStream
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import scala.concurrent.duration.*

/**
 * `ankka local up` (feature 066, research R15): the first-tier checks, the compose document, the
 * database server and the broker, each database and its schema, the second-tier checks, the
 * services, a wait for each, and the table. Every problem of a tier is printed together; a problem
 * of the first tier starts nothing, one of the second starts no service.
 */
object LocalUp:

  /** How a check of readiness is asked of the host. A suite answers it without a network. */
  type Probe = LocalPlan.ReadyCheck => Boolean

  /**
   * What runs once everything a plan brings up is ready, in place of the summary: `local run`'s.
   */
  type Finish = (LocalProject, LocalPlan, LocalState) => Int

  val DefaultTimeout: FiniteDuration = 5.minutes
  val LogLines: Int                  = 40

  def composeFile(root: Path): Path = root.resolve(LocalState.Directory).resolve("compose.json")

  def run(
      dir: Path,
      selection: LocalPlan.Selection,
      timeout: FiniteDuration,
      engine: Engine,
      out: PrintStream,
      err: PrintStream,
      probe: Probe = Probes.host,
      isFree: Int => Boolean = LocalState.isFree,
      cliVersion: String = com.thinkmorestupidless.ankka.core.BuildInfo.version,
      finish: Option[Finish] = None
  ): Int =
    engine.available match
      case Left(refusal) =>
        err.println(refusal)
        1
      case Right(()) =>
        LocalProject.read(dir) match
          case Left(problems) =>
            problems.foreach(err.println)
            1
          case Right(project) =>
            up(project, selection, timeout, engine, out, err, probe, isFree, cliVersion, finish)

  private def up(
      project: LocalProject,
      selection: LocalPlan.Selection,
      timeout: FiniteDuration,
      engine: Engine,
      out: PrintStream,
      err: PrintStream,
      probe: Probe,
      isFree: Int => Boolean,
      cliVersion: String,
      finish: Option[Finish]
  ): Int =
    val root      = project.root
    val composeId = LocalPlan.composeProject(project.name)
    val before    = engine.ps(composeId)
    val reserved  = project.services.flatMap { n =>
      val s = project.descriptor(n).service
      Option.when(s.http)(s.port).toVector ++ Option.when(s.grpc)(s.grpcPort)
    }.toSet
    val kept = LocalState.readChecked(root) match
      case Right(state)  => state
      case Left(refusal) =>
        err.println(refusal)
        return 1
    val state = LocalState.completed(
      kept,
      LocalPlan.wanted(project),
      LocalState.Choices(
        // A port this project's own containers hold is theirs to keep.
        isFree = p => kept.ports.contains(p) || isFree(p),
        freePort = () => LocalState.freePort(),
        newKey = () => LocalState.randomKey(),
        reserved = reserved
      )
    )
    val fromSource = ConsoleEntries.sourceRun(
      project.services,
      address => probe(LocalPlan.ReadyCheck.Observability(s"$address/observability/service", false))
    )
    val machine =
      Machines.of(project, before, engine, isFree, cliVersion, fromSource, kept.processPorts)
    // The ports the command chose once are checked as the descriptors' are: held by anything but
    // what they were chosen for, the run would fail part-way at Docker's refusal.
    val running = before.filter(_.running).map(_.service).toSet
    val chosen  = kept.postgresPort.map(_ -> LocalPlan.Postgres).toVector ++
      kept.kafkaPort.map(_ -> LocalPlan.Kafka) ++
      kept.observability.toVector
        // A web-hosted service's observability port is chosen and never published.
        .filter((n, _) =>
          project.services.contains(n) && !fromSource(n) &&
            !project.descriptor(n).service.isWebHosted
        )
        .map((n, p) => p -> n) ++
      kept.companionPorts.toVector.flatMap((c, ports) => ports.values.map(_ -> c))
    val stateTaken = chosen
      .filterNot((_, owner) => running(owner) || running(LocalPlan.sidecarKey(owner)))
      .filterNot((port, _) => isFree(port))
      .sortBy(_._1)
      .map((port, owner) => s"\"$owner\" names port $port, which is in use on this machine")
    LocalPlan.of(project, selection, state, machine) match
      case Left(problems) =>
        (problems ++ stateTaken).foreach(err.println)
        1
      case Right(_) if stateTaken.nonEmpty =>
        stateTaken.foreach(err.println)
        1
      case Right(plan) =>
        val images = imageProblems(plan, engine)
        if images.nonEmpty then
          images.foreach(err.println)
          1
        else
          plan.notices.foreach(err.println)
          val previous = readCompose(root)
          LocalState.write(root, state)
          val file = composeFile(root)
          Files.createDirectories(file.getParent)
          Files.writeString(file, Compose.json(plan.compose), UTF_8)
          start(project, plan, before, file, timeout, engine, err, probe, machine)
            .fold(
              identity,
              _ =>
                finish.fold {
                  out.println(s"${project.name} is up")
                  Table.print(project, plan, previous, before, engine.ps(composeId), out)
                  plan.leftOut.foreach(name =>
                    out.println(
                      s"left out: $name — run it from its source with: ankka local run $name"
                    )
                  )
                  0
                }(_(project, plan, state))
            )

  private def start(
      project: LocalProject,
      plan: LocalPlan,
      before: Vector[Engine.Container],
      file: Path,
      timeout: FiniteDuration,
      engine: Engine,
      err: PrintStream,
      probe: Probe,
      machine: LocalPlan.Machine
  ): Either[Int, Unit] =
    val composeId = LocalPlan.composeProject(project.name)
    val infra     = engine.up(file, composeId, plan.infrastructure, removeOrphans = false)
    if !infra.ok then
      err.println(
        s"the database server and the broker of ${project.name} did not start: ${infra.err.trim}"
      )
      return Left(1)
    val unhealthy = waitHealthy(composeId, plan.infrastructure, timeout, engine)
    if unhealthy.nonEmpty then
      unhealthy.foreach(name => notReady(composeId, name, timeout, engine, err))
      return Left(1)
    val second =
      plan.databases.flatMap(d => Databases.ensure(composeId, d, machine, engine, err)) ++
        Topics.ensure(composeId, plan.topics, engine)
    if second.nonEmpty then
      second.foreach(err.println)
      err.println(s"the database server and the broker of ${project.name} are left running")
      return Left(1)
    // A service left out that is running from its image is stopped, so it can be run from its
    // source in its place (`local run` refuses while its container runs); its data is kept. So is
    // the sidecar an ended run of a service brought up now left behind: it holds the service's ports.
    val leaving = (plan.containersOf(plan.leftOut) ++
      plan.services.filter(machine.endedRuns).map(LocalPlan.sidecarKey))
      .filter(c => before.exists(r => r.service == c && r.running))
    if leaving.nonEmpty then
      val stopped = engine.stop(composeId, leaving)
      if !stopped.ok then
        err.println(s"the services of ${project.name} left out did not stop: ${stopped.err.trim}")
        return Left(1)
      ConsoleEntries.remove(project.name, plan.leftOut)
    // A run's sidecar is not in the document and is not a service the map dropped: a live run's is
    // kept, an ended run's was stopped above.
    val runSidecars = project.services.map(LocalPlan.sidecarKey).toSet
    val gone        = before
      .map(_.service)
      .distinct
      .filterNot(plan.compose.services.contains)
      .filterNot(runSidecars)
    gone.foreach(name =>
      err.println(
        s"\"$name\" is no longer in the context map; it is stopped, and its database is kept"
      )
    )
    // Companions next, each waited for by its own check; a service is created only after the
    // companions it needs have answered (research R15).
    val companionsFailed =
      if plan.companions.isEmpty then Vector.empty
      else
        val companions = engine.up(file, composeId, plan.companions, removeOrphans = false)
        if !companions.ok then
          err.println(s"the companions of ${project.name} did not start: ${companions.err.trim}")
          return Left(1)
        val failed = waitReady(plan.companions.map(n => n -> plan.ready(n)), timeout, probe)
        failed.foreach(name => notReady(composeId, name, timeout, engine, err))
        failed
    val heldBack = plan.services.filter(s =>
      companionsFailed.exists(c => plan.neededBy.getOrElse(c, Vector.empty).contains(s))
    )
    heldBack.foreach { s =>
      val waiting =
        companionsFailed.filter(c => plan.neededBy.getOrElse(c, Vector.empty).contains(s))
      err.println(
        s"\"$s\" is not started: it needs ${waiting.map(c => s"\"$c\"").mkString(" and ")}, which is not ready"
      )
    }
    val starting = plan.services.filterNot(heldBack.contains)
    val started  =
      if starting.isEmpty then Engine.Result(0, "", "")
      else if machine.liveRuns.isEmpty then
        engine.up(file, composeId, plan.containersOf(starting), removeOrphans = true)
      else
        // Removing orphans would remove a live run's sidecar, which the document does not name: what
        // the map dropped is stopped by name instead.
        val dropped =
          if gone.isEmpty then Engine.Result(0, "", "") else engine.stop(composeId, gone)
        if !dropped.ok then dropped
        else engine.up(file, composeId, plan.containersOf(starting), removeOrphans = false)
    if !started.ok then
      err.println(s"the services of ${project.name} did not start: ${started.err.trim}")
      return Left(1)
    val unready = waitReady(starting.flatMap(n => plan.ready.get(n).map(n -> _)), timeout, probe)
    unready.foreach(name => notReady(composeId, name, timeout, engine, err))
    // The console lists what is ready; an owned entry nothing answers for yet is kept, not swept.
    ConsoleEntries.write(
      project.name,
      plan.entries.filter(e => starting.contains(e.name) && !unready.contains(e.name))
    )
    if unready.isEmpty && companionsFailed.isEmpty then Right(()) else Left(1)

  /** Each image present or pulled; what could be neither, named by the service that names it. */
  private def imageProblems(plan: LocalPlan, engine: Engine): Vector[String] =
    val wanted =
      (plan.infrastructure ++ plan.companions ++ plan.containersOf(plan.services)).distinct
    wanted
      .flatMap(name => plan.compose.services.get(name).map(name -> _.image))
      .distinctBy(_._2)
      .flatMap { (name, image) =>
        Option.when(!engine.imagePresent(image) && !engine.pull(image).ok)(
          s"\"$name\" names the image $image, which is not on this machine and could not be pulled"
        )
      }

  private def readCompose(root: Path): Option[Compose] =
    val file = composeFile(root)
    Option.when(Files.isRegularFile(file))(file).flatMap { f =>
      try Some(readFromString[Compose](Files.readString(f, UTF_8)))
      catch case _: JsonReaderException => None
    }

  private def deadline(timeout: FiniteDuration): Deadline = Deadline.now + timeout

  private def waitHealthy(
      project: String,
      names: Vector[String],
      timeout: FiniteDuration,
      engine: Engine
  ): Vector[String] =
    val until     = deadline(timeout)
    def unhealthy = {
      val now = engine.ps(project).map(c => c.service -> c).toMap
      names.filterNot(n => now.get(n).exists(c => c.running && c.health == "healthy"))
    }
    var left = unhealthy
    while left.nonEmpty && until.hasTimeLeft() do
      Thread.sleep(500)
      left = unhealthy
    left

  private def waitReady(
      checks: Vector[(String, LocalPlan.ReadyCheck)],
      timeout: FiniteDuration,
      probe: Probe
  ): Vector[String] =
    val until = deadline(timeout)
    var left  = checks.filterNot((_, c) => probe(c))
    while left.nonEmpty && until.hasTimeLeft() do
      Thread.sleep(500)
      left = left.filterNot((_, c) => probe(c))
    left.map(_._1)

  /** The refusal for something not ready in time, with the end of its log. */
  private[local] def notReady(
      project: String,
      name: String,
      timeout: FiniteDuration,
      engine: Engine,
      err: PrintStream
  ): Unit =
    err.println(s"\"$name\" was not ready within ${Durations.show(timeout)}; the end of its log:")
    engine.logs(project, name, Some(LogLines), follow = false, err): Unit

/** What the plan is told of the machine, from what the engine and the ports say. */
private[local] object Machines:

  def of(
      project: LocalProject,
      before: Vector[Engine.Container],
      engine: Engine,
      isFree: Int => Boolean,
      cliVersion: String,
      fromSource: Set[String] = Set.empty,
      processPorts: Map[String, Int] = Map.empty
  ): LocalPlan.Machine =
    val source = project.services
      .filter(fromSource)
      .flatMap { n =>
        val s = project.descriptor(n).service
        (Option.when(s.http)(s.port).toVector ++ Option.when(s.grpc)(s.grpcPort)).map(_ -> n)
      }
      .toMap
    val linux   = sys.props.get("os.name").exists(_.toLowerCase.contains("linux"))
    val running = before.filter(_.running).map(_.service).toSet
    // The sidecar `local run` starts for a process service outlives the run. Its run is live while
    // the process port chosen for it is held, and has ended once that port is free.
    val runs          = project.services.filter(n => running(LocalPlan.sidecarKey(n)))
    val (live, ended) =
      runs.partition(n => processPorts.get(n).exists(p => !isFree(p)))
    def portsOf(n: String) =
      val s = project.descriptor(n).service
      Option.when(s.http)(s.port).toVector ++ Option.when(s.grpc)(s.grpcPort)
    val runningFromSource = live.flatMap(n => portsOf(n).map(_ -> n)).toMap
    // A service's port is its own when its container holds it, or a sidecar left by a run that
    // has ended holds it.
    val ours = project.services
      .filter(n => running(n) || ended.contains(n))
      .flatMap { n =>
        val s = project.descriptor(n).service
        (Option.when(s.http)(s.port).toVector ++ Option.when(s.grpc)(s.grpcPort)).map(_ -> n)
      }
      .toMap ++
      // A running companion's named host ports are its own as well.
      project.local.companions
        .filter(c => running(c.name))
        .flatMap(c => c.ports.flatMap(_.host).map(_ -> c.name))
        .toMap
    LocalPlan.Machine(
      cliVersion = cliVersion,
      gateway = if linux then engine.gatewayAddress else None,
      sidecarImage = sys.env.get("ANKKA_SIDECAR_IMAGE").filter(_.nonEmpty),
      proxyImage = sys.env.get("ANKKA_PROXY_IMAGE").filter(_.nonEmpty),
      holder = port =>
        ours
          .get(port)
          .map(LocalPlan.Holder.Ours(_))
          .orElse(runningFromSource.get(port).map(LocalPlan.Holder.FromSource(_)))
          .orElse(source.get(port).filterNot(_ => isFree(port)).map(LocalPlan.Holder.FromSource(_)))
          .orElse(Option.when(!isFree(port))(LocalPlan.Holder.Other)),
      endedRuns = ended.toSet,
      liveRuns = live.toSet
    )

/** A local database and its schema (research R6). */
private[cli] object Databases:

  private def psql(
      project: String,
      database: String,
      engine: Engine,
      sql: String,
      stdin: Option[String] = None
  ): Engine.Result =
    val words = Vector(
      "psql",
      "-U",
      LocalPlan.DatabaseUser,
      "-d",
      database,
      "-v",
      "ON_ERROR_STOP=1",
      "-tA"
    ) ++
      (if stdin.isEmpty then Vector("-c", sql) else Vector.empty)
    engine.exec(project, LocalPlan.Postgres, words, stdin)

  private def literal(text: String): String = "'" + text.replace("'", "''") + "'"
  private def quoted(name: String): String  = "\"" + name.replace("\"", "\"\"") + "\""

  /** Makes the database and gives it its schema; a second-tier problem when it cannot. */
  def ensure(
      project: String,
      database: LocalPlan.Database,
      machine: LocalPlan.Machine,
      engine: Engine,
      err: PrintStream
  ): Option[String] =
    val name   = database.name
    val exists = psql(
      project,
      "postgres",
      engine,
      s"SELECT 1 FROM pg_database WHERE datname = ${literal(name)}"
    )
    if !exists.ok then
      return Some(s"the database of \"$name\" could not be read: ${exists.err.trim}")
    if exists.out.trim != "1" then
      val created = psql(project, "postgres", engine, s"CREATE DATABASE ${quoted(name)}")
      if !created.ok then
        return Some(s"the database of \"$name\" could not be made: ${created.err.trim}")
    if !database.schema then None
    else
      Schemas.files(database.schemaVersion, machine.cliVersion, engine, machine.sidecarImage) match
        case Left(why)     => Some(why)
        case Right(schema) =>
          val comment = psql(
            project,
            "postgres",
            engine,
            s"SELECT shobj_description(oid, 'pg_database') FROM pg_database WHERE datname = ${literal(name)}"
          )
          Schemas.recorded(comment.out.trim) match
            case Some((_, digest)) if digest == schema.digest    => None
            case Some((old, _)) if !movable(old, schema.version) =>
              Some(
                s"the database of \"$name\" holds the schema of ankka $old and its descriptor names " +
                  s"${schema.version}; remove it with: docker compose -p $project exec ${LocalPlan.Postgres} " +
                  s"dropdb -U ${LocalPlan.DatabaseUser} $name"
              )
            case recorded =>
              val applied = psql(project, name, engine, "", stdin = Some(schema.script))
              if !applied.ok then
                Some(s"the schema of \"$name\" could not be applied: ${applied.err.trim}")
              else
                psql(
                  project,
                  "postgres",
                  engine,
                  s"COMMENT ON DATABASE ${quoted(name)} IS ${literal(Schemas.comment(schema))}"
                ): Unit
                recorded.foreach { (old, _) =>
                  if old != schema.version then
                    err.println(
                      s"the schema of \"$name\" moved from ankka $old to ${schema.version}"
                    )
                }
                None

  /**
   * Whether a database of `old` may be given the schema of `next`: the same, or newer of one major.
   */
  private[cli] def movable(old: String, next: String): Boolean =
    if old == next then true
    else
      (Release.parse(old), Release.parse(next)) match
        case (Some(o), Some(n)) =>
          o.major == n.major && (n.minor > o.minor || (n.minor == o.minor && n.patch > o.patch))
        // A development build's version names no release: its schema is taken to be the newest,
        // after a release or after another development build (each commit is one).
        case (_, None) => true
        case _         => false

/** How a duration is written on the command line and in a refusal: `90s`, `5m`. */
object Durations:

  private val Pattern = """(\d+)\s*(ms|s|m|h)""".r

  def parse(text: String): Either[String, FiniteDuration] = text.trim match
    case Pattern(n, unit) =>
      val amount = n.toLong
      Right(unit match
        case "ms" => amount.millis
        case "s"  => amount.seconds
        case "m"  => amount.minutes
        case _    => amount.hours)
    case other => Left(s"'$other' is not a duration; write it as 90s, 5m or 1h")

  def show(d: FiniteDuration): String =
    if d.toMillis % 3600000 == 0 then s"${d.toHours}h"
    else if d.toMillis % 60000 == 0 then s"${d.toMinutes}m"
    else if d.toMillis % 1000 == 0 then s"${d.toSeconds}s"
    else s"${d.toMillis}ms"

/** The readiness checks, asked from the host. */
object Probes:

  private val client = java.net.http.HttpClient
    .newBuilder()
    .connectTimeout(java.time.Duration.ofSeconds(1))
    .build()

  private def get(url: String): Option[(Int, String)] =
    try
      val request = java.net.http.HttpRequest
        .newBuilder(java.net.URI.create(url))
        .timeout(java.time.Duration.ofSeconds(2))
        .GET()
        .build()
      val answer = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString())
      Some(answer.statusCode -> answer.body)
    catch case _: java.io.IOException | _: InterruptedException => None

  private def connects(port: Int): Boolean =
    try
      val socket = java.net.Socket()
      try
        socket.connect(java.net.InetSocketAddress("127.0.0.1", port), 1000)
        true
      finally socket.close()
    catch case _: java.io.IOException => false

  val host: LocalUp.Probe =
    case LocalPlan.ReadyCheck.Observability(url, servesHttp) =>
      get(url).exists((status, body) => status == 200 && (!servesHttp || body.contains("\"http\"")))
    case LocalPlan.ReadyCheck.Port(port)       => connects(port)
    case LocalPlan.ReadyCheck.Http(port, path) =>
      get(s"http://127.0.0.1:$port$path").exists(_._1 < 500)
    case LocalPlan.ReadyCheck.Tcp(port) => connects(port)

/** The table `up` prints (contracts/cli.md). */
private[local] object Table:

  def print(
      project: LocalProject,
      plan: LocalPlan,
      previous: Option[Compose],
      before: Vector[Engine.Container],
      after: Vector[Engine.Container],
      out: PrintStream
  ): Unit =
    val was                           = before.map(c => c.service -> c).toMap
    val now                           = after.map(c => c.service -> c).toMap
    def address(name: String): String =
      project.descriptors.get(name) match
        case Some(d) =>
          val spec = d.service
          (Option.when(spec.http)(s"http://127.0.0.1:${spec.port}").toVector ++
            Option.when(spec.grpc)(s"grpc 127.0.0.1:${spec.grpcPort}")).mkString("  ")
        case None =>
          plan.ready.get(name) match
            case Some(LocalPlan.ReadyCheck.Http(port, _)) => s"http://127.0.0.1:$port"
            case _                                        =>
              plan.compose.services
                .get(name)
                .flatMap(_.ports.headOption)
                .map(p => s"127.0.0.1:${p.split(':').dropRight(1).last}")
                .getOrElse("")
    val rows = (plan.companions ++ plan.services).map { name =>
      val shown  = address(name)
      val status = (was.get(name).filter(_.running), now.get(name)) match
        case (None, _)                                    => "started"
        case (Some(b), Some(a)) if b.id == a.id           => "running"
        case (Some(b), Some(a)) if b.imageId != a.imageId => "restarted (its image changed)"
        case (Some(_), _)                                 =>
          val changed = previous
            .flatMap(_.services.get(name))
            .forall(old => old.environment != plan.compose.services(name).environment)
          if changed then "restarted (its environment changed)" else "restarted"
      val marked = if plan.companions.contains(name) then s"$status      (a companion)" else status
      (name, if shown.isEmpty then "-" else shown, marked)
    }
    val nameWidth    = rows.map(_._1.length).maxOption.getOrElse(0)
    val addressWidth = rows.map(_._2.length).maxOption.getOrElse(0)
    rows.foreach { (name, address, status) =>
      out.println(s"  ${name.padTo(nameWidth, ' ')}  ${address.padTo(addressWidth, ' ')}  $status")
    }

/** The declared topics made on the project's broker before anything that uses them starts. */
object Topics:

  /** Every topic made that was missing, or why one that exists cannot be used. */
  def ensure(
      composeId: String,
      wanted: Vector[LocalPlan.BrokerTopic],
      engine: Engine
  ): Vector[String] =
    if wanted.isEmpty then Vector.empty
    else
      val described = engine.exec(composeId, LocalPlan.Kafka, BrokerTopics.describeCommand, None)
      if !described.ok then
        Vector(s"the topics on the local broker could not be read: ${described.err.trim}")
      else
        val (toCreate, problems) =
          BrokerTopics.diff(wanted, BrokerTopics.parseDescribe(described.out))
        if problems.nonEmpty then problems
        else if toCreate.isEmpty then Vector.empty
        else
          val made =
            engine.exec(composeId, LocalPlan.Kafka, BrokerTopics.createCommand(toCreate), None)
          Option
            .when(!made.ok)(
              s"the topics ${toCreate.map(_.name).mkString(", ")} could not be made on the local " +
                s"broker: ${made.err.trim}"
            )
            .toVector
