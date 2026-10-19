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
      pairs(env) match
        case Left(reason) => refused(reason)
        case Right(moving) =>
          val concurrency    = env.get("MOVER_CONCURRENCY").flatMap(_.toIntOption).getOrElse(8)
          val (report, code) = runAll(mode, moving, concurrency)
          finish(report, code)

  /**
   * Every pair in turn, the unnamed bucket's first (feature 055): the counts are summed, and the
   * first pair that fails ends the run, its reason naming the pair when there is more than one.
   */
  private def runAll(
      mode: String,
      moving: Vector[(Store, Store)],
      concurrency: Int
  ): (Report, Int) =
    val started = System.nanoTime()
    def seconds = java.util.concurrent.TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started)
    def sum(a: Report, b: Report) =
      a.copy(
        counted = a.counted + b.counted,
        copied = a.copied + b.copied,
        verified = a.verified + b.verified
      )
    val empty = Report(mode, 0, 0, 0, None, None, 0)
    @annotation.tailrec
    def loop(rest: Vector[(Store, Store)], done: Report): (Report, Int) =
      rest.headOption match
        case None => (done.copy(seconds = seconds), 0)
        case Some((source, target)) =>
          val mover = Mover(source, target, concurrency)
          val result =
            try Right(if mode == "copy" then mover.copy() else mover.verify())
            catch case e: MoveRun => Left(e)
            finally mover.close()
          result match
            case Right(report) => loop(rest.tail, sum(done, report))
            case Left(e) =>
              val pair   = s"${source.bucket} -> ${target.bucket}"
              val reason = e.report.reason.map(r => if moving.size > 1 then s"$pair: $r" else r)
              (
                sum(done, e.report).copy(
                  failed = e.report.failed,
                  reason = reason,
                  seconds = seconds
                ),
                e.exitCode
              )
    loop(moving, empty)

  /**
   * The pairs of stores from the environment, or why there are none. Each side is one endpoint,
   * region and credential, with its buckets in `MOVER_<side>_BUCKETS`, comma-separated and parallel
   * to the other side's (feature 055); a mover rendered before that reads `MOVER_<side>_BUCKET`.
   */
  def pairs(env: Map[String, String]): Either[String, Vector[(Store, Store)]] =
    def side(prefix: String): Either[String, Vector[Store]] =
      def need(name: String): Either[String, String] =
        val key = s"MOVER_${prefix}_$name"
        env.get(key).filter(_.nonEmpty).toRight(s"$key is not set")
      val buckets =
        env
          .get(s"MOVER_${prefix}_BUCKETS")
          .map(_.split(',').toVector.map(_.trim).filter(_.nonEmpty))
          .filter(_.nonEmpty)
          .orElse(env.get(s"MOVER_${prefix}_BUCKET").filter(_.nonEmpty).map(Vector(_)))
          .toRight(s"MOVER_${prefix}_BUCKETS is not set")
      for
        endpoint <- need("ENDPOINT")
        region   <- need("REGION")
        names    <- buckets
        access   <- need("ACCESS_KEY")
        secret   <- need("SECRET_KEY")
      yield names.map(Store(URI.create(endpoint), region, _, access, secret))
    for
      s <- side("SOURCE")
      t <- side("TARGET")
      _ <- Either.cond(
        s.size == t.size,
        (),
        s"MOVER_SOURCE_BUCKETS names ${s.size} buckets and MOVER_TARGET_BUCKETS ${t.size}"
      )
    yield s.zip(t)
