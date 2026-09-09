package nakka.cli

import com.github.plokhotnyuk.jsoniter_scala.core.{JsonValueCodec, readFromString, writeToString}
import nakka.core.Codecs

import java.nio.file.{Files, Path, Paths}
import scala.util.Try

/**
 * What the CLI remembers between invocations.
 *
 * `project` is here for the same reason `kubectl` has a current namespace: almost every service
 * command needs one, and typing `--project checkout` forty times a day is how a tool earns a shell
 * alias instead of being used as written.
 */
final case class Settings(
    url: String = Settings.DefaultUrl,
    token: Option[String] = None,
    project: Option[String] = None
)

object Settings:

  val DefaultUrl = "http://localhost:9000"

  private given JsonValueCodec[Settings] = Codecs.make[Settings]

  /**
   * `-Dnakka.config`, else `$NAKKA_CONFIG`, else `~/.nakka/config.json`.
   *
   * The system property comes first so that a test — or an `sbt` invocation — can point the CLI at
   * a scratch file. Without it, testing `config set` would mean writing to the developer's own home
   * directory.
   */
  def path: Path =
    sys.props
      .get("nakka.config")
      .orElse(sys.env.get("NAKKA_CONFIG"))
      .map(Paths.get(_))
      .getOrElse(Paths.get(sys.props("user.home"), ".nakka", "config.json"))

  /**
   * Loads the settings, treating a missing or unreadable file as empty.
   *
   * A CLI that refuses to run because its own config file is corrupt is a CLI you cannot use to fix
   * anything. `nakka config set` overwrites it.
   */
  def load(): Settings =
    if !Files.exists(path) then Settings()
    else Try(readFromString[Settings](Files.readString(path))).getOrElse(Settings())

  def save(settings: Settings): Path =
    val target = path
    Option(target.getParent).foreach(parent => Files.createDirectories(parent): Unit)
    Files.writeString(target, writeToString(settings))

  /**
   * Resolves the effective settings: explicit flags beat the environment, which beats the saved
   * file.
   *
   * The environment sits in the middle so that CI can point the CLI at a different control plane
   * without writing to a home directory it may not have.
   */
  def resolve(
      urlFlag: Option[String],
      tokenFlag: Option[String],
      projectFlag: Option[String]
  ): Settings =
    val saved = load()
    Settings(
      url = urlFlag.orElse(sys.env.get("NAKKA_URL")).getOrElse(saved.url),
      token = tokenFlag.orElse(sys.env.get("NAKKA_TOKEN")).orElse(saved.token),
      project = projectFlag.orElse(sys.env.get("NAKKA_PROJECT")).orElse(saved.project)
    )
