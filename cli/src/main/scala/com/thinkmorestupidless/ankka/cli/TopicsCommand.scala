package com.thinkmorestupidless.ankka.cli

import com.thinkmorestupidless.ankka.cli.local.TopicDeclarationRequests
import com.thinkmorestupidless.ankka.controlplane.api.{
  CleanupPolicy,
  CompactionLag,
  ContractDeclaration,
  ProjectTopic,
  RetentionSize,
  RetentionTime,
  TimeSpan,
  TopicDeclarationRequest
}
import com.thinkmorestupidless.ankka.core.{Contract, TopicDeclarations}
import com.thinkmorestupidless.ankka.core.graph.GraphJson

import java.io.BufferedReader
import java.nio.file.{Files, Path, Paths}

/**
 * `projects topics set --contract --schema` (feature 037): the document read, as the server reads
 * it.
 */
object TopicsCommand:

  /**
   * Both or neither: a contract is a name and a schema. `-` reads the document from `input`, which
   * is `Console.in` and which a test redirects with `Console.withIn`.
   */
  def contract(
      name: Option[String],
      schemaPath: Option[String],
      input: => BufferedReader
  ): Option[ContractDeclaration] =
    (name, schemaPath) match
      case (None, None)    => None
      case (Some(_), None) =>
        throw ApiError(0, "--contract needs --schema: a contract is a name and a schema document")
      case (None, Some(_)) =>
        throw ApiError(0, "--schema needs --contract: a contract is a name and a schema document")
      case (Some(n), Some(path)) =>
        val bytes =
          if path == "-" then
            Iterator
              .continually(input.readLine())
              .takeWhile(_ != null)
              .mkString("\n")
              .getBytes("UTF-8")
          else
            try Files.readAllBytes(Paths.get(path))
            catch
              case e: java.io.IOException =>
                throw ApiError(0, s"cannot read the schema at $path: ${e.getMessage}")
        val schema = GraphJson
          .parse(bytes)
          .fold(why => throw ApiError(0, s"the schema at $path is not JSON: $why"), identity)
        Some(ContractDeclaration(n, schema))

  private val Asked =
    // The control plane's refusal reaches the CLI as its JSON body, where the quotes are escaped.
    """(?s).*this declaration removes (.+?); a declaration that removes messages says so with \\?"removes\\?".*""".r

  /**
   * What the control plane's refusal of an unacknowledged removal says is removed, if it is one.
   */
  def removalAsked(detail: String): Option[String] = detail match
    case Asked(removal) => Some(removal.trim)
    case _              => None

  /**
   * Send a declaration. When the control plane refuses it as a removal nobody acknowledged, say
   * what it removes and ask; on "y", send it again saying so. With nothing to read the answer from,
   * it stays refused, naming `--removes`, the scripted confirmation. `input` is `Console.in`, which
   * a test redirects.
   */
  def declare(
      client: ControlPlaneClient,
      project: String,
      name: String,
      request: TopicDeclarationRequest,
      input: => BufferedReader,
      output: java.io.PrintStream
  ): Unit =
    try client.declareTopic(project, name, request)
    catch
      case e: ApiError
          if e.status == 400 && request.removes.isEmpty && removalAsked(e.detail).isDefined =>
        val removal = removalAsked(e.detail).getOrElse("")
        output.print(s"This declaration removes $removal, and they are gone. Remove them? [y/N] ")
        output.flush()
        Option(input.readLine()).map(_.trim.toLowerCase) match
          case Some("y") | Some("yes") =>
            client.declareTopic(project, name, request.copy(removes = Some(removal)))
          case None =>
            throw ApiError(
              0,
              s"this declaration removes $removal; nothing was sent. To send it without the " +
                s"question, add --removes \"$removal\""
            )
          case Some(_) => throw ApiError(0, "nothing was sent")

  /**
   * `projects topics apply` (feature 066): every topic `topics.json` declares, sent as `set` sends
   * it, in the file's order. One the project already declares the same way is said `unchanged` and
   * not sent. The first refusal stops it, naming the topic; what was sent before it stays declared.
   */
  def apply(
      client: ControlPlaneClient,
      project: String,
      root: Path,
      removes: Boolean,
      input: => BufferedReader,
      output: java.io.PrintStream
  ): Unit =
    val declarations = TopicDeclarations.read(root) match
      case Left(problems) => throw ApiError(0, problems.mkString("; "))
      case Right(None)    => throw ApiError(0, s"$root holds no ${TopicDeclarations.FileName}")
      case Right(Some(d)) => d
    val problems = TopicDeclarations.problems(root, declarations)
    if problems.nonEmpty then throw ApiError(0, problems.mkString("; "))
    val listed = client.listTopics(project).map(t => t.name -> t).toMap
    declarations.topics.foreach { topic =>
      val request = TopicDeclarationRequests
        .of(root, topic)
        .fold(p => throw ApiError(0, s"${topic.name}: ${p.mkString("; ")}"), identity)
      if listed.get(topic.name).exists(unchanged(_, request)) then
        output.println(s"${topic.name}: unchanged")
      else
        try
          if removes then acknowledging(client, project, topic.name, request)
          else declare(client, project, topic.name, request, input, output)
        catch case e: ApiError => throw ApiError(e.status, s"${topic.name}: ${e.detail}")
        output.println(s"${topic.name}: declared")
    }

  private def acknowledging(
      client: ControlPlaneClient,
      project: String,
      name: String,
      request: TopicDeclarationRequest
  ): Unit =
    try client.declareTopic(project, name, request)
    catch
      case e: ApiError if e.status == 400 && removalAsked(e.detail).isDefined =>
        client.declareTopic(project, name, request.copy(removes = removalAsked(e.detail)))

  /** Whether the project already declares `listed` as `request` would. */
  private[cli] def unchanged(listed: ProjectTopic, request: TopicDeclarationRequest): Boolean =
    def same[A](asked: Option[String], held: Option[String], parse: String => Either[String, A]) =
      asked.forall(g => held.exists(h => parse(g).toOption.isDefined && parse(g) == parse(h)))
    val contract = (request.contract, listed.contract) match
      case (None, None)       => true
      case (Some(c), Some(l)) =>
        c.name == l.name && Contract
          .fingerprint(com.github.plokhotnyuk.jsoniter_scala.core.writeToArray(c.schema))
          .toOption
          .contains(l.fingerprint)
      case _ => false
    val view     = listed.settings
    val settings =
      same(request.retention, view.map(_.retention), RetentionTime.parse) &&
        same(request.retentionSize, view.map(_.retentionSize), RetentionSize.parse) &&
        same(request.cleanup, view.map(_.cleanup), CleanupPolicy.parse) &&
        same(request.tombstoneWindow, view.map(_.tombstoneWindow), TimeSpan.parse) &&
        same(request.minCompactionLag, view.map(_.minCompactionLag), TimeSpan.parse) &&
        same(request.maxCompactionLag, view.map(_.maxCompactionLag), CompactionLag.parse) &&
        request.copies.forall(c => view.flatMap(_.copies).contains(c)) &&
        request.minInSync.forall(c => view.flatMap(_.minInSync).contains(c))
    // `compacted` is the short form of `cleanup: compact`; once `cleanup` is given it says the
    // compaction, and `same` compares it.
    val compaction = request.cleanup.isDefined || request.compacted == listed.compacted
    request.partitions.contains(listed.partitions) && compaction && contract && settings
