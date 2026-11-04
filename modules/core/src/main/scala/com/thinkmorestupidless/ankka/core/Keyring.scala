package com.thinkmorestupidless.ankka.core

/** What a keyring answers for a data subject's key (feature 042). */
enum KeyAnswer:
  /** The subject key: 32 bytes for AES-256-GCM. */
  case Key(bytes: IArray[Byte])

  /** The subject key was destroyed, by an erasure: the data subject reads as erased. */
  case Destroyed

/**
 * Where a service's personal fields get their keys (feature 042): the runtime's client of the
 * installation's keyring, a test kit's in-memory one, or `Keyring.unavailable`.
 *
 * A codec is reached from derivation, never from a host, so it cannot be handed a keyring: it reads
 * `Keyring.current` on every call. That is the one piece of global state the SDK gains, and it is a
 * default plus a per-thread override, so two services in one JVM (two test kits) each bind their
 * own around their serializer calls and never read the other's.
 */
trait Keyring:

  /**
   * The project whose data subjects this service writes: an envelope's project when sealed here.
   */
  def project: String

  /**
   * The key of `subject` in `project`. `create` makes it when it does not exist yet, which only a
   * write asks for; a read of a subject with no key is told `Destroyed`. Throws `CommandError` with
   * `ErrorCode.Unavailable` when the keyring cannot be reached.
   */
  def key(project: String, subject: DataSubject, create: Boolean): KeyAnswer

object Keyring:

  /** The keyring of a service that reaches none: every personal field is refused, naming it. */
  val unavailable: Keyring = new Keyring:
    def project: String                                                        = ""
    def key(project: String, subject: DataSubject, create: Boolean): KeyAnswer =
      throw CommandError(
        "the keyring is unavailable: no keyring is installed for this service, so a personal field " +
          "can be neither written nor read (does the service declare \"personal\": true, and does the " +
          "installation run a keyring?)",
        ErrorCode.Unavailable,
        Map(Personal.ErrorKey -> Personal.Unavailable)
      )
    override def toString: String = "Keyring.unavailable"

  @volatile private var default: Keyring = unavailable
  private val bound                      = new ThreadLocal[Keyring]

  /** The keyring a codec on this thread uses: the one bound here, else the process's default. */
  def current: Keyring =
    val here = bound.get
    if here != null then here else default

  /** Sets the process's default, which the runtime does when a service starts. */
  def install(keyring: Keyring): Unit = default = keyring

  /** Clears the process's default back to `unavailable`, but only if it is still `keyring`. */
  def uninstall(keyring: Keyring): Unit = synchronized {
    if default eq keyring then default = unavailable
  }

  /** Runs `body` with `keyring` bound on this thread, restoring what was bound before. */
  def binding[T](keyring: Keyring)(body: => T): T =
    val before = bound.get
    bound.set(keyring)
    try body
    finally if before == null then bound.remove() else bound.set(before)
