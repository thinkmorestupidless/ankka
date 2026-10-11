package com.thinkmorestupidless.ankka.cli

import com.github.plokhotnyuk.jsoniter_scala.core.{JsonValueCodec, WriterConfig, writeToString}
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

  /** A project's declared topics, each with how far the platform has got with it (feature 027). */
  def projectTopics(rows: Vector[ProjectTopic], format: Format): String =
    format match
      case Format.Json => writeToString(rows)
      case Format.Table =>
        table(
          Vector("TOPIC", "PARTITIONS", "COMPACTED", "CONTRACT", "PHASE", "DETAIL", "CHECKS"),
          rows.map(row =>
            Vector(
              row.name,
              row.partitions.toString,
              if row.compacted then "yes" else "no",
              row.contract.fold("-")(c => s"${c.name} ${c.fingerprint.take(15)}"),
              row.phase.getOrElse("-"),
              row.detail.getOrElse("-"),
              if row.checks.isEmpty then "-"
              else
                row.checks
                  .map(c =>
                    s"${c.service} ${c.direction}: ${c.state}" +
                      (if c.state == "mismatch" then c.stated.fold(" (none)")(s => s" ($s)")
                       else "")
                  )
                  .mkString("; ")
            )
          )
        )

  /** A project's declared brokers (feature 037). */
  def projectBrokers(rows: Vector[ProjectBroker], format: Format): String =
    format match
      case Format.Json => writeToString(rows)
      case Format.Table =>
        table(
          Vector("BROKER", "BOOTSTRAP", "SHAPE", "SECRET", "DECLARED"),
          rows.map(row =>
            Vector(row.name, row.bootstrap, row.shape, row.secret, row.declaredAt.getOrElse("-"))
          )
        )

  def projectSecrets(rows: Vector[ProjectSecretSummary], format: Format): String =
    format match
      case Format.Json => writeToString(rows)
      case Format.Table =>
        table(
          Vector("NAME", "ENTRIES", "SET", "BY"),
          rows.map(row =>
            Vector(
              row.name,
              row.entries.mkString(", "),
              row.setAt.fold("-")(_.toString),
              row.setBy.getOrElse("-")
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

  /**
   * A project's backups and database (feature 041): a line for the project, a row per line of
   * history, and a line for the database.
   */
  def projectStatus(status: ProjectStatus, format: Format): String =
    format match
      case Format.Json => writeToString(status)
      case Format.Table =>
        val headline =
          if status.backedUp then s"${status.id}: backed up"
          else s"${status.id}: not backed up" + status.detail.fold("")(d => s" ($d)")
        val lines =
          if status.lines.isEmpty then Vector.empty
          else
            table(
              Vector("LINE", "STATUS", "LAST BASE BACKUP", "RESTORABLE FROM", "TO", "LAG"),
              status.lines.map(l =>
                Vector(
                  l.line,
                  l.phase + l.failing.fold("")(f => s": $f"),
                  l.lastBaseBackup.fold("-")(_.toString),
                  l.firstRestorable.fold("-")(_.toString),
                  l.lastRestorable.fold("-")(_.toString),
                  l.archiveLagSeconds.fold("-")(s => f"$s%.0fs")
                )
              )
            ).linesIterator.toVector
        val database = status.database.toVector.map { d =>
          s"database ${d.cluster}: ${d.readyInstances}/${d.instances} ready" +
            d.primary.fold("")(p => s", primary $p") +
            (if d.synchronous then ", synchronous" else "") +
            d.writesWaitingOn.fold("")(w => s" (writes waiting: $w)")
        }
        (headline +: (lines ++ database)).mkString("\n")

  /** One restore (feature 041): its fields, then a row per service's database. */
  def restore(view: RestoreView, format: Format): String =
    format match
      case Format.Json => writeToString(view)
      case Format.Table =>
        val fields = Vector(
          "name"   -> view.name,
          "line"   -> view.line,
          "moment" -> view.moment.toString,
          "phase"  -> (view.phase + view.detail.fold("")(d => s": $d")),
          "requested" -> (view.requestedAt.fold("-")(_.toString) + view.requestedBy.fold("")(b =>
            s" by $b"
          )),
          "reached" -> view.reachedAt.fold("-")(_.toString)
        )
        val width = fields.map(_._1.length).max
        val head  = fields.map((label, value) => s"${label.padTo(width, ' ')}  $value")
        val rows =
          if view.services.isEmpty then Vector.empty
          else
            "" +: table(
              Vector(
                "SERVICE",
                "PRESENT",
                "JOURNAL",
                "STATES",
                "OFFSETS",
                "TIMERS",
                "HIGHEST SEQ",
                "SECRETS CHANGED"
              ),
              view.services.map(v =>
                Vector(
                  v.name,
                  if v.present then "yes" else "no",
                  v.journalRows.toString,
                  v.stateRows.toString,
                  v.offsetRows.toString,
                  v.timerRows.toString,
                  v.highestSequence.toString,
                  if v.changedSecrets.isEmpty then "-" else v.changedSecrets.mkString(", ")
                )
              )
            ).linesIterator.toVector
        // What the restore cannot take back: the broker, as the restore's services say it.
        val broker =
          if view.broker.isEmpty then Vector.empty
          else
            "" +: table(
              Vector("TOPIC", "GROUP", "NEWER THAN THE MOMENT", "READ BY THE GROUP", "SERVICES"),
              view.broker.map(e =>
                Vector(
                  e.topic,
                  e.group.getOrElse("-"),
                  e.after.toString,
                  e.read.fold("-")(_.toString),
                  e.services.mkString(", ")
                )
              )
            ).linesIterator.toVector
        val unasked =
          if view.notAsked.isEmpty then Vector.empty
          else Vector("", s"not asked about the broker: ${view.notAsked.mkString(", ")}")
        val note = view.note.toVector.flatMap(n => Vector("", n))
        (head ++ rows ++ broker ++ unasked ++ note).mkString("\n")

  def rehearsal(view: RehearsalView, format: Format): String =
    format match
      case Format.Json => writeToString(view)
      case Format.Table =>
        s"rehearsal ${view.name} of ${view.line} at ${view.moment}: ${view.outcome}" +
          view.elapsedSeconds.fold("")(s => s", in ${s}s") + view.detail.fold("")(d => s" ($d)")

  def rehearsals(views: Vector[RehearsalView], format: Format): String =
    format match
      case Format.Json => writeToString(views)
      case Format.Table =>
        table(
          Vector("NAME", "MOMENT", "OUTCOME", "TOOK", "REQUESTED", "BY"),
          views.map(v =>
            Vector(
              v.name,
              v.moment.toString,
              v.outcome + v.detail.fold("")(d => s": $d"),
              v.elapsedSeconds.fold("-")(s => s"${s}s"),
              v.requestedAt.fold("-")(_.toString),
              v.requestedBy.getOrElse("the schedule")
            )
          )
        )

  def restoreHold(status: RestoreHoldStatus, format: Format): String =
    format match
      case Format.Json => writeToString(status)
      case Format.Table =>
        val head =
          if status.held then
            s"held: the control plane's database was restored to " +
              s"${status.targetTime.fold("an unknown moment")(_.toString)} and nothing is projected " +
              "until a platform administrator releases it"
          else
            status.releasedAt.fold("not held")(at =>
              s"not held: released at $at" + status.releasedBy.fold("")(b => s" by $b")
            )
        val services =
          if status.services.isEmpty then Vector.empty
          else
            "" +: table(
              Vector("PROJECT", "SERVICE", "RECORDED", "IN THE CLUSTER"),
              status.services.map(d =>
                Vector(
                  d.project,
                  d.service,
                  d.recordedGeneration.fold("-")(g => s"$g ${d.recordedImage.getOrElse("")}"),
                  d.clusterGeneration.fold("-")(g => s"$g ${d.clusterImage.getOrElse("")}")
                )
              )
            ).linesIterator.toVector
        val lines =
          Option.when(status.topics.nonEmpty)(
            s"topics differ in: ${status.topics.mkString(", ")}"
          ) ++
            Option.when(status.unknownProjects.nonEmpty)(
              s"projects the database does not know: ${status.unknownProjects.mkString(", ")}"
            ) ++ status.reconciled
        (head +: services ++: lines.toVector).mkString("\n")

  def databaseSetting(setting: DatabaseSetting, format: Format): String =
    format match
      case Format.Json => writeToString(setting)
      case Format.Table =>
        Vector(
          "replicas"    -> setting.replicas.toString,
          "synchronous" -> (if setting.synchronous then "yes" else "no"),
          "retention"   -> setting.retentionDays.fold("the installation's")(d => s"$d days"),
          "rehearsed"   -> setting.rehearse.getOrElse("never")
        ).map((label, value) => s"${label.padTo(11, ' ')}  $value").mkString("\n")

  def restores(views: Vector[RestoreView], format: Format): String =
    format match
      case Format.Json => writeToString(views)
      case Format.Table =>
        table(
          Vector("NAME", "LINE", "MOMENT", "PHASE", "REQUESTED BY"),
          views.map(v =>
            Vector(v.name, v.line, v.moment.toString, v.phase, v.requestedBy.getOrElse("-"))
          )
        )

  def projectHistory(entries: Vector[ProjectHistoryView], format: Format): String =
    format match
      case Format.Json => writeToString(entries)
      case Format.Table =>
        table(
          Vector("WHEN", "KIND", "BY", "DETAIL"),
          entries.map(e =>
            Vector(e.at.fold("-")(_.toString), e.kind, e.by.getOrElse("-"), e.detail.getOrElse("-"))
          )
        )

  /** Where the installation's backups go (feature 041), as fields. */
  def installation(status: InstallationStatus, format: Format): String =
    format match
      case Format.Json => writeToString(status)
      case Format.Table =>
        val fields = Vector(
          "backups"               -> status.notBackedUp.getOrElse(status.backupTarget),
          "retention"             -> s"at least ${status.retentionDays} days",
          "copy outside required" -> (if status.copyRequired then "yes" else "no"),
          "shares failure domain" -> (if status.sharesFailureDomain then "yes" else "no"),
          "encryption"            -> status.encryption
        ) ++ status.controlPlane.toVector.map(l =>
          "control plane" -> (l.phase + l.lastBaseBackup.fold("")(b => s", last base backup $b"))
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

  private def topicSourceLine(s: TopicSourceReport): String =
    val where = s.broker.fold(s.topic)(b => s"${s.topic}@$b")
    val as    = s.contract.fold("")(c => s" as $c")
    val lag   = s.lag.fold("")(l => s"  lag $l")
    val fail  = s.failing.fold("")(f => s"  failing: $f")
    s"${s.component}: $where$as  group ${s.group}  v${s.version}$lag$fail"

  private def topicCheckLine(c: TopicCheck): String =
    val stated = c.stated.fold("none")(identity)
    s"${c.topic}: ${c.component} ${c.direction} $stated — ${c.state}"

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
          row.broker.map("broker" -> _) ++
          row.undeclaredTopics
            .filter(_.nonEmpty)
            .map("undeclared topics" -> _.mkString("\n")) ++
          // Feature 037: each topic source with how far behind it is, and the sides it takes.
          row.topicSources
            .filter(_.nonEmpty)
            .map("topic sources" -> _.map(topicSourceLine).mkString("\n")) ++
          row.topicChecks
            .filter(_.nonEmpty)
            .map("topic checks" -> _.map(topicCheckLine).mkString("\n")) ++
          // Feature 034: each only when present, so a service with no bucket reads as before.
          row.objectStorage.map("object storage" -> _) ++ row.bucket.map("bucket" -> _) ++
          row.bucketAddress.map("bucket address" -> _) ++
          // Feature 039: where the bucket is, how long it keeps a deleted object, and a move.
          row.objectStore.map("object store" -> _) ++
          row.bucketLocation.map("bucket location" -> _) ++
          row.softDeleteDays.map(days => "soft delete" -> s"$days days") ++
          row.storageMove.map("storage move" -> _) ++
          row.detail.map("detail" -> _) ++ webFields(row)
        val width = fields.map(_._1.length).max
        // A value of several lines (a web-hosted service's mounts) continues under the first.
        fields
          .map { (label, value) =>
            val lines = value.split("\n", -1).toVector
            (s"${label.padTo(width, ' ')}  ${lines.head}" +: lines.tail.map(" " * (width + 2) + _))
              .mkString("\n")
          }
          .mkString("\n")

  /**
   * A web-hosted service's own facts (feature 021): the port its process listens on, who it admits
   * — always the internet first, since the descriptor never writes it — and each mount, with what
   * is behind it when that is anything but ok. Nothing for any other hosting.
   */
  private def webFields(row: ServiceStatus): Vector[(String, String)] =
    if row.hosting != "web" then Vector.empty
    else
      val callers = "the internet" +: row.callers.map {
        case "*"   => s"every service in ${row.projectId}"
        case other => other
      }
      val pathWidth = row.mounts.map(_.path.length).maxOption.getOrElse(0)
      val mounts = row.mounts.map { m =>
        val state = if m.state.isEmpty || m.state == "ok" then "" else s"    (${m.state})"
        s"${m.path.padTo(pathWidth, ' ')}  → ${m.service}$state"
      }
      row.processPort.map(port => "process" -> s"port $port").toVector ++
        Vector("callers" -> callers.mkString(", ")) ++
        Option.when(mounts.nonEmpty)("mounts" -> mounts.mkString("\n"))

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
          Vector("WHEN", "KIND", "GEN", "IMAGE", "DIGEST", "BY"),
          entries.map(e =>
            Vector(
              e.at.fold("-")(_.toString),
              e.rolledBackTo
                .fold(e.kind)(n => s"${e.kind} to $n") + e.detail.fold("")(d => s" ($d)"),
              e.generation.toString,
              e.image.getOrElse("-"),
              e.digest.fold("-")(_.take(DigestShown)),
              e.actor.fold("-")(a =>
                a.display.getOrElse(a.subject) + (if a.administrative then " (admin)" else "")
              )
            )
          )
        )

  /** How much of a digest the table shows: enough to tell generations apart by eye. */
  val DigestShown = 12

  /** A rollback: which generation it chose, then the status as `services get` prints it. */
  def rolledBack(result: RolledBack, format: Format): String =
    format match
      case Format.Json => writeToString(result)
      case Format.Table =>
        s"rolled back to generation ${result.rolledBackTo}\n" + service(result.status, format)

  /**
   * A past descriptor, indented, in either format: it is a document to redirect into a file and
   * apply, not a table.
   */
  def descriptor(value: ServiceDescriptor): String =
    writeToString(value, WriterConfig.withIndentionStep(2))

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

  /** `ankka installation` (feature 044): the version, and the cloud when there is one. */
  def installation(installation: Installation, format: Format): String =
    format match
      case Format.Json => writeToString(installation)
      case Format.Table =>
        val cloud = installation.cloud match
          case None => Vector("provider" -> "none")
          case Some(c) =>
            Vector("provider" -> c.provider, "account" -> c.account, "location" -> c.location) ++
              c.kmsKey.map("kms key" -> _)
        (("platform" -> installation.platformVersion) +: cloud)
          .map((label, value) => f"$label%-10s $value")
          .mkString("\n")

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
