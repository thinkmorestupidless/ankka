package com.thinkmorestupidless.ankka.runtime

import java.util.concurrent.ConcurrentHashMap

/**
 * The other services a topology shows by name: the first ones a service calls, up to a limit.
 *
 * A service's name is whatever is passed to `services(...)`, and that can come from a request, so
 * the names are not bounded by what the code says. They are bounded here instead: a name is given
 * room while there is room, and a call to any service after that is counted under the one name the
 * rest share. No call goes uncounted, and the recorder's table of names cannot be grown by asking
 * for services that do not exist.
 */
final class ExternalServices(limit: Int, methodLimit: Int = ExternalServices.DefaultMethodLimit):

  private val admitted = ConcurrentHashMap.newKeySet[String]()
  private val methods  = ConcurrentHashMap.newKeySet[String]()

  /**
   * The name a call to this gRPC method of another service is recorded under. A method's full name
   * comes from a compiled service definition, so it is bounded by code; it is interned all the
   * same, and nothing interned may be unbounded, so it is admitted up to a limit like a service.
   */
  def methodFor(fullName: String): String =
    if methods.contains(fullName) then fullName
    else
      synchronized {
        if methods.contains(fullName) then fullName
        else if methods.size < methodLimit then
          methods.add(fullName): Unit
          fullName
        else ExternalServices.OtherMethods
      }

  /** The name a call to this service is counted under. */
  def nameFor(project: String, service: String): String =
    val name = ExternalServices.nameOf(project, service)
    if admitted.contains(name) then name
    else
      synchronized {
        if admitted.contains(name) then name
        else if admitted.size < limit then
          admitted.add(name): Unit
          name
        else ExternalServices.Other
      }

object ExternalServices:

  /** A service as a topology names it. The same name is the node's id. */
  def nameOf(project: String, service: String): String = s"service:$project/$service"

  /** Every service beyond the limit, together. */
  val Other: String = s"service:${CallCounts.OtherServices}"

  /** Every gRPC method of another service beyond the limit, together. */
  val OtherMethods: String = "(other methods)"

  val DefaultMethodLimit: Int = 256
