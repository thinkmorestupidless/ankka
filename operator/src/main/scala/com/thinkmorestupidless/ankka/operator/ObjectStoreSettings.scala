package com.thinkmorestupidless.ankka.operator

/**
 * Where the installation's object store is, and what a workload is told about it (feature 034).
 *
 * @param adminUrl
 *   the administration API, which only the operator calls
 * @param adminToken
 *   its bearer token. Never logged; `toString` leaves it out
 * @param endpoint
 *   what a workload is given as `ANKKA_S3_ENDPOINT`: the S3 API inside the cluster
 * @param region
 *   what a workload is given as `ANKKA_S3_REGION`. A client signs for a region, and the store
 *   refuses a signature made for any other
 * @param service
 *   the store's Service, which a bucket reachable from the internet is routed to
 */
final case class ObjectStoreSettings(
    adminUrl: String,
    adminToken: String,
    endpoint: String,
    region: String,
    service: ObjectStoreSettings.ServiceRef
):
  override def toString: String =
    s"ObjectStoreSettings($adminUrl, <token>, $endpoint, $region, $service)"

/** The store an installation makes new buckets in (feature 039). */
enum ObjectStoreBackend:
  case Garage, Gcs

object ObjectStoreBackend:
  /** `garage` or `gcs`, or the value as given when it is neither. */
  def parse(value: String): Either[String, ObjectStoreBackend] =
    value.toLowerCase match
      case "garage" => Right(Garage)
      case "gcs"    => Right(Gcs)
      case _        => Left(value)

/**
 * Google Cloud Storage, as the operator renders a service's bucket in it (feature 039). The bucket
 * itself, its account and its key are the cloud provider's to make (feature 044); these are what
 * the operator asks for and tells a workload.
 *
 * @param prefix
 *   the installation's prefix for a bucket's name, which names are shared with every other customer
 *   of Google's
 * @param softDeleteDays
 *   how long a new bucket keeps a deleted object, 7 to 90 days
 * @param endpoint
 *   what a workload is given as `ANKKA_S3_ENDPOINT`. Google's address as shipped; a suite points it
 *   at a store of its own
 */
final case class GcsSettings(prefix: String, softDeleteDays: Int, endpoint: String)

object GcsSettings:
  val GoogleEndpoint: String          = "https://storage.googleapis.com"
  val DefaultSoftDeleteDays: Int      = 7
  val SoftDeleteDays: Range.Inclusive = 7 to 90

object ObjectStoreSettings:

  /** A Service in another namespace, as a route's backend names it. */
  final case class ServiceRef(namespace: String, name: String, port: Int):
    override def toString: String = s"$namespace/$name:$port"

  object ServiceRef:
    private val Form = """([a-z0-9-]+)/([a-z0-9-]+):(\d{1,5})""".r

    def parse(value: String): Option[ServiceRef] =
      value match
        case Form(namespace, name, port) => Some(ServiceRef(namespace, name, port.toInt))
        case _                           => None
