package com.thinkmorestupidless.ankka.cli

import com.thinkmorestupidless.ankka.controlplane.api.Protocol
import com.thinkmorestupidless.ankka.core.{ContextMap, Names}

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

/**
 * `ankka init --project` and `ankka init <service>` inside a project (feature 062).
 *
 * A project is one repository of several services, found by its `context-map.json`. It is started
 * from the CLI's `project` template with two or more Scala services, each rendered from the
 * `project-service-scala` skeleton; a service in any language is added inside it later from that
 * language's template, without the parts the project owns. Rendering replaces exact tokens, as
 * `Scaffold` does, and the context map is written and rendered through `core`'s `ContextMap`, so
 * `CONTEXT-MAP.md` is never a template file that could drift from the map.
 */
object Projects:

  /** The project's own directories, which no service may be named. */
  val Reserved: Set[String] = Set("shared", "tests")

  /** The first port a service of a project is given locally. */
  val FirstPort: Int = 9000

  /** The first process port; `ANKKA_PROCESS_PORT`'s default, which a single service keeps. */
  val FirstProcessPort: Int = 9010

  /** spec-kit's own setup, which the project template carries no copy of. */
  val SpecifyCommand: Vector[String] =
    Vector("specify", "init", "--here", "--ai", "claude", "--ai-skills")

  // Scala's keywords: a service named after one could not be a package segment.
  private val ScalaKeywords: Set[String] =
    ("abstract case catch class def do else enum export extends false final finally for forSome " +
      "given if implicit import lazy match new null object override package private protected " +
      "return sealed super then this throw trait true try type val var while with yield")
      .split(' ')
      .toSet

  /** The package segment a Scala service's code is in: its name without `-`. */
  def servicePackage(service: String): String = service.replace("-", "")

  /** The prefix of a Scala service's types: its name's words, each capitalised. */
  def serviceClass(service: String): String = service.split('-').map(_.capitalize).mkString

  // ── Starting a project ────────────────────────────────────────────────────────────────────────

  /** Everything wrong with `ankka init <project> --project --services …`; empty means go. */
  def startProblems(request: Init.Request, services: Option[String], add: Boolean): Vector[String] =
    val names  = services.toVector.flatMap(_.split(',').map(_.trim).filter(_.nonEmpty))
    val target = request.directory.resolve(request.name)
    val listed =
      if names.size < 2 then Vector("--project needs --services with at least two service names")
      else
        names.flatMap(Names.serviceNameProblems) ++
          (if names.distinct.size != names.size then Vector("the services must be distinct")
           else Vector.empty) ++
          (if names.exists(Reserved) then
             Vector("\"shared\" and \"tests\" are the project's own directories")
           else Vector.empty) ++
          names.filter(n => ScalaKeywords(servicePackage(n))).map(keyword)
    val occupied =
      if Files.isDirectory(target) && Files.list(target).iterator().asScala.nonEmpty then
        Vector(s"$target already exists and is not empty")
      else Vector.empty
    val options =
      request.template.map(_ => "--template applies to a single Scala service").toVector ++
        (if request.language != Language.Scala then
           Vector(
             "--project starts Scala services; add a service in another language inside the " +
               "project with ankka init <service> --language <lang>"
           )
         else Vector.empty) ++
        (if add then Vector("--add adds a service to a project; --project starts one")
         else Vector.empty)
    Names.Project.problems(request.name) ++ listed ++ occupied ++ options ++
      Scaffold.packageProblems(Scaffold.jvmPackage(request), Language.Scala)

  private def keyword(service: String): String =
    s"service name '$service' is a Scala keyword without its '-', which a Scala service's package cannot be"

  /** Writes the project at `<directory>/<name>` with its services; returns where. */
  def renderProject(request: Init.Request, services: Vector[String], version: String): Path =
    val target   = request.directory.resolve(request.name)
    val pkg      = Scaffold.jvmPackage(request)
    val first    = services(0)
    val second   = services(1)
    val projectT = base(pkg, version) ++ Map(
      "{{project}}"    -> request.name,
      "{{first}}"      -> first,
      "{{First}}"      -> serviceClass(first),
      "{{first_pkg}}"  -> servicePackage(first),
      "{{second}}"     -> second,
      "{{Second}}"     -> serviceClass(second),
      "{{second_pkg}}" -> servicePackage(second)
    )
    write("project", target, projectT, _ => true, identity)
    services.zipWithIndex.foreach { case (service, i) =>
      val role = if i == 0 then "first" else if i == 1 then "second" else "plain"
      writeSkeleton(target, service, role, FirstPort + i, pkg, version, first, second)
    }
    // The template's map names the first two services and the sample's crossings; any more are
    // appended as a service added later would be.
    val map = readMap(target)
    val all = services.drop(2).foldLeft(map)(appendService)
    writeMap(target, all)
    target

  // ── Adding a service ──────────────────────────────────────────────────────────────────────────

  /** Whether `directory` holds a project started from the project template. */
  def isProject(directory: Path): Boolean =
    Files.isRegularFile(directory.resolve(ContextMap.FileName))

  def outside(directory: Path): String =
    s"$directory holds no project started from the project template (no context-map.json); " +
      "nothing was written"

  /** Everything wrong with adding `request.name` to the project at `request.directory`. */
  def addProblems(request: Init.Request): Vector[String] =
    val root    = request.directory
    val name    = request.name
    val project = readMap(root).project
    val exists  =
      if Reserved(name) || Files.exists(root.resolve("services").resolve(name)) ||
        readMap(root).services.exists(_.name == name)
      then Vector(s"$project already has a service \"$name\"")
      else Vector.empty
    val language = request.language match
      case Language.Scala =>
        request.template.map(_ => "--template applies to a single Scala service").toVector ++
          (if ScalaKeywords(servicePackage(name)) then Vector(keyword(name)) else Vector.empty)
      case _ =>
        // The language's own rules, against a directory that is the service's own.
        Init
          .problems(request.copy(directory = root.resolve("services")))
          .filterNot(_.contains("already exists"))
    Names.serviceNameProblems(name) ++ exists ++ language

  /** The smallest port from 9000 that no service's descriptor in the project uses. */
  def nextPort(root: Path): Int =
    val used = descriptors(root).map(portOf).toSet
    Iterator.from(FirstPort).find(p => !used(p)).get

  /**
   * The smallest even port from 9010 that no process service's compose file dials: a Python,
   * TypeScript or Go process listens on it, and its sidecar's observe port is the one above, so two
   * such services run side by side.
   */
  def nextProcessPort(root: Path): Int =
    val used = composes(root).flatMap(processPortOf).toSet
    Iterator.from(FirstProcessPort, 2).find(p => !used(p)).get

  /**
   * Writes `services/<name>/`, appends it to the map and renders the map again; returns its
   * directory.
   */
  def addService(request: Init.Request, version: String): Path =
    val root    = request.directory
    val name    = request.name
    val port    = nextPort(root)
    val service = root.resolve("services").resolve(name)
    request.language match
      case Language.Scala =>
        val pkg = request.pkg.getOrElse(projectPackage(root))
        writeSkeleton(root, name, "plain", port, pkg, version, name, name)
      case language =>
        // Chosen before the language's own compose file is written, which names the default port.
        val process = nextProcessPort(root)
        val values  = Scaffold.tokens(request, version)
        write(language.id, service, values, keep, identity)
        withPort(service.resolve("service.json"), port)
        stripDeploy(service.resolve("README.md"))
        val compose = service.resolve("docker-compose.yml")
        language match
          case Language.Python | Language.TypeScript | Language.Go =>
            Files.writeString(compose, sidecarCompose(name, port, process, version)): Unit
            withProcessPort(service.resolve("README.md"), process)
          case Language.Rust =>
            Files.writeString(
              compose,
              runtimeCompose(name, port, version, Scaffold.module(request).replace('-', '_'))
            ): Unit
          case _ => Files.deleteIfExists(compose): Unit
    writeMap(root, appendService(readMap(root), name))
    service

  // A language's template, without what the project owns: its workflows, hooks, skills and MCP entry.
  private def keep(path: String): Boolean =
    !(path.startsWith(".github/") || path.startsWith(".githooks/") || path.startsWith(".claude/") ||
      path == ".mcp.json")

  private def appendService(map: ContextMap, name: String): ContextMap =
    map.copy(services =
      map.services :+ ContextMap.Service(name, "", ContextMap.Boundary("ownership", ""))
    )

  // ── specify ───────────────────────────────────────────────────────────────────────────────────

  /** What became of spec-kit's own setup. */
  enum Specify:
    case Ran
    case Failed(code: Int)
    case Skipped

  /**
   * Runs `specify init` in the project when it is on PATH and neither `-Dankka.init.specify=off`
   * nor `ANKKA_INIT_SPECIFY=off` says not to. `run` is the process, so a test scripts it.
   */
  def specify(
      target: Path,
      env: Map[String, String] = sys.env,
      props: collection.Map[String, String] = sys.props,
      run: (Vector[String], Path) => Int = runInherited
  ): Specify =
    val off = props.get("ankka.init.specify").contains("off") || env
      .get("ANKKA_INIT_SPECIFY")
      .contains("off")
    if off || !onPath("specify", env) then Specify.Skipped
    else
      run(SpecifyCommand, target) match
        case 0    => Specify.Ran
        case code => Specify.Failed(code)

  private def runInherited(command: Vector[String], directory: Path): Int =
    new ProcessBuilder(command*).directory(directory.toFile).inheritIO().start().waitFor()

  def onPath(program: String, env: Map[String, String] = sys.env): Boolean =
    env
      .getOrElse("PATH", "")
      .split(java.io.File.pathSeparator)
      .filter(_.nonEmpty)
      .exists(dir => Files.isExecutable(Path.of(dir).resolve(program)))

  /** The next steps printed once a project is written. */
  def startSteps(where: Path, name: String, first: String, specify: Specify): String =
    val setup = specify match
      case Specify.Ran       => Vector.empty
      case Specify.Failed(c) =>
        Vector(SpecifyCommand.mkString(" ") + s"   # specify init exited with $c")
      case Specify.Skipped => Vector(SpecifyCommand.mkString(" "))
    val steps = Vector(s"cd $name") ++ setup ++ Vector(
      "docker compose up -d",
      s"scripts/run.sh $first",
      "sbt test",
      "ankka context-map check"
    )
    s"created $where\n\n" + steps.map("  " + _).mkString("\n") + "\n\nsee README.md for the rest"

  /** The next steps printed once a service is added. */
  def addSteps(service: Path, name: String, language: Language): String =
    val dir   = s"services/$name"
    val steps = language match
      case Language.Scala  => Vector(s"sbt $name/test", s"scripts/run.sh $name")
      case Language.Python =>
        Vector(s"(cd $dir && uv sync && uv run pytest -q -rs)", s"scripts/run.sh $name")
      case Language.TypeScript =>
        Vector(s"(cd $dir && npm install && npm test)", s"scripts/run.sh $name")
      case Language.Rust => Vector(s"(cd $dir && cargo test)", s"scripts/run.sh $name")
      case Language.Go   =>
        Vector(s"(cd $dir && go mod tidy && go test ./...)", s"scripts/run.sh $name")
      case Language.Java   => Vector(s"(cd $dir && ./mvnw test)", s"scripts/run.sh $name")
      case Language.Kotlin => Vector(s"(cd $dir && ./gradlew test)", s"scripts/run.sh $name")
      case Language.Web => Vector(s"(cd $dir && npm install && npm test)", s"scripts/run.sh $name")
    s"created $service\n\n" + steps.map("  " + _).mkString("\n") +
      s"\n\ncomplete the context map's entry for \"$name\" (owns, why) and record its crossings"

  // ── Files ─────────────────────────────────────────────────────────────────────────────────────

  private def base(pkg: String, version: String): Map[String, String] = Map(
    "{{ankka_version}}"    -> version,
    "{{protocol_version}}" -> Protocol.version.toString,
    "{{package}}"          -> pkg,
    "{{package_path}}"     -> pkg.replace('.', '/')
  )

  private def writeSkeleton(
      root: Path,
      service: String,
      role: String,
      port: Int,
      pkg: String,
      version: String,
      first: String,
      second: String
  ): Unit =
    val values = base(pkg, version) ++ Map(
      "{{service}}"     -> service,
      "{{Service}}"     -> serviceClass(service),
      "{{service_pkg}}" -> servicePackage(service),
      "{{port}}"        -> port.toString,
      "{{first}}"       -> first,
      "{{First}}"       -> serviceClass(first),
      "{{second}}"      -> second
    )
    val prefixes = Vector("common/", s"$role/")
    write(
      "project-service-scala",
      root.resolve("services").resolve(service),
      values,
      path => prefixes.exists(path.startsWith),
      path => prefixes.find(path.startsWith).fold(path)(p => path.drop(p.length))
    )

  // Renders the template `id`'s entries that `include` keeps into `target`, each at `relocate(path)`.
  private def write(
      id: String,
      target: Path,
      values: Map[String, String],
      include: String => Boolean,
      relocate: String => String
  ): Unit =
    def fill(text: String) = values.foldLeft(text) { case (acc, (token, value)) =>
      acc.replace(token, value)
    }
    for Scaffold.Entry(path, mode) <- Scaffold.entriesOf(id) if include(path) do
      val bytes = Scaffold
        .resource(s"$id/$path")
        .getOrElse(throw IllegalStateException(s"the $id template lists $path but lacks it"))
      val out = target.resolve(fill(relocate(path)))
      Files.createDirectories(out.getParent)
      mode match
        case Scaffold.Mode.Binary => Files.write(out, bytes): Unit
        case _                    => Files.writeString(out, fill(new String(bytes, UTF_8))): Unit
      if mode == Scaffold.Mode.Exec then Scaffold.executable(out)

  private def readMap(root: Path): ContextMap =
    ContextMap.read(root) match
      case Right(map)     => map
      case Left(problems) =>
        throw ApiError(0, problems.mkString(s"${root.resolve(ContextMap.FileName)}: ", "; ", ""))

  private def writeMap(root: Path, map: ContextMap): Unit =
    Files.writeString(root.resolve(ContextMap.FileName), ContextMap.write(map))
    Files.writeString(root.resolve(ContextMap.RenderingName), ContextMap.render(map)): Unit

  private def descriptors(root: Path): Vector[Path] =
    val services = root.resolve("services")
    if !Files.isDirectory(services) then Vector.empty
    else
      Files
        .list(services)
        .iterator()
        .asScala
        .map(_.resolve("service.json"))
        .filter(Files.isRegularFile(_))
        .toVector

  // A descriptor that names no port is served on the runtime's default, 9000.
  private def portOf(descriptor: Path): Int =
    "\"port\"\\s*:\\s*(\\d+)".r
      .findFirstMatchIn(Files.readString(descriptor))
      .map(_.group(1).toInt)
      .getOrElse(FirstPort)

  // The process ports the project's compose files dial.
  private def composes(root: Path): Vector[Path] =
    descriptors(root).map(_.resolveSibling("docker-compose.yml")).filter(Files.isRegularFile(_))

  private def processPortOf(compose: Path): Option[Int] =
    "ANKKA_PROCESS_ADDRESS:\\s*host\\.docker\\.internal:(\\d+)".r
      .findFirstMatchIn(Files.readString(compose))
      .map(_.group(1).toInt)

  // A language's README says its process listens on 9010; inside a project it is this service's port.
  private def withProcessPort(readme: Path, process: Int): Unit =
    if Files.isRegularFile(readme) then
      val text = Files.readString(readme)
      Files.writeString(
        readme,
        text.replace(s"port $FirstProcessPort", s"port $process (ANKKA_PROCESS_PORT=$process)")
      ): Unit

  private def withPort(descriptor: Path, port: Int): Unit =
    val text = Files.readString(descriptor)
    Files.writeString(
      descriptor,
      text.replaceFirst("\"service\"\\s*:\\s*\\{", s"\"service\": {\n    \"port\": $port,")
    ): Unit

  // A service's README inside a project says nothing of deploying: the project's release does that.
  private def stripDeploy(readme: Path): Unit =
    if Files.isRegularFile(readme) then
      val (kept, _) =
        Files.readString(readme).linesIterator.foldLeft((Vector.empty[String], false)) {
          case ((lines, dropping), line) =>
            if line.startsWith("## ") then
              val drop = line.startsWith("## Deploy")
              (if drop then lines else lines :+ line, drop)
            else (if dropping then lines else lines :+ line, dropping)
        }
      Files.writeString(
        readme,
        kept.mkString("", "\n", "\n") +
          "\n## Release\n\nThis service is released by the project's service tag, `" +
          readme.getParent.getFileName + "/v<version>`: see the project's README.md.\n"
      ): Unit

  /** The project's package: the directory holding the shared module's contracts, or the default. */
  private def projectPackage(root: Path): String =
    val scala = root.resolve("shared/src/main/scala")
    val found =
      if !Files.isDirectory(scala) then None
      else
        Files
          .walk(scala)
          .iterator()
          .asScala
          .find(p => Files.isDirectory(p) && p.getFileName.toString == "contracts")
          .map(p => scala.relativize(p.getParent).toString.replace(java.io.File.separatorChar, '.'))
          .filter(_.nonEmpty)
    found.getOrElse(Scaffold.jvmPackage(Init.Request(readMap(root).project)))

  private def sidecar(version: String): String =
    "${ANKKA_SIDECAR_IMAGE:-ghcr.io/thinkmorestupidless/ankka-sidecar:" + version + "}"

  private def database(name: String, port: Int): String =
    s"""      ANKKA_SERVICE_NAME: $name
       |      ANKKA_HTTP_PORT: "$port"
       |      ANKKA_DB_HOST: host.docker.internal
       |      ANKKA_DB_PORT: "5432"
       |      ANKKA_DB_NAME: $name
       |      ANKKA_DB_USER: ankka
       |      ANKKA_DB_PASSWORD: ankka
       |      ANKKA_KAFKA_BOOTSTRAP_SERVERS: host.docker.internal:9094
       |      ANKKA_SECRET_KEY: $${ANKKA_SECRET_KEY:-}
       |      ANKKA_OTLP_ENDPOINT: $${ANKKA_OTLP_ENDPOINT:-}
       |      ANKKA_OTLP_HEADERS: $${ANKKA_OTLP_HEADERS:-}""".stripMargin

  /**
   * A process service's sidecar, on the project's Postgres and Kafka: no Postgres of its own. It
   * dials the process on `process`, a port of this service's own, and its observe port is the one
   * above, so the project's process services run side by side; `scripts/run.sh` reads the process
   * port from this file.
   */
  def sidecarCompose(name: String, port: Int, process: Int, version: String): String =
    s"""# $name's sidecar, beside the project's Postgres and Kafka (the docker-compose.yml at the project's
       |# root): it owns the journal, the views and HTTP, in the database "$name", and dials this
       |# service's process on port $process. scripts/run.sh $name starts it, then the process on that port.
       |services:
       |  sidecar:
       |    image: ${sidecar(version)}
       |    environment:
       |      ANKKA_PROCESS_ADDRESS: host.docker.internal:$process
       |      ANKKA_SIDECAR_BIND: 0.0.0.0
       |${database(name, port)}
       |    extra_hosts:
       |      - "host.docker.internal:host-gateway"
       |    ports:
       |      - "$port:$port"
       |      - "127.0.0.1:${process + 1}:9011"
       |""".stripMargin

  /** A WebAssembly service's runtime, on the project's Postgres and Kafka. */
  def runtimeCompose(name: String, port: Int, version: String, module: String): String =
    s"""# $name's runtime, beside the project's Postgres and Kafka (the docker-compose.yml at the
       |# project's root): the module `cargo module` builds, loaded into the sidecar image, in the
       |# database "$name". scripts/run.sh $name builds the module and starts it.
       |services:
       |  runtime:
       |    profiles: ["wasm"]
       |    image: ${sidecar(version)}
       |    environment:
       |      ANKKA_WASM_MODULE: /module/service.wasm
       |${database(name, port)}
       |    extra_hosts:
       |      - "host.docker.internal:host-gateway"
       |    ports:
       |      - "$port:$port"
       |    volumes:
       |      - type: bind
       |        source: ./target/wasm32-unknown-unknown/release/$module.wasm
       |        target: /module/service.wasm
       |        read_only: true
       |        bind:
       |          create_host_path: false
       |""".stripMargin
