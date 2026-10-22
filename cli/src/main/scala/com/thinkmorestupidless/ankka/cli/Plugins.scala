package com.thinkmorestupidless.ankka.cli

import java.io.{File, IOException, PrintStream}
import java.nio.file.{Files, Path, Paths}
import java.util.regex.Pattern
import scala.jdk.CollectionConverters.*
import scala.util.{Try, Using}

/**
 * Plugins: a first word that names no built-in command names a program `ankka-<word>` on the path,
 * run with the rest of the command line and with the settings a built-in command would have
 * resolved in its environment (`docs/reference/cli.md`, *Plugins*).
 *
 * The CLI learns nothing from a plugin and asks it nothing: it finds the file, starts it, waits,
 * and exits with its code.
 */
object Plugins:

  enum Status:
    /** The program `ankka <word>` starts. */
    case Runs

    /** Its word is a built-in command's, which always wins. */
    case Shadowed

    /** An earlier program of the same name on the path runs instead. */
    case Hidden(by: Path)

    /** Not a regular, executable file: passed over, as a shell passes over it. */
    case NotExecutable

  final case class Candidate(word: String, program: Path, status: Status)

  private val Prefix = "ankka-"

  /**
   * `-Dankka.path`, else `$PATH`, in order.
   *
   * The system property comes first for the reason `-Dankka.config` does: an environment variable
   * cannot be set in-process, so a test could otherwise only ever search the developer's own PATH.
   */
  def directories: Seq[Path] =
    sys.props
      .get("ankka.path")
      .orElse(sys.env.get("PATH"))
      .toSeq
      .flatMap(_.split(Pattern.quote(File.pathSeparator)))
      .filter(_.nonEmpty)
      .map(Paths.get(_).toAbsolutePath)

  /** A plugin is found by name on the path, never by a path the person typed. */
  def isWord(word: String): Boolean = word.nonEmpty && !word.contains('/') && !word.contains('\\')

  /**
   * Every `ankka-<word>` on the path, in path order, with whether `ankka <word>` would run it.
   *
   * Each directory is listed and names are compared as strings, never looked up with
   * `Files.exists`: on a filesystem that ignores case (APFS, by default) `ankka-Vendor` would exist
   * for `ankka-vendor`, and a plugin's name is exact. A directory that cannot be listed is skipped,
   * as a shell skips it.
   */
  def candidates(builtIn: Set[String]): Vector[Candidate] =
    val entries = directories.distinct.toVector.flatMap { directory =>
      Using(Files.list(directory))(
        _.iterator.asScala
          .map(_.getFileName.toString)
          .filter(name => name.startsWith(Prefix) && name.length > Prefix.length)
          .toVector
          .sorted
      ).getOrElse(Vector.empty).map(name => name.drop(Prefix.length) -> directory.resolve(name))
    }
    val running = scala.collection.mutable.Map.empty[String, Path]
    entries.map { (word, program) =>
      val status =
        if !(Files.isRegularFile(program) && Files.isExecutable(program)) then Status.NotExecutable
        else if builtIn(word) then Status.Shadowed
        else
          running.get(word) match
            case Some(first) => Status.Hidden(first)
            case None =>
              running(word) = program
              Status.Runs
      Candidate(word, program, status)
    }

  /** The program `ankka <word>` would run, if any. */
  def find(word: String, builtIn: Set[String]): Option[Path] =
    candidates(builtIn).collectFirst { case Candidate(`word`, program, Status.Runs) => program }

  /**
   * Runs `ankka <word> <rest>`, or says why it cannot be run: 2 for a usage error, 1 for a program
   * that would not start, otherwise the program's own code.
   */
  def dispatch(word: String, rest: Seq[String], builtIn: Set[String], err: PrintStream): Int =
    if !isWord(word) then
      err.println(s"ankka: a plugin is named by a word, not a path: '$word'")
      2
    else if directories.isEmpty then
      err.println(
        s"ankka: '$word' is not an ankka command, and PATH is empty, so no plugin can be found"
      )
      2
    else
      find(word, builtIn) match
        case None =>
          err.println(
            s"ankka: '$word' is not an ankka command, and no program '$Prefix$word' is on the path"
          )
          err.println("See 'ankka --help'.")
          2
        case Some(program) => run(program, rest, environment(err), err)

  /**
   * What a plugin is given beyond the CLI's own environment: the settings a built-in command given
   * no flags would resolve, and where they are saved.
   *
   * No token is not an error here: a plugin may need no control plane, or say `run 'ankka login'`
   * itself. A refused refresh is said on standard error, so the plugin's standard output stays a
   * script's. The token goes into the child's environment and nowhere else.
   */
  def environment(err: PrintStream): Map[String, String] =
    val settings = Settings.resolve(None, None, None)
    val token = Session.credential(settings) match
      case Session.Credential.Token(value) => Some("ANKKA_TOKEN" -> value)
      case Session.Credential.NotLoggedIn  => None
      case Session.Credential.Refused(reason) =>
        err.println(
          s"warning: ${settings.url} rejected the login ($reason); run 'ankka login'"
        )
        None
    Map(
      "ANKKA_URL"    -> settings.url,
      "ANKKA_CONFIG" -> Settings.path.toAbsolutePath.toString
    ) ++
      settings.project.map("ANKKA_PROJECT" -> _) ++
      // Against the working directory, exactly as `ControlPlaneClient` reads the same value.
      settings.ca.map(ca => "ANKKA_CA" -> Paths.get(ca).toAbsolutePath.toString) ++
      token

  /**
   * Starts the program with the CLI's streams and waits for it.
   *
   * The CLI ignores SIGINT from here on: a ctrl-c at the terminal reaches the whole foreground
   * process group, so the plugin receives it itself, and the CLI must still be there to report how
   * the plugin ended rather than return the prompt under its output. `sun.misc.Signal` rather than
   * a shutdown hook, because the native image installs no exit handlers. The handler is never
   * restored: the CLI exits right after. A child ended by a signal is reported by the JDK as 128
   * plus the signal, which is the shell's rule, so nothing maps it.
   */
  def run(program: Path, args: Seq[String], env: Map[String, String], err: PrintStream): Int =
    val builder = ProcessBuilder((program.toString +: args)*).inheritIO()
    env.foreach((name, value) => builder.environment().put(name, value): Unit)
    Try(sun.misc.Signal.handle(sun.misc.Signal("INT"), _ => ())): Unit
    try builder.start().waitFor()
    catch
      case e: IOException =>
        err.println(s"error: cannot run $program: ${e.getMessage}")
        1
