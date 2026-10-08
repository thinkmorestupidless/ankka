package com.thinkmorestupidless.ankka.cli

import com.thinkmorestupidless.ankka.controlplane.api.ContractDeclaration
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
