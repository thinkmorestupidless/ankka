package com.thinkmorestupidless.ankka.keyring

import com.github.plokhotnyuk.jsoniter_scala.core.{JsonValueCodec, readFromArray}
import com.thinkmorestupidless.ankka.core.Codecs
import com.thinkmorestupidless.ankka.http.{Caller, MachineTokens}
import org.slf4j.LoggerFactory

import java.nio.file.{Files, Path}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

/**
 * Who may ask the keyring what (feature 042, `contracts/keyring-and-protocol.md`, FR-018). The
 * caller is read from its client certificate as every port reads it, from a relayed installation's
 * identity, or from a machine's bearer token. Every refusal is recorded with the caller and why.
 */
final class Admission(
    grants: ProjectGrants,
    store: KeyStore,
    /** The installations a project's home admits to relay a follower's fetch (R17). */
    relays: String => Set[String] = _ => Set.empty
)(using ExecutionContext):

  private val log = LoggerFactory.getLogger(classOf[Admission])

  /** `request` from `who`, admitted or refused with the reason, the refusal recorded. */
  def admit(who: Admission.Who, request: Admission.Request): Future[Either[String, Unit]] =
    decide(who, request) match
      case Right(()) =>
        granted(who, request) match
          case None                          => Future.successful(Right(()))
          case Some((caller, grant, p, sub)) =>
            // A key read under another project's grant is recorded with the grant it was read under.
            store.granted(caller, grant, p, sub).map(_ => Right(())).recover { case NonFatal(e) =>
              log.warn(
                s"a read of ${who.name} under grant $grant was not recorded: ${e.getMessage}"
              )
              Right(())
            }
      case Left(why) =>
        val (project, subject) = request.about
        log.info(s"the keyring refused ${who.name}: $why")
        store.refused(who.name, project, subject, why).map(_ => Left(why)).recover {
          case NonFatal(e) =>
            log.warn(s"a refusal of ${who.name} was not recorded: ${e.getMessage}")
            Left(why)
        }

  /** The caller, the grant, the project and the subject of an admitted read of another's key. */
  private def granted(
      who: Admission.Who,
      request: Admission.Request
  ): Option[(String, String, String, String)] =
    (who, request) match
      case (Admission.Who.Service(Caller.Service(p, s)), Admission.Request.Key(project, subject, _))
          if p != project =>
        grants.decryptGrant(project, s"service:$p/$s").map(g => (who.name, g.id, project, subject))
      case _ => None

  private def decide(who: Admission.Who, request: Admission.Request): Either[String, Unit] =
    import Admission.Request.*
    import Admission.Who.*
    (who, request) match
      // The control plane orders erasures and reads their status; it never reads a key.
      case (Service(Admission.ControlPlane), _: Destroy | _: Resend | _: Status) => Right(())
      case (Service(Admission.ControlPlane), _)                                  =>
        Left("the control plane may order erasures and read their status, and nothing else")
      case (_, _: Destroy | _: Resend | _: Status) => Left("only the control plane orders erasures")
      // A service of the project: its keys, its lookup key and its notices.
      case (Service(Caller.Service(p, _)), Key(project, _, _)) if p == project => Right(())
      case (Service(Caller.Service(p, _)), LookupKey(project)) if p == project => Right(())
      case (Service(Caller.Service(p, _)), Notices(project)) if p == project   => Right(())
      // A consumer in another project reads with a grant to consume one of its topics, with decrypt.
      case (Service(Caller.Service(p, s)), Key(project, _, create)) =>
        if create then Left(s"a service of $p may not make a key of $project")
        else if grants.decrypts(project, s"service:$p/$s") then Right(())
        else Left(s"service:$p/$s holds no grant to consume a topic of $project with decrypt")
      case (Service(Caller.Service(p, s)), _) =>
        Left(s"service:$p/$s is not a service of ${request.about._1}")
      // A follower's fetch, relayed: read only, for an installation the home admits.
      case (Relayed(installation), Key(project, _, false)) =>
        if relays(project).contains(installation) then Right(())
        else Left(s"installation $installation is not admitted to relay keys of $project")
      case (Relayed(installation), _) =>
        Left(s"installation $installation may only relay a read of a key")
      // A machine outside the installation, through the gateway: decryption under a grant it holds.
      case (Machine(Caller.Machine(org, name)), Decrypt(project, grant)) =>
        if grants.decryptsWith(project, grant, s"machine:$org/$name") then Right(())
        else Left(s"machine:$org/$name holds no grant $grant to decrypt topics of $project")
      case (Machine(Caller.Machine(org, name)), _) => Left(s"machine:$org/$name may only decrypt")
      case (Service(other), _) => Left(s"${Caller.encode(other)} is not a caller of the keyring")
      case (Unknown(why), _)   => Left(why)

object Admission:

  /** The control plane's identity, as its certificate names it. */
  val ControlPlane: Caller.Service = Caller.Service("platform", "controlplane")

  enum Who:
    /** A caller read from a client certificate. */
    case Service(caller: Caller)

    /** Another installation, relaying a follower's fetch (R17). */
    case Relayed(installation: String)

    /** A machine, from its bearer token. */
    case Machine(caller: Caller.Machine)

    /** A caller that could not be named, and why. */
    case Unknown(why: String)

    def name: String = this match
      case Service(c) => Caller.encode(c)
      case Relayed(i) => s"installation:$i"
      case Machine(c) => Caller.encode(c)
      case Unknown(_) => "unknown"

  object Who:
    /** A certificate's caller, or why it names none. */
    def fromCertificate(certificate: java.security.cert.X509Certificate): Who =
      Caller.fromCertificate(certificate, None).fold(Unknown(_), Service(_))

    /** A bearer token's machine, or why it is none. */
    def fromBearer(tokens: MachineTokens, token: String): Who =
      tokens.verify(token) match
        case Right(m: Caller.Machine) => Machine(m)
        case Right(other)             => Unknown(s"${Caller.encode(other)} is not a machine")
        case Left(why)                => Unknown(why)

  enum Request:
    case Key(project: String, subject: String, create: Boolean)
    case LookupKey(project: String)
    case Notices(project: String)
    case Destroy(project: String, subject: String)
    case Resend(seq: Long)
    case Status(seq: Long)
    case Decrypt(project: String, grant: String)

    /** The project and data subject the request is about, for the record of a refusal. */
    def about: (String, String) = this match
      case Key(p, s, _)  => p  -> s
      case LookupKey(p)  => p  -> ""
      case Notices(p)    => p  -> ""
      case Destroy(p, s) => p  -> s
      case Resend(_)     => "" -> ""
      case Status(_)     => "" -> ""
      case Decrypt(p, _) => p  -> ""

/**
 * Every project's grants, as the operator renders them into each project's `ankka-project`
 * ConfigMap (`grants.json`, feature 040): the keyring admits by a grant to consume a topic with
 * `decrypt`.
 */
trait ProjectGrants:
  /** The rendered grants of `project`; none when it has none. */
  def of(project: String): Vector[ProjectGrants.Grant]

  /** Whether `grantee` holds a grant to consume a topic of `project` with decrypt. */
  final def decrypts(project: String, grantee: String): Boolean =
    decryptGrant(project, grantee).isDefined

  /** The first grant, by id, under which `grantee` consumes a topic of `project` with decrypt. */
  final def decryptGrant(project: String, grantee: String): Option[ProjectGrants.Grant] =
    of(project).filter(g => g.grantee == grantee && g.decrypts).sortBy(_.id).headOption

  /** Whether grant `id` of `project` is `grantee`'s, to consume a topic with decrypt. */
  final def decryptsWith(project: String, id: String, grantee: String): Boolean =
    of(project).exists(g => g.id == id && g.grantee == grantee && g.decrypts)

object ProjectGrants:

  final case class Grant(
      id: String = "",
      grantee: String = "",
      kind: String = "",
      topic: Option[String] = None,
      right: Option[String] = None,
      decrypt: Boolean = false
  ):
    def decrypts: Boolean = kind == "topic" && right.contains("consume") && decrypt

  private final case class File(project: String = "", grants: Vector[Grant] = Vector.empty)
  private given JsonValueCodec[File] = Codecs.make[File]

  /** A rendered `grants.json`'s grants; anything malformed throws. */
  def parse(bytes: Array[Byte]): Vector[Grant] = readFromArray[File](bytes).grants

  val none: ProjectGrants = _ => Vector.empty

  /** `<root>/<project>/grants.json`, read on each ask: a test's, and a developer's. */
  def fromDirectory(root: Path): ProjectGrants = project =>
    val file = root.resolve(project).resolve("grants.json")
    if Files.exists(file) then parse(Files.readAllBytes(file)) else Vector.empty
