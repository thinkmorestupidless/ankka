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
