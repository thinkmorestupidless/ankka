package com.thinkmorestupidless.ankka.mover

import java.io.PrintStream
import java.net.URI
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import scala.util.Try

/**
 * The mover's entry point: `copy` or `verify`, configured by its environment (contracts/mover.md).
 * The last line of standard output, and the container's termination message, is the run's report as
 * one JSON object; the exit code is 0 done, 1 an object could not be moved or did not verify, 2 the
 * run could not proceed.
 */
object Main:

  def main(args: Array[String]): Unit =
    val code = run(args.toVector, sys.env, System.out, Path.of("/dev/termination-log"))
    System.out.flush()
    sys.exit(code)

  def run(
      args: Vector[String],
      env: Map[String, String],
      out: PrintStream,
      terminationLog: Path
  ): Int =
    def finish(report: Report, code: Int): Int =
      out.println(report.json)
      if Files.isWritable(terminationLog) then
        Try(Files.writeString(terminationLog, report.json, UTF_8)): Unit
      code
    val mode                         = args.headOption.getOrElse("")
    def refused(reason: String): Int = finish(Report(mode, 0, 0, 0, None, Some(reason), 0), 2)
    if mode != "copy" && mode != "verify" then refused(s"mode must be copy or verify, not '$mode'")
    else
      stores(env) match
        case Left(missing) => refused(s"$missing is not set")
        case Right((source, target)) =>
          val concurrency = env.get("MOVER_CONCURRENCY").flatMap(_.toIntOption).getOrElse(8)
          val mover       = Mover(source, target, concurrency)
          try
            val report = if mode == "copy" then mover.copy() else mover.verify()
            finish(report, 0)
          catch case e: MoveRun => finish(e.report, e.exitCode)
          finally mover.close()

  /** The two stores from the environment, or the first variable that is missing. */
  def stores(env: Map[String, String]): Either[String, (Store, Store)] =
    def side(prefix: String): Either[String, Store] =
      def need(name: String): Either[String, String] =
        val key = s"MOVER_${prefix}_$name"
        env.get(key).filter(_.nonEmpty).toRight(key)
      for
        endpoint <- need("ENDPOINT")
        region   <- need("REGION")
        bucket   <- need("BUCKET")
        access   <- need("ACCESS_KEY")
        secret   <- need("SECRET_KEY")
      yield Store(URI.create(endpoint), region, bucket, access, secret)
    for
      s <- side("SOURCE")
      t <- side("TARGET")
    yield (s, t)
