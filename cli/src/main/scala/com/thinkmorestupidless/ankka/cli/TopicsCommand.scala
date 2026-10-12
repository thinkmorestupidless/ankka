package com.thinkmorestupidless.ankka.cli

import com.thinkmorestupidless.ankka.controlplane.api.{ContractDeclaration, TopicDeclarationRequest}
import com.thinkmorestupidless.ankka.core.graph.GraphJson

import java.io.BufferedReader
import java.nio.file.{Files, Paths}

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
      case (None, None) => None
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
