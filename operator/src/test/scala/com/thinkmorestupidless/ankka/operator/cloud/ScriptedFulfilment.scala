package com.thinkmorestupidless.ankka.operator.cloud

import com.thinkmorestupidless.ankka.operator.BucketNames
import com.thinkmorestupidless.ankka.crd.{
  CloudKinds,
  CloudResourceSpec,
  CloudResourceStatus,
  CloudSubject
}
import com.thinkmorestupidless.ankka.operator.CloudRequests.{Keys, Purpose}
import com.thinkmorestupidless.ankka.operator.{ObjectStore, StorageCredential}

import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import scala.collection.mutable
import scala.concurrent.duration.FiniteDuration

/**
 * What the scripted cloud provider writes a Secret through: `create`, learning from a conflict that
 * one is there, and `patch`. Nothing that reads one, because the grant it runs under has nothing
 * that could.
 */
trait SecretWrites:
  def create(namespace: String, name: String, entries: Map[String, String]): SecretWrites.Outcome
  def patch(namespace: String, name: String, entries: Map[String, String]): Unit

object SecretWrites:
  enum Outcome:
    case Created, Exists

/**
 * What the scripted cloud provider remembers of the cloud account it pretends to hold: what it made
 * for which subject, and where. Shared between two fulfilments to stand for an installation that
 * changed its account or location under requests already fulfilled.
 */
final class ScriptedMemory:
  final case class Made(account: String, location: String, outputs: Map[String, String])
  private[cloud] val made = mutable.Map.empty[(String, CloudSubject), Made]

/**
 * Where the scripted cloud provider makes what it is asked for: nowhere, with visibly made-up
 * answers (`MadeUp`, as feature 044's suite runs it), or in a Garage standing in for the cloud
 * (`InGarage`, feature 039), so a service on the cloud path in a k3s suite keeps and reads objects
 * for real and a replaced credential is refused once its grace has passed.
 */
trait ScriptedStore:
  /** Make the bucket or find it, set its origins, and say where it is reached: endpoint, region. */
  def bucket(name: String, location: String, corsOrigins: Vector[String]): (String, String)

  /** A new credential allowed on one bucket: its key id, and the two entries its Secret holds. */
  def mint(bucket: String, secretName: String, generation: Long): (String, Map[String, String])

  /** End a credential at `at`, by the store's own clock. */
  def end(keyId: String, at: Instant): Unit

object ScriptedStore:

  object MadeUp extends ScriptedStore:
    def bucket(name: String, location: String, corsOrigins: Vector[String]): (String, String) =
      "https://storage.scripted.invalid" -> location

    def mint(bucket: String, secretName: String, generation: Long): (String, Map[String, String]) =
      val id = s"scripted-$secretName-$generation"
      id -> Map(
        StorageCredential.AccessKeyEntry -> id,
        StorageCredential.SecretKeyEntry ->
          s"scripted-secret-$secretName-$generation-${java.util.UUID.randomUUID()}"
      )

    def end(keyId: String, at: Instant): Unit = ()

  /**
   * A Garage as the cloud: its buckets under the contract's names, its keys as the credentials, its
   * S3 port as the endpoint, and its own key expiry as the end of a replaced credential.
   */
  final class InGarage(store: ObjectStore, endpoint: String, region: String) extends ScriptedStore:
    def bucket(name: String, location: String, corsOrigins: Vector[String]): (String, String) =
      val made = store.bucket(name).getOrElse(store.createBucket(name))
      store.setCors(made.id, corsOrigins)
      endpoint -> region

    def mint(bucket: String, secretName: String, generation: Long): (String, Map[String, String]) =
      val made = store
        .bucket(bucket)
        .getOrElse(throw IllegalStateException(s"no bucket $bucket to grant a credential on"))
      val key = store.createKey(s"$secretName#$generation")
      store.allow(made.id, key.accessKeyId)
      key.accessKeyId -> Map(
        StorageCredential.AccessKeyEntry -> key.accessKeyId,
        StorageCredential.SecretKeyEntry -> key.secretAccessKey
      )

    def end(keyId: String, at: Instant): Unit = store.expire(keyId, at)

/** A credential issued, for which Secret and generation, and when. */
final case class Issued(secretName: String, generation: Long, at: Instant)

/** A credential ended, and why: `conflict` (its Secret was already there) or `rotated`. */
final case class Ended(secretName: String, generation: Long, at: Instant, why: String)

/**
 * The scripted cloud provider's answers (feature 044), pure of any cluster: given a request it
 * returns the status to write, and writes a credential through `SecretWrites` when the kind has
 * one. Its outputs are visibly made up, it reaches nothing outside the cluster, and it records
 * every credential it issues and ends so a scenario can assert on them.
 *
 * It holds itself to `docs/platform/cloud-provider.md`'s rules as `ankka-gcp` must: a credential is
 * offered once and a conflict ends the one just made; a raised generation patches the same Secret
 * and ends the previous credential once the rotation grace has passed since the report; a request
 * whose account or location moved is refused, never moved; nothing is ever deleted.
 */
final class ScriptedFulfilment(
    val provider: String,
    val account: String,
    val location: String,
    val grace: FiniteDuration,
    clock: () => Instant = () => Instant.now(),
    val memory: ScriptedMemory = new ScriptedMemory,
    store: ScriptedStore = ScriptedStore.MadeUp
):
  val version: String = "scripted 0.1.0"

  /** The key id of each credential minted, by Secret and generation, so a replaced one is ended. */
  private val keys = mutable.Map.empty[(String, Long), String]

  private val issuedLog = mutable.ArrayBuffer.empty[Issued]
  private val endedLog  = mutable.ArrayBuffer.empty[Ended]
  private val due       = mutable.ArrayBuffer.empty[(Ended, Instant)]
  private val refusals  = mutable.Map.empty[String, String]
  private val stalls    = mutable.Map.empty[String, String]

  /** Anything this provider tried to reach outside the cluster. It has nothing that could. */
  val reached: AtomicInteger = new AtomicInteger(0)

  def issued: Vector[Issued] = synchronized(issuedLog.toVector)
  def ended: Vector[Ended]   = synchronized(endedLog.toVector)

  /**
   * Whether it holds, in the account it pretends to, what a request of this kind made for a
   * subject.
   */
  def holds(kind: String, subject: CloudSubject): Boolean = synchronized(
    memory.made.contains(kind -> subject)
  )

  /** What it made for a subject, as it answered. */
  def outputsFor(kind: String, subject: CloudSubject): Option[Map[String, String]] =
    synchronized(memory.made.get(kind -> subject).map(_.outputs))

  /** Fail the request of this name with this reason, from now on. */
  def failing(name: String, reason: String): Unit = synchronized(refusals(name) = reason)

  /** Answer the request of this name as still being made, saying why, until `proceed`. */
  def stalling(name: String, reason: String): Unit = synchronized(stalls(name) = reason)

  /** Stop stalling every request. */
  def proceed(): Unit = synchronized(stalls.clear())

  /**
   * The status for one request at one generation. `previous` is the status it already carries, if
   * any: a provider reads a request's status as freely as its spec, and the rotation's moment is
   * derived from it rather than from memory, so a restart still ends the old credential.
   */
  def fulfil(
      namespace: String,
      name: String,
      spec: CloudResourceSpec,
      generation: Long,
      previous: Option[CloudResourceStatus],
      secrets: SecretWrites
  ): CloudResourceStatus = synchronized {
    val now = clock()
    val base = CloudResourceStatus(
      observedGeneration = Some(generation),
      account = account,
      location = spec.parameters.getOrElse(Keys.Location, location),
      providerVersion = version
    )
    def failed(reason: String) = base.copy(phase = CloudKinds.Failed, detail = Some(reason))

    refusals
      .get(name)
      .map(failed)
      .orElse(
        stalls.get(name).map(reason => base.copy(phase = CloudKinds.Waiting, detail = Some(reason)))
      ) match
      case Some(answer) => answer
      case None if !CloudKinds.all.contains(spec.kind) =>
        failed(s"kind ${spec.kind} is not implemented by $version")
      case None =>
        val key      = (spec.kind, spec.subject)
        val existing = memory.made.get(key)
        existing match
          case Some(m)
              if m.account != account ||
                (spec.parameters.contains(Keys.Location) && m.location != base.location) =>
            failed("made in another account or location")
          case _ =>
            answer(namespace, spec, now, previous, existing.map(_.outputs), secrets) match
              case Left(reason) => failed(reason)
              case Right((outputs, credential)) =>
                memory.made(key) = memory.Made(account, base.location, outputs)
                // Recovered means the thing was there before this request was: a later generation
                // of the same request keeps what the first answer said.
                val recovered = previous.map(_.recovered).getOrElse(existing.isDefined)
                base.copy(
                  phase = if recovered then CloudKinds.Recovered else CloudKinds.Ready,
                  recovered = recovered,
                  outputs = outputs,
                  credentialGeneration = credential.map(_._1),
                  credentialReportedAt = credential.map(_._2.toString)
                )
  }

  /** End every replaced credential whose grace has passed; returns what it ended. */
  def endDue(): Vector[Ended] = synchronized {
    val now          = clock()
    val (ready, not) = due.partition((_, at) => !now.isBefore(at))
    due.clear()
    due ++= not
    val endings = ready.map((e, _) => e.copy(at = now)).toVector
    endedLog ++= endings
    endings
  }

  private def answer(
      namespace: String,
      spec: CloudResourceSpec,
      now: Instant,
      previous: Option[CloudResourceStatus],
      before: Option[Map[String, String]],
      secrets: SecretWrites
  ): Either[String, (Map[String, String], Option[(Long, Instant)])] =
    val p = spec.parameters
    spec.kind match
      case CloudKinds.Identity =>
        Right(
          Map(
            Keys.Identity                  -> s"${p(Keys.ServiceAccount)}@$account.scripted",
            Keys.ServiceAccountAnnotations -> s"scripted.example/identity=${p(Keys.ServiceAccount)}"
          ) -> None
        )
      case CloudKinds.SecretAccess =>
        Right(Map.empty -> None)
      case CloudKinds.SecretSync =>
        val entries = p(Keys.Entries)
          .split(',')
          .filter(_.nonEmpty)
          .map(_.split("=", 2))
          .collect { case Array(k, id) => k -> s"scripted-value-of-$id" }
          .toMap
        secrets.patch(namespace, p(Keys.SecretName), entries)
        Right(Map(Keys.EntryGeneration -> p(Keys.EntryGeneration)) -> None)
      case CloudKinds.Bucket =>
        val bucket  = before.flatMap(_.get(Keys.Bucket)).getOrElse(bucketName(spec))
        val origins = p.get(Keys.CorsOrigins).toVector.flatMap(_.split(',')).filter(_.nonEmpty)
        val (endpoint, region) = store.bucket(bucket, p.getOrElse(Keys.Location, location), origins)
        Right(
          Map(
            Keys.Bucket   -> bucket,
            Keys.Endpoint -> endpoint,
            Keys.Region   -> region
          ) -> None
        )
      case CloudKinds.BucketCredential => credential(namespace, spec, now, previous, secrets)
      case CloudKinds.WrappingKey =>
        Right(Map(Keys.Key -> p(Keys.Key)) -> None)

  /**
   * A service's bucket is named by the contract's rule (`BucketNames`), its prefix the request's
   * `namePrefix` or, with none, the account. A backup bucket's name cannot be any service's:
   * `<account>-<project>--backup` would need a service whose name begins with a hyphen, which no
   * name can.
   */
  private def bucketName(spec: CloudResourceSpec): String =
    if spec.parameters.get(Keys.Purpose).contains(Purpose.Backup) then
      s"$account-${spec.subject.project}--backup"
    else
      BucketNames.name(
        spec.parameters.get(Keys.NamePrefix).filter(_.nonEmpty).getOrElse(account),
        spec.subject.project,
        spec.subject.service
      )

  private def isBackupBucket(bucket: String): Boolean = bucket.endsWith("--backup")

  /** The project's database's identity: what an identity request of the project's own answered. */
  private def databaseIdentity(project: String): Option[String] =
    memory.made
      .get((CloudKinds.Identity, CloudSubject(project)))
      .flatMap(_.outputs.get(Keys.Identity))

  private def credential(
      namespace: String,
      spec: CloudResourceSpec,
      now: Instant,
      previous: Option[CloudResourceStatus],
      secrets: SecretWrites
  ): Either[String, (Map[String, String], Option[(Long, Instant)])] =
    val p          = spec.parameters
    val secretName = p(Keys.SecretName)
    val wanted     = math.max(spec.credentialGeneration, 1L)
    val inPlace    = previous.flatMap(_.credentialGeneration)
    val outputs    = Map(Keys.SecretName -> secretName)
    if isBackupBucket(p(Keys.Bucket)) && !databaseIdentity(spec.subject.project)
        .contains(p(Keys.Identity))
    then Left("a backup bucket is granted only to its project's database")
    else
      inPlace match
        case Some(generation) if generation >= wanted =>
          // Already answered at this generation: issue nothing.
          Right(
            outputs -> previous.flatMap(s =>
              s.credentialReportedAt.map(at => generation -> Instant.parse(at))
            )
          )
        case Some(older) =>
          issuedLog += Issued(secretName, wanted, now)
          secrets.patch(namespace, secretName, mint(p(Keys.Bucket), secretName, wanted))
          val endsAt = now.plusMillis(grace.toMillis)
          // The store ends the old key by its own clock, as a cloud's would; the record is the
          // provider's, kept when the grace has passed.
          keys.get(secretName -> older).foreach(store.end(_, endsAt))
          due += Ended(secretName, older, now, "rotated") -> endsAt
          Right(outputs -> Some(wanted -> now))
        case None =>
          issuedLog += Issued(secretName, wanted, now)
          secrets.create(namespace, secretName, mint(p(Keys.Bucket), secretName, wanted)) match
            case SecretWrites.Outcome.Created =>
              Right(outputs -> Some(wanted -> now))
            case SecretWrites.Outcome.Exists =>
              // Someone wrote this Secret before: what is in it stays, and the key just issued is
              // in no Secret, so it is ended now.
              keys.get(secretName -> wanted).foreach(store.end(_, now))
              endedLog += Ended(secretName, wanted, now, "conflict")
              Right(outputs -> Some(wanted -> now))

  private def mint(bucket: String, secretName: String, generation: Long): Map[String, String] =
    val (id, entries) = store.mint(bucket, secretName, generation)
    keys(secretName -> generation) = id
    entries
