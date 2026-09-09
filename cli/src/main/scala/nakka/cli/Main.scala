package nakka.cli

import cats.data.Validated
import cats.syntax.all.*
import com.github.plokhotnyuk.jsoniter_scala.core.readFromString
import com.monovore.decline.{Command, Opts}
import nakka.controlplane.api.*
import nakka.controlplane.api.Wire.given

import java.io.PrintStream
import java.nio.file.{Files, Paths}

/**
 * The `nakka` command-line client.
 *
 * Every command is the same three steps: resolve settings, make one HTTP call, print. The CLI holds
 * no state beyond `~/.nakka/config.json` and makes no decisions the control plane could make
 * instead — a client that second-guesses the server is a client that disagrees with it after the
 * next upgrade.
 */
object Main:

  // ── Global options ────────────────────────────────────────────────────────

  private val urlOpt = Opts
    .option[String]("url", "Control plane base URL. Defaults to the configured value.")
    .orNone

  private val tokenOpt = Opts
    .option[String]("token", "Bearer token. Prefer NAKKA_TOKEN or the config file.")
    .orNone

  private val projectOpt = Opts
    .option[String]("project", "Project id. Defaults to the configured project.", short = "p")
    .orNone

  private val formatOpt = Opts
    .option[String]("output", "Output format: table or json.", short = "o")
    .mapValidated {
      case "table" => Validated.valid(Format.Table)
      case "json"  => Validated.valid(Format.Json)
      case other   => Validated.invalidNel(s"unknown output format '$other'; use table or json")
    }
    .withDefault(Format.Table)

  private val nameOpt = Opts.option[String]("name", "Display name.")

  private val organizationOpt =
    Opts.option[String]("organization", "Organization id.", short = "O")

  /** Settings plus a format, which is what every command actually needs. */
  private final case class Context(settings: Settings, format: Format):
    def client: ControlPlaneClient = ControlPlaneClient(settings)

    /**
     * The project to act on, or a usage failure.
     *
     * Failing here rather than sending an empty project id means the error names the fix —
     * `--project` or `nakka config set project` — instead of surfacing as a 404 from the server.
     */
    def project: String = settings.project.getOrElse(
      throw ApiError(
        0,
        "no project selected; pass --project <id> or run `nakka config set project <id>`"
      )
    )

  private val contextOpt: Opts[Context] =
    (urlOpt, tokenOpt, projectOpt, formatOpt).mapN { (url, token, project, format) =>
      Context(Settings.resolve(url, token, project), format)
    }

  // ── organizations ─────────────────────────────────────────────────────────

  private val organizationsCommand = Opts.subcommand(
    "organizations",
    "Manage organizations."
  ) {
    val list = Opts.subcommand("list", "List every organization.") {
      contextOpt.map(ctx => () => Output.organizations(ctx.client.listOrganizations(), ctx.format))
    }

    val get = Opts.subcommand("get", "Show one organization.") {
      (Opts.argument[String]("id"), contextOpt).mapN { (id, ctx) => () =>
        Output.organization(ctx.client.getOrganization(id), ctx.format)
      }
    }

    val create = Opts.subcommand("create", "Create an organization.") {
      (Opts.argument[String]("id"), nameOpt, contextOpt).mapN { (id, name, ctx) => () =>
        ctx.client.createOrganization(id, name)
        s"organization '$id' created"
      }
    }

    val rename = Opts.subcommand("rename", "Change an organization's display name.") {
      (Opts.argument[String]("id"), nameOpt, contextOpt).mapN { (id, name, ctx) => () =>
        ctx.client.renameOrganization(id, name)
        s"organization '$id' renamed to '$name'"
      }
    }

    val delete = Opts.subcommand("delete", "Delete an organization. It must have no projects.") {
      (Opts.argument[String]("id"), contextOpt).mapN { (id, ctx) => () =>
        ctx.client.deleteOrganization(id)
        s"organization '$id' deleted"
      }
    }

    list.orElse(get).orElse(create).orElse(rename).orElse(delete)
  }

  // ── projects ──────────────────────────────────────────────────────────────

  private val projectsCommand = Opts.subcommand("projects", "Manage projects.") {
    val list = Opts.subcommand("list", "List projects, optionally in one organization.") {
      (organizationOpt.orNone, contextOpt).mapN { (organization, ctx) => () =>
        Output.projects(ctx.client.listProjects(organization), ctx.format)
      }
    }

    val get = Opts.subcommand("get", "Show one project.") {
      (Opts.argument[String]("id"), contextOpt).mapN { (id, ctx) => () =>
        Output.project(ctx.client.getProject(id), ctx.format)
      }
    }

    val create = Opts.subcommand("create", "Create a project.") {
      (Opts.argument[String]("id"), nameOpt, organizationOpt, contextOpt).mapN {
        (id, name, organization, ctx) => () =>
          ctx.client.createProject(id, name, organization)
          s"project '$id' created in organization '$organization'"
      }
    }

    val rename = Opts.subcommand("rename", "Change a project's display name.") {
      (Opts.argument[String]("id"), nameOpt, contextOpt).mapN { (id, name, ctx) => () =>
        ctx.client.renameProject(id, name)
        s"project '$id' renamed to '$name'"
      }
    }

    val delete = Opts.subcommand("delete", "Delete a project. It must have no services.") {
      (Opts.argument[String]("id"), contextOpt).mapN { (id, ctx) => () =>
        ctx.client.deleteProject(id)
        s"project '$id' deleted"
      }
    }

    list.orElse(get).orElse(create).orElse(rename).orElse(delete)
  }

  // ── services ──────────────────────────────────────────────────────────────

  private val servicesCommand = Opts.subcommand("services", "Manage services.") {
    val list = Opts.subcommand("list", "List the services in a project.") {
      contextOpt.map(ctx => () => Output.services(ctx.client.listServices(ctx.project), ctx.format))
    }

    val get = Opts.subcommand("get", "Show one service.") {
      (Opts.argument[String]("name"), contextOpt).mapN { (name, ctx) => () =>
        Output.service(ctx.client.getService(ctx.project, name), ctx.format)
      }
    }

    val fileOpt = Opts.option[String]("file", "Descriptor file, or '-' for stdin.", short = "f")

    val applyCommand = Opts.subcommand("apply", "Apply a service descriptor.") {
      (fileOpt, contextOpt).mapN { (file, ctx) => () =>
        val descriptor = Descriptors.read(file)
        Output.service(ctx.client.applyService(ctx.project, descriptor), ctx.format)
      }
    }

    val pause = Opts.subcommand("pause", "Stop a service's instances, keeping its descriptor.") {
      (Opts.argument[String]("name"), contextOpt).mapN { (name, ctx) => () =>
        Output.service(ctx.client.pauseService(ctx.project, name), ctx.format)
      }
    }

    val resume = Opts.subcommand("resume", "Start a paused service again.") {
      (Opts.argument[String]("name"), contextOpt).mapN { (name, ctx) => () =>
        Output.service(ctx.client.resumeService(ctx.project, name), ctx.format)
      }
    }

    val restart = Opts.subcommand("restart", "Replace a service's instances.") {
      (Opts.argument[String]("name"), contextOpt).mapN { (name, ctx) => () =>
        Output.service(ctx.client.restartService(ctx.project, name), ctx.format)
      }
    }

    val delete = Opts.subcommand("delete", "Delete a service.") {
      (Opts.argument[String]("name"), contextOpt).mapN { (name, ctx) => () =>
        ctx.client.deleteService(ctx.project, name)
        s"service '$name' deleted"
      }
    }

    list
      .orElse(get)
      .orElse(applyCommand)
      .orElse(pause)
      .orElse(resume)
      .orElse(restart)
      .orElse(delete)
  }

  // ── config ────────────────────────────────────────────────────────────────

  private val configCommand = Opts.subcommand("config", "Read and write the saved settings.") {
    val get = Opts.subcommand("get", "Show the effective settings.") {
      contextOpt.map(ctx => () => Output.settings(ctx.settings, ctx.format))
    }

    val set = Opts.subcommand("set", "Set url, token or project.") {
      (
        Opts.argument[String]("key"),
        Opts.argument[String]("value")
      ).mapN { (key, value) => () =>
        val current = Settings.load()
        val updated = key match
          case "url"     => current.copy(url = value)
          case "token"   => current.copy(token = Some(value))
          case "project" => current.copy(project = Some(value))
          case other =>
            throw ApiError(0, s"unknown setting '$other'; one of url, token, project")
        s"set $key in ${Settings.save(updated)}"
      }
    }

    val unset = Opts.subcommand("unset", "Clear token or project.") {
      Opts.argument[String]("key").map { key => () =>
        val current = Settings.load()
        val updated = key match
          case "token"   => current.copy(token = None)
          case "project" => current.copy(project = None)
          case "url"     => current.copy(url = Settings.DefaultUrl)
          case other =>
            throw ApiError(0, s"unknown setting '$other'; one of url, token, project")
        s"cleared $key in ${Settings.save(updated)}"
      }
    }

    get.orElse(set).orElse(unset)
  }

  private val command = Command(
    name = "nakka",
    header = "Operate a nakka control plane."
  )(organizationsCommand.orElse(projectsCommand).orElse(servicesCommand).orElse(configCommand))

  /**
   * Runs one command and returns the exit code: 0 ok, 1 failed, 2 misused.
   *
   * Separate from `main` so tests can drive the real command tree without the process exiting
   * underneath them — and so the exit codes are themselves testable, which for a CLI used in
   * scripts is part of the contract.
   */
  def run(args: Seq[String], out: PrintStream, err: PrintStream): Int =
    command.parse(args, sys.env) match
      case Left(help) =>
        // Decline prints usage on a parse failure. That is a usage error, not a failure of
        // the operation, so it gets its own exit code.
        err.println(help)
        2

      case Right(action) =>
        try
          out.println(action())
          0
        catch
          case error: ApiError =>
            err.println(s"error: ${error.detail}")
            1

  def main(args: Array[String]): Unit =
    sys.exit(run(args.toIndexedSeq, System.out, System.err))

/** Reads a descriptor from a file or stdin. */
private object Descriptors:

  /**
   * JSON only, for now.
   *
   * Akka's CLI takes YAML. Matching that means a YAML parser on the CLI's classpath, which is a
   * real dependency for a cosmetic difference — the descriptor is the same shape either way. Called
   * out as a divergence rather than hidden.
   */
  def read(file: String): ServiceDescriptor =
    val text =
      if file == "-" then readStdin()
      else
        val path = Paths.get(file)
        if !Files.exists(path) then throw ApiError(0, s"no such descriptor file: $file")
        Files.readString(path)

    val descriptor =
      try readFromString[ServiceDescriptor](text)
      catch
        case error: Exception =>
          throw ApiError(0, s"could not read $file as a service descriptor: ${error.getMessage}")

    // Validated client-side as well as server-side: a typo in a descriptor should be
    // reported before a round trip, and the rules are in the shared module precisely so
    // both ends can apply them.
    descriptor.problems match
      case problems if problems.nonEmpty =>
        throw ApiError(0, problems.mkString(s"invalid descriptor in $file:\n  - ", "\n  - ", ""))
      case _ => descriptor

  /**
   * Reads stdin through `Console.in` rather than `System.in`.
   *
   * `Console.withIn` can redirect the former, which is what lets a test drive `apply -f -` without
   * a subprocess.
   */
  private def readStdin(): String =
    val builder = StringBuilder()
    var line    = Console.in.readLine()
    while line != null do
      builder.append(line).append('\n'): Unit
      line = Console.in.readLine()
    builder.toString
