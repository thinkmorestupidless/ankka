package com.thinkmorestupidless.ankka.controlplane.api

import com.github.plokhotnyuk.jsoniter_scala.core.{JsonReader, JsonValueCodec, JsonWriter}

/**
 * Who a grant names: a service of another project, proven by its certificate, or a machine
 * registered on an organization, proven by a token the control plane issued.
 *
 * Written on the wire, in events and in the cluster as one string, `service:<project>/<name>` or
 * `machine:<organization>/<name>` — the form a person types and a log shows — so a grantee has one
 * spelling everywhere.
 */
enum Grantee:
  case Service(project: String, name: String)
  case Machine(organization: String, name: String)

  def text: String = this match
    case Service(project, name)      => s"service:$project/$name"
    case Machine(organization, name) => s"machine:$organization/$name"

  override def toString: String = text

object Grantee:

  def parse(text: String): Either[String, Grantee] =
    val refusal =
      s"grantee '$text' is not \"service:<project>/<service>\" or \"machine:<organization>/<name>\""
    text.split(":", 2) match
      case Array("service", rest) =>
        rest.split("/", -1) match
          case Array(project, name) if project.nonEmpty && name.nonEmpty =>
            Right(Service(project, name))
          case _ => Left(refusal)
      case Array("machine", rest) =>
        rest.split("/", -1) match
          case Array(organization, name) if organization.nonEmpty && name.nonEmpty =>
            Right(Machine(organization, name))
          case _ => Left(refusal)
      case _ => Left(refusal)

  /** Everything wrong with the grantee's names, all at once. */
  def problems(grantee: Grantee): Vector[String] = grantee match
    case Service(project, name) =>
      ProjectId.problems(project).map(p => s"grantee: $p") ++
        Option.when(!ServiceDescriptor.isName(name))(s"grantee: service name '$name' is invalid")
    case Machine(organization, name) =>
      Option
        .when(!ServiceDescriptor.isName(organization))(
          s"grantee: organization '$organization' cannot have machines: an organization's id is " +
            "part of a machine's name on the broker, so it must be lowercase letters, digits and " +
            "'-', starting with a letter"
        )
        .toVector ++
        Machines.nameProblems(name).map(p => s"grantee: $p")

  given codec: JsonValueCodec[Grantee] = new JsonValueCodec[Grantee]:
    def decodeValue(in: JsonReader, default: Grantee): Grantee =
      val text = in.readString(null)
      parse(text).fold(in.decodeError, identity)
    def encodeValue(x: Grantee, out: JsonWriter): Unit = out.writeVal(x.text)
    def nullValue: Grantee                             = null

/**
 * What one grant opens, as one flat value: `kind` is `route`, `method`, `topic` or `erasure`, and
 * only that kind's fields are set. Flat rather than a sum type so the wire, the journal, the
 * cluster resource and a person's eye all read the same object.
 *
 *   - `route`: `service`, `method` (an HTTP method) and `path` (the route's template);
 *   - `method`: `service` and `method`, a gRPC method as `<Service>/<Method>`;
 *   - `topic`: `topic`, one of the grantor's declared topics, and `right`, `consume` or `produce`;
 *     `decrypt` only with `consume`;
 *   - `erasure`: the right to ask for the erasure of the grantor's data subjects.
 */
final case class GrantTarget(
    kind: String,
    service: Option[String] = None,
    method: Option[String] = None,
    path: Option[String] = None,
    topic: Option[String] = None,
    right: Option[String] = None,
    decrypt: Boolean = false
):

  /** One line, as a listing shows it: `route wallet POST /v1/…`, `topic casino.players consume`. */
  def text: String = kind match
    case GrantTarget.Route =>
      s"route ${service.getOrElse("")} ${method.getOrElse("")} ${path.getOrElse("")}"
    case GrantTarget.Method => s"method ${service.getOrElse("")} ${method.getOrElse("")}"
    case GrantTarget.Topic =>
      s"topic ${topic.getOrElse("")} ${right.getOrElse("")}" + (if decrypt then " decrypt" else "")
    case other => other

object GrantTarget:
  val Route   = "route"
  val Method  = "method"
  val Topic   = "topic"
  val Erasure = "erasure"

  val Kinds: Vector[String]  = Vector(Route, Method, Topic, Erasure)
  val Consume                = "consume"
  val Produce                = "produce"
  val Rights: Vector[String] = Vector(Consume, Produce)

  val HttpMethods: Vector[String] =
    Vector("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")

  def route(service: String, method: String, path: String): GrantTarget =
    GrantTarget(Route, service = Some(service), method = Some(method), path = Some(path))

  def grpcMethod(service: String, method: String): GrantTarget =
    GrantTarget(Method, service = Some(service), method = Some(method))

  def topic(name: String, right: String, decrypt: Boolean = false): GrantTarget =
    GrantTarget(Topic, topic = Some(name), right = Some(right), decrypt = decrypt)

  val erasure: GrantTarget = GrantTarget(Erasure)

  private val GrpcMethod = "[A-Za-z_][A-Za-z0-9_.]*/[A-Za-z_][A-Za-z0-9_]*".r

  /**
   * The words a person types after the grantee, as the CLI takes them: `route <service> <METHOD>
   * <path>`, `method <service> <Service/Method>`, `topic <name> consume|produce [decrypt]`,
   * `erasure`.
   */
  def parse(words: Vector[String]): Either[String, GrantTarget] = words match
    case Vector(Route, service, method, path)  => Right(route(service, method.toUpperCase, path))
    case Vector(Method, service, method)       => Right(grpcMethod(service, method))
    case Vector(Topic, name, right)            => Right(topic(name, right))
    case Vector(Topic, name, right, "decrypt") => Right(topic(name, right, decrypt = true))
    case Vector(Erasure)                       => Right(erasure)
    case _ =>
      Left(
        s"target '${words.mkString(" ")}' is not \"route <service> <METHOD> <path>\", " +
          "\"method <service> <Service/Method>\", \"topic <name> consume|produce [decrypt]\" or " +
          "\"erasure\""
      )

  /** Everything wrong with the target, all at once. */
  def problems(target: GrantTarget): Vector[String] =
    def needs(field: String, value: Option[String]) =
      Option.when(value.forall(_.isEmpty))(s"a $field target needs a $field").toVector
    def none(field: String, value: Option[String]) =
      Option.when(value.isDefined)(s"a ${target.kind} target has no $field").toVector
    val serviceName = target.service.toVector.flatMap { s =>
      Option.when(!ServiceDescriptor.isName(s))(s"target: service name '$s' is invalid")
    }
    val noDecrypt =
      Option
        .when(target.decrypt && !(target.kind == Topic && target.right.contains(Consume)))(
          "only a grant to consume a topic may allow decryption"
        )
        .toVector
    val byKind = target.kind match
      case Route =>
        Option.when(target.service.forall(_.isEmpty))("a route target needs a service").toVector ++
          Option
            .when(!target.method.exists(HttpMethods.contains))(
              s"a route target's method is one of ${HttpMethods.mkString(", ")}"
            )
            .toVector ++
          Option
            .when(!target.path.exists(_.startsWith("/")))(
              "a route target's path is a template beginning with '/'"
            )
            .toVector ++ none("topic", target.topic) ++ none("right", target.right)
      case Method =>
        Option.when(target.service.forall(_.isEmpty))("a method target needs a service").toVector ++
          Option
            .when(!target.method.exists(GrpcMethod.matches))(
              "a method target's method is a gRPC method, \"<Service>/<Method>\""
            )
            .toVector ++ none("path", target.path) ++ none("topic", target.topic) ++
          none("right", target.right)
      case Topic =>
        needs("topic", target.topic) ++
          target.topic.toVector.flatMap(t =>
            Option.when(!ProjectTopics.validName(t))(s"topic '$t': ${ProjectTopics.NameRule}")
          ) ++
          Option
            .when(!target.right.exists(Rights.contains))(
              s"a topic target's right is one of ${Rights.mkString(", ")}"
            )
            .toVector ++ none("service", target.service) ++ none("method", target.method) ++
          none("path", target.path)
      case Erasure =>
        none("service", target.service) ++ none("method", target.method) ++
          none("path", target.path) ++ none("topic", target.topic) ++ none("right", target.right)
      case other => Vector(s"target kind '$other' is not one of ${Kinds.mkString(", ")}")
    byKind ++ serviceName ++ noDecrypt

/**
 * Where a grant is in its life. A grant within the granting project's organization starts
 * `accepted`; one to another organization's grantee starts `pending`. Only an accepted grant opens
 * anything, and the other five are ends: a grant that ended is never reopened, and granting again
 * makes a new one.
 */
enum GrantState:
  case Pending, Accepted, Declined, Withdrawn, Revoked, Relinquished, Lapsed

  def word: String = toString.toLowerCase

  def live: Boolean = this == Pending || this == Accepted

object GrantState:
  def byWord(word: String): Option[GrantState] = values.find(_.word == word)

  /** One word, as the state is written everywhere: `"accepted"`, not `{"type":"Accepted"}`. */
  given codec: JsonValueCodec[GrantState] = new JsonValueCodec[GrantState]:
    def decodeValue(in: JsonReader, default: GrantState): GrantState =
      val word = in.readString(null)
      byWord(word).getOrElse(in.decodeError(s"unknown grant state '$word'"))
    def encodeValue(x: GrantState, out: JsonWriter): Unit = out.writeVal(x.word)
    def nullValue: GrantState                             = null

/**
 * What happened to a grant, as the grantee side records it: `made` (within one organization, so
 * accepted at once), `offered` (pending), and the five ways it can change after.
 */
enum GrantChange:
  case Made, Offered, Accepted, Declined, Withdrawn, Revoked, Relinquished, Lapsed

  def word: String = toString.toLowerCase

object GrantChange:
  def byWord(word: String): Option[GrantChange] = values.find(_.word == word)

  given codec: JsonValueCodec[GrantChange] = new JsonValueCodec[GrantChange]:
    def decodeValue(in: JsonReader, default: GrantChange): GrantChange =
      val word = in.readString(null)
      byWord(word).getOrElse(in.decodeError(s"unknown grant change '$word'"))
    def encodeValue(x: GrantChange, out: JsonWriter): Unit = out.writeVal(x.word)
    def nullValue: GrantChange                             = null

/** The rules of a grant, the same in the CLI and the control plane. */
object GrantRules:

  def problems(request: GrantRequest): Vector[String] =
    Grantee.parse(request.grantee).fold(Vector(_), Grantee.problems) ++
      GrantTarget.problems(request.target)

  /** The refusal of a grant on a topic the project has not declared, worded once. */
  def undeclared(project: String, topic: String): String =
    s"project '$project' has not declared the topic '$topic'"

  /** The refusal of a grant to one of the project's own services. */
  val ownService: String =
    "a project's own services need no grant: name them in the route's ACL instead"

  /** The refusal of a change a grant's state does not allow. */
  def cannot(id: String, state: GrantState, change: String, applies: String): String =
    s"grant '$id' is ${state.word}; $change applies to $applies grant"

/** The rules of a registered machine's name and byte rates. */
object Machines:

  val NameRule: String = "lowercase letters, digits and '-', starting with a letter"

  def nameProblems(name: String): Vector[String] =
    if name.isEmpty then Vector("a machine needs a name")
    else if !ServiceDescriptor.isName(name) then
      Vector(s"machine name '$name' is invalid: $NameRule")
    else Vector.empty

  /** `machine:<organization>/<name>`: what a machine gives the token route as its client id. */
  def clientId(organization: String, name: String): String = s"machine:$organization/$name"

  /** The broker user, `machine.<organization>.<name>`. */
  def brokerUser(organization: String, name: String): String = s"machine.$organization.$name"

  /** The prefix of every consumer group the machine may read under. */
  def groupPrefix(organization: String, name: String): String =
    s"ankka.machine.$organization.$name."

  def byteRateProblems(rates: ByteRatesRequest, ceiling: Long): Vector[String] =
    def rate(label: String, value: Long) =
      if value <= 0 then Vector(s"$label must be more than nothing")
      else if value > ceiling then
        Vector(s"$label of $value bytes a second is over the installation's ceiling of $ceiling")
      else Vector.empty
    rate("the produce byte rate", rates.produceBytesPerSecond) ++
      rate("the consume byte rate", rates.consumeBytesPerSecond) ++
      Option
        .when(rates.requestPercentage < 1 || rates.requestPercentage > 100)(
          "the request percentage is from 1 to 100"
        )
        .toVector

// ── Wire types ──────────────────────────────────────────────────────────────

/** `POST /projects/{id}/grants`: one grantee, as text, and one target. */
final case class GrantRequest(grantee: String, target: GrantTarget)

/** Who did something to a grant, and when: a display label, never a subject key. */
final case class GrantAct(by: Option[String] = None, at: Option[java.time.Instant] = None)

/**
 * A grant as its project lists it. `effect` is `in effect`, or why not: the state's word for a
 * grant that is not accepted, else `route not seen`, `route not grantable`, `rollout needed` or
 * `broker not exposed`.
 */
final case class GrantDetail(
    id: String,
    grantee: Grantee,
    target: GrantTarget,
    state: GrantState,
    effect: String,
    granted: GrantAct,
    answered: Option[GrantAct] = None,
    ended: Option[GrantAct] = None
)

/** A declared topic's settings, as a grantee of it is told them. */
final case class TopicSettings(
    partitions: Int,
    compacted: Boolean = false,
    retention: Option[String] = None,
    copies: Option[Int] = None
)

/** One change to a grant as the grantee side recorded it. */
final case class GrantChangeRecord(
    change: GrantChange,
    by: Option[String] = None,
    at: Option[java.time.Instant] = None
)

/** A grant as its grantee's side lists it: what it reaches, from whom, and every change. */
final case class ReceivedGrantDetail(
    id: String,
    grantingProject: String,
    grantingOrganization: String,
    grantee: Grantee,
    target: GrantTarget,
    state: GrantState,
    changes: Vector[GrantChangeRecord] = Vector.empty,
    topic: Option[TopicSettings] = None
)

/** `POST /organizations/{id}/machines`. */
final case class MachineRegistration(name: String)

/**
 * The answer to a registration: the client id and secret, shown this once, where to take a token,
 * and the broker's address when the installation exposes it.
 */
final case class MachineRegistered(
    name: String,
    clientId: String,
    clientSecret: String,
    tokenUrl: String,
    brokerBootstrap: Option[String] = None
)

/** `PUT /organizations/{id}/machines/{name}/byte-rates`. */
final case class ByteRatesRequest(
    produceBytesPerSecond: Long,
    consumeBytesPerSecond: Long,
    requestPercentage: Int
)

/** A registered machine as listed: never its secret. */
final case class MachineSummary(
    name: String,
    clientId: String,
    registeredBy: Option[String] = None,
    registeredAt: Option[java.time.Instant] = None,
    byteRates: Option[ByteRatesRequest] = None
)

/** The token route's answer, in RFC 6749's spelling. */
final case class TokenResponse(access_token: String, token_type: String, expires_in: Long)

/** One public key of the control plane's, as a JSON Web Key. */
final case class JsonWebKey(
    kty: String,
    use: String,
    alg: String,
    kid: String,
    n: String,
    e: String
)

/** The keys a machine token is checked against. */
final case class JsonWebKeySet(keys: Vector[JsonWebKey])
