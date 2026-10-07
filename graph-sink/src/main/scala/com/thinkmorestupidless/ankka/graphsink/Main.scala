package com.thinkmorestupidless.ankka.graphsink

import com.thinkmorestupidless.ankka.graph.neo4j.{Neo4jSettings, Neo4jSink}
import com.thinkmorestupidless.ankka.runtime.{Ankka, ProjectionRuntime}
import org.slf4j.LoggerFactory

import scala.concurrent.Await
import scala.concurrent.duration.*
import scala.util.Try

/**
 * The platform's graph sink image: one `Neo4jSink` from the environment, and nothing else. A member
 * deploys it into a project like any service, with the delta topic, the store's address and a
 * project secret for the credential in its descriptor, and `"database": "none"`.
 */
object Main:

  private val log = LoggerFactory.getLogger("ankka.graph-sink")

  final case class Settings(
      topic: String,
      version: Int,
      parallel: Boolean,
      store: Neo4jSettings
  )

  def settings(env: Map[String, String]): Either[String, Settings] =
    def required(name: String) =
      env.get(name).map(_.trim).filter(_.nonEmpty).toRight(s"$name is not set")
    for
      topic    <- required("ANKKA_GRAPH_SINK_TOPIC")
      uri      <- required("NEO4J_URI")
      username <- required("NEO4J_USERNAME")
      password <- required("NEO4J_PASSWORD")
      version <- env
        .get("ANKKA_GRAPH_SINK_VERSION")
        .fold[Either[String, Int]](Right(1))(v =>
          v.trim.toIntOption
            .filter(_ >= 1)
            .toRight(s"ANKKA_GRAPH_SINK_VERSION '$v' is not a positive whole number")
        )
      parallel <- env
        .get("ANKKA_GRAPH_SINK_PARALLEL")
        .fold[Either[String, Boolean]](Right(true))(v =>
          v.trim.toBooleanOption.toRight(s"ANKKA_GRAPH_SINK_PARALLEL '$v' is not true or false")
        )
      timeout <- env
        .get("ANKKA_GRAPH_SINK_TRANSACTION_TIMEOUT")
        .fold[Either[String, FiniteDuration]](Right(30.seconds))(v =>
          Try(Duration(v.trim)).toOption
            .collect { case d: FiniteDuration => d }
            .toRight(s"ANKKA_GRAPH_SINK_TRANSACTION_TIMEOUT '$v' is not a duration such as 30s")
        )
    yield Settings(
      topic,
      version,
      parallel,
      Neo4jSettings(
        uri,
        username,
        password,
        env
          .get("NEO4J_DATABASE")
          .map(_.trim)
          .filter(_.nonEmpty)
          .getOrElse(Neo4jSettings.DefaultDatabase),
        timeout
      )
    )

  def run(env: Map[String, String] = sys.env): Int =
    settings(env) match
      case Left(problem) =>
        log.error("refusing to start: {}", problem)
        1
      case Right(s) =>
        val sink = Neo4jSink(s.topic, s.store, version = s.version, parallel = s.parallel)
        val service = Ankka.service
          .register(sink.descriptor)
          .withExtension(ProjectionRuntime.fromEnv(env))
          .start("ankka-graph-sink")
        log.info(
          "graph sink reading '{}' at version {} into {} ({})",
          s.topic,
          s.version,
          s.store.uri,
          s.store.database
        )
        // The service is the process: it lives until it terminates.
        Await.ready(service.whenTerminated, Duration.Inf): Unit
        0

  def main(args: Array[String]): Unit = sys.exit(run())
