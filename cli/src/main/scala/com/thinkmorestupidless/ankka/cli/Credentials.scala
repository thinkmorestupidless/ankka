package com.thinkmorestupidless.ankka.cli

import com.github.plokhotnyuk.jsoniter_scala.core.{JsonValueCodec, readFromString, writeToString}
import com.thinkmorestupidless.ankka.core.Codecs

import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.{Files, Path}
import scala.util.Try

/**
 * A saved login for one control plane: what `ankka login` obtained, renewable without a browser.
 */
final case class Login(
    issuer: String,
    clientId: String,
    refreshToken: String,
    accessToken: String,
    /** Epoch seconds. */
    expiresAt: Long
):
  def accessTokenUsable(now: Long, margin: Long = 30): Boolean = expiresAt - margin > now

/**
 * `credentials.json` beside `config.json`, keyed by control plane URL.
 *
 * A separate file from the settings, deliberately: `ankka config get` prints the settings, and a
 * credential must never be printed. Keyed by URL so that pointing the CLI at a second installation
 * never presents the first one's login to it. Readable by the owner only, where the filesystem can
 * say so. It follows `Settings.path`, so `-Dankka.config` and `ANKKA_CONFIG` relocate it too — the
 * same isolation a test needs for the config file (see the trap about `$HOME` in CLAUDE.md).
 */
object Credentials:

  private given JsonValueCodec[Map[String, Login]] = Codecs.make[Map[String, Login]]

  def path: Path =
    val config = Settings.path
    Option(config.getParent).fold(Path.of("credentials.json"))(_.resolve("credentials.json"))

  def load(): Map[String, Login] =
    if !Files.exists(path) then Map.empty
    else Try(readFromString[Map[String, Login]](Files.readString(path))).getOrElse(Map.empty)

  def get(url: String): Option[Login] = load().get(key(url))

  def put(url: String, login: Login): Path = save(load() + (key(url) -> login))

  def remove(url: String): Path = save(load() - key(url))

  def clear(): Path = save(Map.empty)

  private def save(all: Map[String, Login]): Path =
    val target = path
    Option(target.getParent).foreach(parent => Files.createDirectories(parent): Unit)
    Files.writeString(target, writeToString(all))
    // Owner-only. Not every filesystem is POSIX; on one that is not, the write above stands and
    // the permissions are whatever the platform gives a new file.
    Try(Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rw-------"))): Unit
    target

  /** Trailing slashes and case in the host must not make two entries of one installation. */
  private def key(url: String): String = url.trim.stripSuffix("/").toLowerCase
