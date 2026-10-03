package com.thinkmorestupidless.ankka.cli

import com.github.plokhotnyuk.jsoniter_scala.core.{JsonValueCodec, writeToString}
import com.thinkmorestupidless.ankka.controlplane.api.*
import com.thinkmorestupidless.ankka.controlplane.api.Wire.given

/** How results are printed. */
enum Format:
  case Table
  case Json

/**
 * Renders results for a terminal, or verbatim for a pipe.
 *
 * The table is padded from the widest cell rather than fixed widths, because an image reference is
 * routinely longer than the column header and truncating it hides exactly the part that differs
 * between two deployments.
 */
object Output:

  def organizations(rows: Vector[OrganizationSummary], format: Format): String =
    format match
      case Format.Json => writeToString(rows)
      case Format.Table =>
        table(
          Vector("ID", "NAME", "PROJECTS", "SERVICES", "INSTANCES", "QUOTA", "ROLE", "STATE"),
          rows.map(row =>
            Vector(
              row.id,
              row.name,
              row.projects.toString,
              row.usage.services.toString,
              row.usage.instances.toString,
              row.quota.fold("-")(quotaCell),
              row.role.fold("-")(Role.name),
              if row.disabled then "disabled" else "active"
            )
          )
        )

  def organization(row: OrganizationSummary, format: Format): String =
    format match
      case Format.Json  => writeToString(row)
      case Format.Table => organizations(Vector(row), format)

  /** `projects/services/instances`, `-` for a limit not set — the column a listing scans. */
  private def quotaCell(quota: Quota): String =
    Vector(quota.projects, quota.services, quota.instances)
      .map(_.fold("-")(_.toString))
      .mkString("/")

  /** The quota in words, for a confirmation: `projects 2, instances 4`. */
  def quota(quota: Quota): String =
    Vector(
      "projects"  -> quota.projects,
      "services"  -> quota.services,
      "instances" -> quota.instances
    )
      .collect { case (name, Some(limit)) => s"$name $limit" }
      .mkString(", ")

  def projects(rows: Vector[ProjectSummary], format: Format): String =
    format match
      case Format.Json => writeToString(rows)
      case Format.Table =>
        table(
          Vector("ID", "NAME", "ORGANIZATION", "SERVICES", "REGISTRY"),
          rows.map(row =>
            Vector(
              row.id,
              row.name,
              row.organizationId,
              row.services.toString,
              // Who the credential belongs to, not when it was set: a listing is scanned, and the
              // one thing worth seeing at a glance is whether a project pulls from somewhere that
              // needs one.
              row.registry.fold("-")(r => s"${r.server} as ${r.username}")
            )
          )
        )

  def project(row: ProjectSummary, format: Format): String =
    format match
      case Format.Json  => writeToString(row)
      case Format.Table =>
        // Fields rather than a one-row table, for the reason a single service is shown that way:
        // a registry line carries who set it and when, and that does not fit in a column.
        val fields = Vector(
          "id"           -> row.id,
          "name"         -> row.name,
          "organization" -> row.organizationId,
          "services"     -> row.services.toString,
          "registry"     -> registry(row)
        )
        val width = fields.map(_._1.length).max
        fields.map((label, value) => s"${label.padTo(width, ' ')}  $value").mkString("\n")

  /** `ghcr.io as octocat, set 2026-09-25 by sam@example.com`, or `none`. */
  private def registry(row: ProjectSummary): String =
    row.registry.fold("none") { r =>
      val set = r.setAt.map(at => s", set ${at.atZone(java.time.ZoneOffset.UTC).toLocalDate}")
      val by  = r.setBy.map(who => s" by $who")
      s"${r.server} as ${r.username}${set.getOrElse("")}${by.getOrElse("")}"
    }

  def services(rows: Vector[ServiceStatus], format: Format): String =
    format match
      case Format.Json => writeToString(rows)
      case Format.Table =>
        table(
          Vector("NAME", "STATUS", "INSTANCES", "GEN", "IMAGE", "HOSTNAME"),
          rows.map { row =>
            Vector(
              row.name,
              status(row),
              s"${row.readyInstances}/${row.desiredInstances}",
              row.generation.toString,
              row.image,
              row.hostname.getOrElse("-")
            )
          }
        )

  /**
   * The lifecycle, marked when it is not a confirmed reading.
   *
   * Rendered into the STATUS column rather than left to `detail`, because the table drops `detail`
   * entirely — and the table is what an operator scans. A stale `Ready` that looks identical to a
   * live one is the failure this whole field exists to prevent.
   */
  private def status(row: ServiceStatus): String =
    if row.confirmed then row.lifecycle.toString else s"${row.lifecycle} (unconfirmed)"

  /** Always a row: a service that is private should say so, not show nothing. */
  private def hostname(row: ServiceStatus): String =
    (row.exposed, row.hostname) match
      case (_, Some(url)) => url
      case (true, None)   => "exposed, but the control plane has no base domain (ANKKA_BASE_DOMAIN)"
      case (false, None)  => "not exposed"

  def service(row: ServiceStatus, format: Format): String =
    format match
      case Format.Json  => writeToString(row)
      case Format.Table =>
        // A single service is shown as fields rather than a one-row table: `detail` is
        // the reason a deployment is stuck, and it does not fit in a column.
        val fields = Vector(
          "name"       -> row.name,
          "project"    -> row.projectId,
          "status"     -> status(row),
          "instances"  -> s"${row.readyInstances}/${row.desiredInstances}",
          "generation" -> row.generation.toString,
          "image"      -> row.image,
          "hosting"    -> row.hosting,
          "hostname"   -> hostname(row)
        ) ++ row.protocol.map("protocol" -> _) ++ row.database.map("database" -> _) ++
          row.detail.map("detail" -> _)
        val width = fields.map(_._1.length).max
        fields.map((label, value) => s"${label.padTo(width, ' ')}  $value").mkString("\n")

  /**
   * Shows the effective settings with the token redacted in both formats.
   *
   * A config listing is the sort of thing that ends up in a screen share or a bug report, so
   * `--output json` here is not round-trippable on purpose.
   */
  def settings(current: Settings, format: Format, loginSaved: Boolean = false): String =
    val redacted = current.copy(token = current.token.map(_ => "(set)"))
    format match
      case Format.Json => writeToString(redacted)(using settingsCodec)
      case Format.Table =>
        Vector(
          "url     " -> current.url,
          "token   " -> current.token.fold("(unset)")(_ => "(set)"),
          "login   " -> (if loginSaved then "saved" else "none"),
          "project " -> current.project.getOrElse("(unset)"),
          "ca      " -> current.ca.getOrElse("(unset)")
        ).map((label, value) => s"$label $value").mkString("\n")

  def history(entries: Vector[HistoryEntry], format: Format): String =
    format match
      case Format.Json => writeToString(entries)
      case Format.Table =>
        table(
          Vector("WHEN", "KIND", "GEN", "BY"),
          entries.map(e =>
            Vector(
              e.at.fold("-")(_.toString),
              e.kind,
              e.generation.toString,
              e.actor.fold("-")(a =>
                a.display.getOrElse(a.subject) + (if a.administrative then " (admin)" else "")
              )
            )
          )
        )

  def members(response: MembersResponse, format: Format): String =
    format match
      case Format.Json => writeToString(response)
      case Format.Table =>
        val members = table(
          Vector("SUBJECT", "ROLE", "EMAIL", "SINCE", "ADDED BY"),
          response.members.map(m =>
            Vector(
              m.subject,
              Role.name(m.role),
              m.email.getOrElse("-"),
              m.since.fold("-")(_.toString),
              m.addedBy.getOrElse("-")
            )
          )
        )
        val invitations =
          if response.invitations.isEmpty then "no pending invitations"
          else
            table(
              Vector("EMAIL", "ROLE", "INVITED", "BY"),
              response.invitations.map(i =>
                Vector(
                  i.email,
                  Role.name(i.role),
                  i.invitedAt.fold("-")(_.toString),
                  i.invitedBy.getOrElse("-")
                )
              )
            )
        s"$members\n\n$invitations"

  /**
   * The one time a secret is ever printed.
   *
   * Deliberately loud and deliberately final: there is no route that returns it again, so a reader
   * who skims past this has lost it. The JSON form is the document the control plane sent, for a
   * script that is going to store it somewhere.
   */
  def deployTokenCreated(token: DeployTokenCreated, format: Format): String =
    format match
      case Format.Json => writeToString(token)
      case Format.Table =>
        val expiry = token.expiresAt.fold("It never expires.")(at => s"Expires $at.")
        s"""Deploy token '${token.label}' created. This is the only time the secret is shown.
           |
           |  ${token.secret}
           |
           |$expiry Store it as a secret named ANKKA_TOKEN.""".stripMargin

  def deployTokens(tokens: Vector[DeployTokenSummary], format: Format): String =
    format match
      case Format.Json                    => writeToString(tokens)
      case Format.Table if tokens.isEmpty => "no deploy tokens"
      case Format.Table =>
        table(
          Vector("ID", "LABEL", "CREATED", "BY", "EXPIRES", "LAST USED"),
          tokens.map(t =>
            Vector(
              t.id,
              t.label,
              t.createdAt.fold("-")(_.toString),
              t.createdBy.getOrElse("-"),
              // "never" is a fact about the token, not a missing value, so it is not "-".
              t.expiresAt.fold("never")(_.toString),
              t.lastUsed.fold("-")(_.toString)
            )
          )
        )

  def whoami(who: Whoami, format: Format): String =
    format match
      case Format.Json => writeToString(who)
      case Format.Table =>
        val identity = Vector(
          "subject        " -> who.subject,
          "name           " -> who.name.getOrElse("(none)"),
          "email          " -> who.email.fold("(none)")(e =>
            s"$e (${if who.emailVerified then "verified" else "unverified"})"
          ),
          "platform admin " -> (if who.platformAdmin then "yes" else "no")
        ).map((label, value) => s"$label $value").mkString("\n")
        val organizations =
          if who.organizations.isEmpty then "no organizations"
          else
            table(
              Vector("ORGANIZATION", "NAME", "ROLE"),
              who.organizations.map(o => Vector(o.id, o.name, Role.name(o.role)))
            )
        s"$identity\n\n$organizations"

  private val settingsCodec: JsonValueCodec[Settings] =
    com.thinkmorestupidless.ankka.core.Codecs.make[Settings]

  private def table(headers: Vector[String], rows: Vector[Vector[String]]): String =
    if rows.isEmpty then "no results"
    else
      val widths = headers.indices.map { column =>
        (headers(column).length +: rows.map(_(column).length)).max
      }
      val rendered = (headers +: rows).map { cells =>
        cells.zip(widths).map((cell, width) => cell.padTo(width, ' ')).mkString("  ").stripTrailing
      }
      rendered.mkString("\n")

  /**
   * A deployed service's topology: its parts by layer, what feeds what, and who called whom, with
   * the handled and the unanswered counts in separate columns because they are different facts. A
   * partial result names the instances that did not contribute; the command still answers what it
   * could.
   */
  def topology(response: ServiceTopology, format: Format): String =
    format match
      case Format.Json => writeToString(response)
      case Format.Table =>
        val instances =
          s"${response.contributing} of ${response.running} instance" +
            (if response.running == 1 then "" else "s") + " answered"
        val missing = response.instances.filter(_.status != InstanceStatus.Ok).map { i =>
          s"  ${i.pod}: ${i.status.wire}${i.problem.fold("")(p => s" ($p)")}"
        }
        val nodes = table(
          Vector("LAYER", "COMPONENT", "KIND", "HANDLERS"),
          response.nodes.sortBy(n => (n.layer, n.id)).map { n =>
            val onSome = response.differences.find(_.node == n.id)
            Vector(
              n.layer.toString,
              n.id,
              n.kind + (if n.platform then " (platform)" else "") +
                onSome.fold("")(d => s" [on ${d.presentOn.mkString(", ")} only]"),
              n.handlers.map(_.name).mkString(", ")
            )
          }
        )
        val declared =
          if response.declared.isEmpty then "no declared connections"
          else
            table(
              Vector("FROM", "TO", "AS"),
              response.declared.map(e => Vector(e.from, e.to, e.kind))
            )
        val calls =
          if response.calls.isEmpty then "no observed calls in the window"
          else
            table(
              Vector(
                "FROM",
                "TO",
                "HANDLER -> HANDLER",
                "OK",
                "REFUSED",
                "FAILED",
                "TIMED OUT",
                "UNDELIVERED",
                "P50/P99/MAX MS"
              ),
              response.calls.flatMap(c =>
                c.pairs.map(p =>
                  Vector(
                    c.from,
                    c.to,
                    s"${p.caller} -> ${p.callee}" + (if p.streaming then " (stream)" else ""),
                    p.handled.ok.toString,
                    p.handled.refused.toString,
                    p.handled.failed.toString,
                    p.unanswered.timedOut.toString,
                    p.unanswered.undelivered.toString,
                    s"~${p.durationMillis.p50}/${p.durationMillis.p99}/${p.durationMillis.max}"
                  )
                )
              )
            )
        val window =
          s"Calls observed in the last ${response.window.seconds / 60} minutes (since " +
            s"${response.window.since}); calls are observed, not complete."
        val partial =
          if response.partial then
            s"\nPartial: ${response.running - response.contributing} instance(s) did not " +
              "contribute:\n" + missing.mkString("\n")
          else ""
        s"""$instances$partial
           |
           |Components
           |$nodes
           |
           |Declared connections
           |$declared
           |
           |Observed calls
           |$calls
           |
           |$window""".stripMargin

  /**
   * A service's output, unwrapped.
   *
   * Lines go out exactly as the service produced them, with no decoration: a developer piping this
   * to `grep` must be searching their own output, not a rendering of it. The instance prefix
   * appears only when there is more than one, because a prefix on every line of a single-instance
   * service is noise that breaks every grep for a line start.
   */
  def logs(response: LogsResponse, format: Format): String =
    format match
      case Format.Json => writeToString(response)
      case Format.Table =>
        val many = response.instances.size > 1
        response.instances
          .map { instance =>
            val body = instance.error match
              // Reported inline rather than thrown: one unreadable instance must not cost the
              // reader the output of the others.
              case Some(problem)                   => s"($problem)"
              case None if instance.output.isEmpty => "(no output)"
              case None                            => instance.output.stripLineEnd
            if many then
              val prefix = s"${instance.instance}: "
              s"$prefix${body.linesIterator.mkString(s"\n$prefix")}"
            else body
          }
          .mkString("\n")
