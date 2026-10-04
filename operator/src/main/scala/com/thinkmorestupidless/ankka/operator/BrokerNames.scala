package com.thinkmorestupidless.ankka.operator

/**
 * The names a service and its topics have on the installation's broker, in one place.
 *
 * A project's topics share its name and a dot, so one permission on that prefix is the whole of
 * what a service of the project may reach, and the broker refuses every other project's. A project
 * id and a service name are DNS labels, which have no dot, so `a.b-c` and `a-b.c` cannot be the
 * same name.
 */
object BrokerNames:

  /** The service's user on the broker, which is also its certificate's common name. */
  def user(project: String, service: String): String = s"$project.$service"

  /** A declared topic, as the broker holds it. */
  def topic(project: String, name: String): String = topicPrefix(project) + name

  /**
   * What every topic of the project starts with: the one prefix its services may read and write.
   */
  def topicPrefix(project: String): String = s"$project."

  /**
   * What every consumer group of the service starts with: the qualified group id the runtime
   * builds, `ankka.<project>.<service>.<kind>.<component>`.
   */
  def groupPrefix(project: String, service: String): String = s"ankka.$project.$service."
