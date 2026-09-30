package com.thinkmorestupidless.ankka.cli.mcp

import com.thinkmorestupidless.ankka.cli.ApiError

import java.nio.file.{Files, Path, Paths}

/**
 * `ankka mcp install`: tells an MCP client how to start `ankka mcp`.
 *
 * Three targets, because a client's configuration lives in three places:
 *
 *   - **Claude Code, for this user** — through `claude mcp add --scope user`, Claude Code's own
 *     command, rather than by editing `~/.claude.json`, which is Claude Code's file to write.
 *   - **A project** — a `.mcp.json` at the project's root, the file `ankka init` writes. It names
 *     the command `ankka` rather than a path, because it is committed and read on other machines.
 *   - **Claude Desktop** — its `claude_desktop_config.json`, merged. Desktop is started from the
 *     Dock, not a shell, so it is given an absolute path, and for the JVM build a `JAVA_HOME`: its
 *     `PATH` is the system's, which rarely holds `ankka` and less often `java`.
 *
 * Every write is a merge: other servers are kept, and an existing `ankka` entry is left alone
 * unless `--force` replaces it. The planning is pure, so the rules are tested without touching a
 * real configuration or running `claude`.
 */
private[cli] object McpInstall:

  /** The server's name in every client's configuration. */
  val ServerName = "ankka"

  /** How a client starts the server: a command, its arguments, and any environment it needs. */
  final case class Launch(command: String, args: Vector[String], env: Vector[(String, String)]):
    def entry: Json = Json.Obj(
      Vector("command" -> Json.str(command), "args" -> Json.Arr(args.map(Json.str))) ++
        Option.when(env.nonEmpty)("env" -> Json.Obj(env.map((k, v) => k -> Json.str(v))))
    )

  /** What a project's `.mcp.json` says: the command by name, since other machines read it. */
  val ProjectLaunch: Launch = Launch("ankka", Vector("mcp"), Vector.empty)

  // ── Finding the ankka to start ────────────────────────────────────────────

  /**
   * The command a client outside a shell should run: `--command` if given; else the first `ankka`
   * on `PATH`, as a shell would find it (for Homebrew that is the stable symlink, not the versioned
   * path it points to, so an upgrade does not break it); else this process's own executable.
   */
  def locate(
      explicit: Option[String],
      path: String = sys.env.getOrElse("PATH", ""),
      self: () => Option[Path] = () => running()
  ): Path =
    explicit
      .map(Paths.get(_).toAbsolutePath)
      .orElse(onPath("ankka", path))
      .orElse(self())
      .getOrElse(
        throw ApiError(
          0,
          "cannot tell where this ankka is installed: put it on PATH, or pass --command /path/to/ankka"
        )
      )

  def onPath(name: String, path: String): Option[Path] =
    path
      .split(java.io.File.pathSeparator)
      .iterator
      .filter(_.nonEmpty)
      .map(dir => Paths.get(dir).resolve(name))
      .find(Files.isExecutable)

  /** Whether this process is a native image, which has no JVM to point a client at. */
  def isNative: Boolean = sys.props.get("org.graalvm.nativeimage.imagecode").contains("runtime")

  /**
   * This process's own executable: the binary itself when native; for the JVM build, the launcher
   * script beside the directory the CLI's jar was loaded from (`bin/ankka` next to `lib/`).
   */
  private def running(): Option[Path] =
    if isNative then Option(ProcessHandle.current().info().command().orElse(null)).map(Paths.get(_))
    else
      Option(getClass.getProtectionDomain.getCodeSource)
        .flatMap(source => Option(source.getLocation))
        .map(url => Paths.get(url.toURI))
        .flatMap(jar => Option(jar.getParent).flatMap(lib => Option(lib.getParent)))
        .map(_.resolve("bin").resolve("ankka"))
        .filter(Files.isExecutable)

  /**
   * The launch for a client that does not inherit a shell. The JVM build's launcher runs `java`
   * from `JAVA_HOME` when it is set, so it is set to this JVM's; a native binary needs nothing.
   */
  def desktopLaunch(command: Path, native: Boolean = isNative): Launch =
    val env =
      if native then Vector.empty
      else sys.props.get("java.home").map(home => "JAVA_HOME" -> home).toVector
    Launch(command.toString, Vector("mcp"), env)

  // ── Merging a configuration file ──────────────────────────────────────────

  enum Outcome:
    /** The entry was absent and is now written. */
    case Added

    /** The entry was there and differed; `--force` replaced it. */
    case Replaced

    /** The entry is already exactly this; nothing to do. */
    case Unchanged

    /** The entry is there and differs; left alone without `--force`. */
    case Kept(existing: Json)

  /**
   * `config` with `mcpServers.ankka` set to `entry`, keeping every other field and server in the
   * order it was written. `None` for the configuration means the file does not exist yet.
   */
  def merge(config: Option[Json], entry: Json, force: Boolean): (Json, Outcome) =
    val root = config.getOrElse(Json.Obj(Vector.empty)) match
      case obj: Json.Obj => obj
      case _             => throw ApiError(0, "the configuration is not a JSON object")
    val servers = root("mcpServers") match
      case Some(obj: Json.Obj) => obj
      case None                => new Json.Obj(Vector.empty)
      case Some(_) => throw ApiError(0, "the configuration's mcpServers is not an object")
    servers(ServerName) match
      case Some(existing) if same(existing, entry) => (root, Outcome.Unchanged)
      case Some(existing) if !force                => (root, Outcome.Kept(existing))
      case existing =>
        val updated = put(servers, ServerName, entry)
        (
          put(root, "mcpServers", updated),
          if existing.isDefined then Outcome.Replaced else Outcome.Added
        )

  /** Equal as JSON: an object's keys in any order, as a person may have written them. */
  private def same(a: Json, b: Json): Boolean = (a, b) match
    case (Json.Obj(x), Json.Obj(y)) =>
      x.size == y.size && x.forall((k, v) =>
        y.collectFirst { case (`k`, w) => w }.exists(same(v, _))
      )
    case (Json.Arr(x), Json.Arr(y)) => x.size == y.size && x.zip(y).forall(same.tupled)
    case _                          => a == b

  private def put(obj: Json.Obj, key: String, value: Json): Json.Obj =
    if obj.fields.exists(_._1 == key) then
      Json.Obj(obj.fields.map((k, v) => if k == key then k -> value else k -> v))
    else Json.Obj(obj.fields :+ (key -> value))

  def read(file: Path): Option[Json] =
    if !Files.exists(file) then None
    else
      Json.parse(Files.readString(file)) match
        case Right(json) => Some(json)
        case Left(error) =>
          throw ApiError(0, s"$file is not valid JSON, so it was left as it is: $error")

  /**
   * Merges the entry into `file` and writes it, unless `dryRun`. Returns what happened and the text
   * that was (or would be) written.
   */
  def install(file: Path, entry: Json, force: Boolean, dryRun: Boolean): (Outcome, String) =
    val (merged, outcome) = merge(read(file), entry, force)
    val text              = merged.pretty + "\n"
    val writes = outcome match
      case Outcome.Added | Outcome.Replaced => true
      case _                                => false
    if writes && !dryRun then
      Option(file.getParent).foreach(parent => Files.createDirectories(parent): Unit)
      Files.writeString(file, text): Unit
    (outcome, text)

  // ── Where Claude Desktop keeps its configuration ─────────────────────────

  /**
   * `-Dankka.claude.desktop.config` if set — so a test never writes to the developer's own — else
   * where Claude Desktop reads it on macOS and Windows. There is no Claude Desktop for Linux.
   */
  def desktopConfig(
      os: String = sys.props.getOrElse("os.name", ""),
      home: String = sys.props("user.home"),
      appData: Option[String] = sys.env.get("APPDATA")
  ): Path =
    sys.props.get("ankka.claude.desktop.config").map(Paths.get(_)).getOrElse {
      val name = os.toLowerCase
      if name.contains("mac") then
        Paths.get(home, "Library", "Application Support", "Claude", "claude_desktop_config.json")
      else if name.contains("windows") then
        Paths.get(
          appData.getOrElse(Paths.get(home, "AppData", "Roaming").toString),
          "Claude",
          "claude_desktop_config.json"
        )
      else
        throw ApiError(
          0,
          "Claude Desktop runs on macOS and Windows only; on this machine use `ankka mcp install` for Claude Code"
        )
    }

  // ── Claude Code, through its own CLI ──────────────────────────────────────

  /** The `claude` invocation that registers the server for this user, as a value. */
  def claudeAdd(launch: Launch): Vector[String] =
    Vector("claude", "mcp", "add", "--scope", "user") ++
      launch.env.flatMap((k, v) => Vector("-e", s"$k=$v")) ++
      Vector(ServerName, "--", launch.command) ++ launch.args

  val claudeGet: Vector[String] = Vector("claude", "mcp", "get", ServerName)
  val claudeRemove: Vector[String] =
    Vector("claude", "mcp", "remove", "--scope", "user", ServerName)

  /** Runs a command to completion; its exit code and everything it printed. */
  type Runner = Vector[String] => (Int, String)

  val processRunner: Runner = argv =>
    val process = new ProcessBuilder(argv*).redirectErrorStream(true).start()
    val output  = new String(process.getInputStream.readAllBytes())
    (process.waitFor(), output.trim)

  // ── The command ───────────────────────────────────────────────────────────

  enum Client:
    case Code, Desktop

  enum Scope:
    case User, Project

  final case class Request(
      client: Client = Client.Code,
      scope: Scope = Scope.User,
      dir: Path = Paths.get("."),
      command: Option[String] = None,
      force: Boolean = false,
      dryRun: Boolean = false
  )

  /**
   * Carries out a request and says what happened. `runner` and `path` are parameters so a test can
   * drive the Claude Code branch without the real `claude`, which would write the developer's own
   * configuration.
   */
  def perform(
      request: Request,
      runner: Runner = processRunner,
      path: String = sys.env.getOrElse("PATH", "")
  ): String =
    (request.client, request.scope) match
      case (Client.Desktop, Scope.Project) =>
        throw ApiError(
          0,
          "--scope project is for Claude Code; Claude Desktop has one configuration"
        )
      case (Client.Code, Scope.Project) =>
        val file = request.dir.resolve(".mcp.json").toAbsolutePath.normalize
        val (outcome, text) =
          install(file, ProjectLaunch.entry, request.force, request.dryRun)
        report(outcome, file.toString, text, request.dryRun) +
          (if outcome == Outcome.Added && !request.dryRun then
             "\nCommit it; Claude Code asks each person once before starting a project's server."
           else "")
      case (Client.Desktop, Scope.User) =>
        val file            = desktopConfig()
        val launch          = desktopLaunch(locate(request.command, path))
        val (outcome, text) = install(file, launch.entry, request.force, request.dryRun)
        report(outcome, file.toString, text, request.dryRun) +
          (if Set(Outcome.Added, Outcome.Replaced)(outcome) && !request.dryRun then
             "\nQuit and reopen Claude Desktop to load it."
           else "")
      case (Client.Code, Scope.User) =>
        val launch = Launch(locate(request.command, path).toString, Vector("mcp"), Vector.empty)
        val add    = claudeAdd(launch)
        if onPath("claude", path).isEmpty then
          s"""Claude Code's `claude` command is not on PATH, so nothing was changed. Run:
             |
             |  ${shell(add)}
             |
             |or install the ankka plugin inside Claude Code:
             |
             |  /plugin marketplace add thinkmorestupidless/ankka-marketplace
             |  /plugin install ankka@ankka""".stripMargin
        else if request.dryRun then s"would run: ${shell(add)}"
        else
          val (present, existing) = runner(claudeGet)
          if present == 0 && !request.force then
            s"""Claude Code already has a server named '$ServerName'; left as it is:
               |
               |${existing.linesIterator.map("  " + _).mkString("\n")}
               |
               |Pass --force to replace it.""".stripMargin
          else
            if present == 0 then
              val (code, output) = runner(claudeRemove)
              if code != 0 then throw ApiError(0, s"`${shell(claudeRemove)}` failed: $output")
            val (code, output) = runner(add)
            if code != 0 then throw ApiError(0, s"`${shell(add)}` failed: $output")
            s"registered ankka with Claude Code for this user: ${shell(add)}"

  private def report(outcome: Outcome, file: String, text: String, dryRun: Boolean): String =
    outcome match
      case Outcome.Unchanged => s"$file already starts ankka mcp; nothing to change"
      case Outcome.Kept(existing) =>
        s"""$file already has a server named '$ServerName', different from this one; left as it is:
           |
           |  ${existing.render}
           |
           |Pass --force to replace it.""".stripMargin
      case Outcome.Added | Outcome.Replaced =>
        val verb = if outcome == Outcome.Added then "added ankka to" else "replaced ankka in"
        if dryRun then s"would write $file:\n\n$text" else s"$verb $file"

  /** A command line a person can paste, quoting only what a shell would split. */
  def shell(argv: Vector[String]): String =
    argv
      .map(arg =>
        if arg.matches("[A-Za-z0-9_./:=@+-]+") then arg else s"'${arg.replace("'", "'\\''")}'"
      )
      .mkString(" ")
