package com.thinkmorestupidless.ankka.cli.mcp

import com.github.plokhotnyuk.jsoniter_scala.core.{
  JsonValueCodec,
  WriterConfig,
  readFromString,
  writeToString
}
import com.thinkmorestupidless.ankka.cli.console.{InvokeRequest, LocalSource, Source}
import com.thinkmorestupidless.ankka.cli.{ControlPlaneClient, Settings}
import com.thinkmorestupidless.ankka.controlplane.api.*
import com.thinkmorestupidless.ankka.controlplane.api.Wire.given

/**
 * What `ankka mcp` offers: the CLI's own verbs, the local console's view of this machine, and the
 * documentation.
 *
 * Every control plane tool is one `ControlPlaneClient` call, the same one the matching command
 * makes, so a model driving this server can do exactly what its user could type and nothing more —
 * the control plane's authorization applies to the token either way. Tenancy administration
 * (creating organizations, managing members, disabling) is left to the command line on purpose: it
 * is rare, it is a person's decision, and a tool that exists is a tool a model may choose.
 *
 * The local tools read what the local console reads. A call to a local service's endpoint goes to
 * that service's own HTTP port as an ordinary client, so its ACL applies exactly as it does to
 * `curl`.
 */
private[cli] final class AnkkaTools(
    settings: () => Settings,
    local: () => Source = () => LocalSource()
):

  private def client = ControlPlaneClient(settings())

  private def json[A](value: A)(using JsonValueCodec[A]): String =
    writeToString(value, WriterConfig.withIndentionStep(2))

  private def project(arguments: Json): String =
    arguments
      .string("project")
      .orElse(settings().project)
      .getOrElse(
        throw IllegalArgumentException(
          "no project: pass `project`, or run `ankka config set project <id>` where this server runs"
        )
      )

  private def required(arguments: Json, field: String): String =
    arguments
      .string(field)
      .filter(_.nonEmpty)
      .getOrElse(throw IllegalArgumentException(s"`$field` is required"))

  // ── Schemas ─────────────────────────────────────────────────────────────

  private def schema(required: Seq[String], properties: (String, Json)*): Json =
    Json.obj(
      "type"                 -> Json.str("object"),
      "properties"           -> Json.Obj(properties.toVector),
      "required"             -> Json.Arr(required.map(Json.str).toVector),
      "additionalProperties" -> Json.bool(false)
    )

  private def string(description: String): Json =
    Json.obj("type" -> Json.str("string"), "description" -> Json.str(description))

  private def integer(description: String): Json =
    Json.obj("type" -> Json.str("integer"), "description" -> Json.str(description))

  private def boolean(description: String): Json =
    Json.obj("type" -> Json.str("boolean"), "description" -> Json.str(description))

  private val projectArg =
    "project" -> string(
      "Project id. Defaults to the project configured with `ankka config set project`."
    )

  private val serviceArg = "name" -> string("The service's name, as in its descriptor.")

  // ── Control plane ───────────────────────────────────────────────────────

  private def serviceAction(
      toolName: String,
      title: String,
      description: String,
      destructive: Boolean,
      idempotent: Boolean
  )(call: (ControlPlaneClient, String, String) => ServiceStatus): Tool =
    Tool(
      toolName,
      title,
      description,
      schema(Seq("name"), serviceArg, projectArg),
      readOnly = false,
      destructive = destructive,
      idempotent = idempotent
    )(args => ToolResult(json(call(client, project(args), required(args, "name")))))

  private val controlPlane: Vector[Tool] = Vector(
    Tool(
      "whoami",
      "Who am I",
      "Who the control plane thinks the caller is: subject, name, email, organizations and roles. Use it to check the login works.",
      schema(Nil),
      readOnly = true,
      idempotent = true
    )(_ => ToolResult(json(client.whoami()))),
    Tool(
      "list_organizations",
      "List organizations",
      "The organizations the caller belongs to, with the caller's role in each and whether it is active.",
      schema(Nil),
      readOnly = true,
      idempotent = true
    )(_ => ToolResult(json(client.listOrganizations()))),
    Tool(
      "list_projects",
      "List projects",
      "Projects the caller can see, optionally in one organization, with how many services each holds.",
      schema(
        Nil,
        "organization" -> string("Organization id to list projects in. Omit for every one.")
      ),
      readOnly = true,
      idempotent = true
    )(args => ToolResult(json(client.listProjects(args.string("organization"))))),
    Tool(
      "list_services",
      "List services",
      "Every service in a project with its lifecycle state, ready and desired instances, generation and image.",
      schema(Nil, projectArg),
      readOnly = true,
      idempotent = true
    )(args => ToolResult(json(client.listServices(project(args))))),
    Tool(
      "get_service",
      "Get a service",
      "One service's full status: lifecycle, instances, generation, image, hosting, database, hostname if exposed, and detail explaining a state that is not Ready. `confirmed: false` means the control plane is restating what it last knew.",
      schema(Seq("name"), serviceArg, projectArg),
      readOnly = true,
      idempotent = true
    )(args => ToolResult(json(client.getService(project(args), required(args, "name"))))),
    Tool(
      "service_history",
      "Service history",
      "Who did what to a service and when, newest first: every apply, pause, resume, restart, expose and delete, with the actor.",
      schema(Seq("name"), serviceArg, projectArg),
      readOnly = true,
      idempotent = true
    )(args => ToolResult(json(client.serviceHistory(project(args), required(args, "name"))))),
    Tool(
      "service_logs",
      "Service logs",
      "A deployed service's recent output, per instance, as Kubernetes holds it. Use `previous` after a restart: the container before it is usually where the answer is.",
      schema(
        Seq("name"),
        serviceArg,
        projectArg,
        "instance" -> string("One instance (pod) name. Omit for every instance."),
        "previous" -> boolean("The container before the last restart."),
        "tail"     -> integer("Only the last N lines."),
        "since"    -> integer("Only the last N seconds.")
      ),
      readOnly = true,
      idempotent = true
    ) { args =>
      val response = client.serviceLogs(
        project(args),
        required(args, "name"),
        args.string("instance"),
        args.bool("previous").getOrElse(false),
        args.int("tail"),
        args.int("since")
      )
      ToolResult(
        response.instances
          .map { i =>
            val problem = i.error.map(e => s"\n(error: $e)").getOrElse("")
            s"── ${i.instance} ──\n${i.output}$problem"
          }
          .mkString("\n")
      )
    },
    Tool(
      "apply_service",
      "Apply a service descriptor",
      "Create or update a service from its descriptor — its desired state. The descriptor is validated with the platform's own rules before it is sent. Increments the service's generation and rolls its instances if anything Kubernetes sees changed.",
      schema(
        Seq("descriptor"),
        "descriptor" -> Json.obj(
          "type" -> Json.str("object"),
          "description" -> Json.str(
            "The service descriptor, as in service.json: {\"name\": ..., \"service\": {\"image\": ..., ...}}."
          )
        ),
        projectArg
      ),
      readOnly = false,
      destructive = true,
      idempotent = true
    ) { args =>
      val text = args("descriptor") match
        case Some(Json.Str(raw)) => raw
        case Some(value)         => value.render
        case None                => throw IllegalArgumentException("`descriptor` is required")
      val descriptor =
        try readFromString[ServiceDescriptor](text)
        catch
          case e: Exception =>
            throw IllegalArgumentException(s"not a service descriptor: ${e.getMessage}")
      descriptor.problems match
        case problems if problems.nonEmpty =>
          ToolResult(problems.mkString("invalid descriptor:\n  - ", "\n  - ", ""), isError = true)
        case _ => ToolResult(json(client.applyService(project(args), descriptor)))
    },
    serviceAction(
      "expose_service",
      "Expose a service",
      "Make a service reachable from outside the cluster at its platform-derived https hostname. Exposure changes who can reach it, not who may call it: the endpoints' ACLs still apply.",
      destructive = false,
      idempotent = true
    )(_.exposeService(_, _)),
    serviceAction(
      "unexpose_service",
      "Unexpose a service",
      "Remove a service's external route. Nothing else about the service changes.",
      destructive = true,
      idempotent = true
    )(_.unexposeService(_, _)),
    serviceAction(
      "pause_service",
      "Pause a service",
      "Stop every instance of a service, keeping its descriptor, data and hostname. `resume_service` starts it again.",
      destructive = true,
      idempotent = true
    )(_.pauseService(_, _)),
    serviceAction(
      "resume_service",
      "Resume a service",
      "Start a paused service again at its current descriptor.",
      destructive = false,
      idempotent = true
    )(_.resumeService(_, _)),
    serviceAction(
      "restart_service",
      "Restart a service",
      "Replace every instance of a service, one at a time, with no downtime.",
      destructive = false,
      idempotent = false
    )(_.restartService(_, _)),
    Tool(
      "delete_service",
      "Delete a service",
      "Delete a service: its instances stop and its route goes. Its database is kept, and applying a descriptor with the same name recovers its data.",
      schema(Seq("name"), serviceArg, projectArg),
      readOnly = false,
      destructive = true,
      idempotent = true
    ) { args =>
      val name = required(args, "name")
      client.deleteService(project(args), name)
      ToolResult(s"service '$name' deleted")
    }
  )

  // ── This machine ────────────────────────────────────────────────────────

  private val serviceOnMachine =
    "service" -> string("The local service's name, as `list_local_services` shows it.")

  private def found(what: String, value: Option[String]): ToolResult =
    value
      .map(ToolResult(_))
      .getOrElse(ToolResult(s"no running local service or $what by that name", isError = true))

  private val machine: Vector[Tool] = Vector(
    Tool(
      "list_local_services",
      "List local services",
      "Every ankka service running on this machine (started with `sbt run` or as a Python process beside a local sidecar), with where its observability endpoint listens. Nothing here is deployed.",
      schema(Nil),
      readOnly = true,
      idempotent = true
    ) { _ =>
      val services = local().services()
      if services.isEmpty then ToolResult("no ankka service is running on this machine")
      else
        ToolResult(
          services
            .map(s =>
              s"${s.name}  instance ${s.instanceId}  started ${s.startedAt}  ${s.observabilityAddress}"
            )
            .mkString("\n")
        )
    },
    Tool(
      "describe_local_service",
      "Describe a local service",
      "A local service's runtime version, instances with their HTTP address, registered components (kind, id, declared queries) and HTTP routes.",
      schema(Seq("service"), serviceOnMachine),
      readOnly = true,
      idempotent = true
    )(args => found("service", local().service(required(args, "service")))),
    Tool(
      "local_traces",
      "Local traces",
      "A local service's recent traces, or one trace in full: which components a request went through, how long each took, and how much time the platform could not attribute. The window is a fixed ring of recent spans; a partial trace had older spans overwritten.",
      schema(
        Seq("service"),
        serviceOnMachine,
        "trace_id" -> string("One trace's id, from the listing. Omit for the listing.")
      ),
      readOnly = true,
      idempotent = true
    ) { args =>
      val service = required(args, "service")
      args.string("trace_id") match
        case Some(id) => found("trace", local().trace(service, id))
        case None     => found("service", local().traces(service))
    },
    Tool(
      "query_local_entity",
      "Query a local entity",
      "Run one of a component's declared query handlers against an entity id and return its answer. Only queries: a handler declared as a command is refused by the service.",
      schema(
        Seq("service", "component", "id", "query"),
        serviceOnMachine,
        "component" -> string("The component id, e.g. `shopping-cart`."),
        "id"        -> string("The entity id."),
        "query"     -> string("The query handler's wire name, e.g. `get-cart`.")
      ),
      readOnly = true,
      idempotent = true
    ) { args =>
      local().query(
        required(args, "service"),
        required(args, "component"),
        required(args, "id"),
        required(args, "query")
      ) match
        case None => ToolResult("no running local service by that name", isError = true)
        case Some(response) =>
          ToolResult(s"${response.status}\n${response.body}", isError = response.status >= 400)
    },
    Tool(
      "call_local_endpoint",
      "Call a local endpoint",
      "Send an HTTP request to a local service's own port as an ordinary client, and return the status, headers and body. The endpoint's ACL applies. May change the service's state.",
      schema(
        Seq("service", "method", "path"),
        serviceOnMachine,
        "method" -> string("GET, POST, PUT, PATCH or DELETE."),
        "path"   -> string("The request path with parameters filled in, e.g. `/carts/c1/items`."),
        "body"   -> string("The request body, usually JSON."),
        "headers" -> Json.obj(
          "type"                 -> Json.str("object"),
          "description"          -> Json.str("Extra request headers."),
          "additionalProperties" -> Json.obj("type" -> Json.str("string"))
        )
      ),
      readOnly = false,
      destructive = true
    ) { args =>
      val headers = args("headers") match
        case Some(Json.Obj(fields)) => fields.collect { case (k, Json.Str(v)) => k -> v }
        case _                      => Vector.empty
      val body = args.string("body")
      val withType =
        if body.isDefined && !headers.exists(_._1.equalsIgnoreCase("content-type")) then
          headers :+ ("Content-Type" -> "application/json")
        else headers
      val request =
        InvokeRequest(required(args, "method").toUpperCase, required(args, "path"), withType, body)
      local().invoke(required(args, "service"), request) match
        case None => ToolResult("no running local service by that name", isError = true)
        case Some(response) =>
          val shown = response.headers.map((k, v) => s"$k: $v").mkString("\n")
          ToolResult(
            s"${response.status}\n$shown\n\n${response.body}",
            isError = response.status >= 400
          )
    },
    Tool(
      "local_agent_session",
      "Local agent session",
      "An agent session's stored conversation and the tokens it has cost, by session id.",
      schema(
        Seq("service", "session_id"),
        serviceOnMachine,
        "session_id" -> string("The session id the application used.")
      ),
      readOnly = true,
      idempotent = true
    )(args =>
      found("session", local().session(required(args, "service"), required(args, "session_id")))
    )
  )

  // ── Documentation ───────────────────────────────────────────────────────

  private val documentation: Vector[Tool] = Vector(
    Tool(
      "search_docs",
      "Search the documentation",
      "Find documentation pages for this ankka version by keywords. Returns each page's path, title and one-sentence description; read one with `read_doc`.",
      schema(
        Seq("query"),
        "query" -> string("Keywords, e.g. `view sql query` or `expose hostname`.")
      ),
      readOnly = true,
      idempotent = true
    ) { args =>
      val pages = Docs.search(required(args, "query"), 8)
      if pages.isEmpty then ToolResult("no page matches; `read_doc` with `index.md` shows the map")
      else ToolResult(pages.map(p => s"${p.path} — ${p.title}: ${p.description}").mkString("\n"))
    },
    Tool(
      "read_doc",
      "Read a documentation page",
      "One documentation page, whole, as Markdown. Samples in it are copied from code the ankka build compiles and tests.",
      schema(
        Seq("path"),
        "path" -> string("The page's path, e.g. `build/views.md` or `reference/cli.md`.")
      ),
      readOnly = true,
      idempotent = true
    ) { args =>
      val path = required(args, "path").stripPrefix("/").stripPrefix("ankka://docs/")
      Docs.read(path) match
        case Some(text) => ToolResult(text)
        case None       => ToolResult(s"no page '$path'; `search_docs` finds pages", isError = true)
    }
  )

  val all: Vector[Tool] = controlPlane ++ machine ++ documentation

  def resources(): Vector[Resource] =
    Docs.pages.map { page =>
      Resource(page.uri, page.path, page.title, page.description, "text/markdown")(() =>
        Docs.read(page.path).getOrElse("")
      )
    }

private[cli] object AnkkaTools:

  val Instructions: String =
    """Tools for ankka, a platform for services built from entities, views, consumers, workflows, timers, agents and HTTP endpoints, in Scala or Python.
      |
      |Control plane tools act on deployed services in a project, as the logged-in user (`ankka login`). Local tools act on services running on this machine. `search_docs` and `read_doc` read the documentation of this ankka version; read the relevant page before writing ankka code or a descriptor, because its samples are compiled and tested.""".stripMargin
