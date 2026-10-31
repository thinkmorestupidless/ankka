package com.thinkmorestupidless.ankka.core

/**
 * The rules for the names a project is made of: a service's, a project's and a topic's. Kept here,
 * below everything that applies them, so the control plane, the CLI and a project's own checks of
 * its context map refuse the same names with the same words. A contract's name is `Contract`'s.
 */
object Names:

  /**
   * A service's name: a Kubernetes DNS label, because it becomes a Deployment and a Service name,
   * and the descriptor that cannot be expressed in Kubernetes is refused when it is applied.
   */
  val ServiceNamePattern: scala.util.matching.Regex = "[a-z]([-a-z0-9]{0,61}[a-z0-9])?".r

  def serviceNameProblems(name: String): Vector[String] =
    if name.isEmpty then Vector("service name must not be empty")
    else if !ServiceNamePattern.matches(name) then
      Vector(
        s"service name '$name' is invalid: lowercase letters, digits and '-', " +
          "starting with a letter"
      )
    else Vector.empty

  /**
   * A topic's name: one the broker can hold under a project's prefix, and one the topic's resource
   * can be named for, which keeps `<project>.<name>` within Kafka's limit too.
   */
  val TopicNameRule: String =
    "a name is lower-case letters, digits, \"-\" and \".\", starting and ending with a letter " +
      "or digit, at most 100 characters"

  private val TopicName = "[a-z0-9]([a-z0-9.-]{0,98}[a-z0-9])?".r

  def validTopicName(name: String): Boolean = TopicName.matches(name)

  /** A project's id: part of a namespace name, of a workload's identity and of consumer groups. */
  object Project:

    private val Valid = "[a-z]([-a-z0-9]{0,61}[a-z0-9])?".r

    /**
     * Conservative, because a client cannot see the server's namespace prefix. The tighter bound is
     * the project's backup bucket, `platform.backups.<id>`, which must fit in a bucket's 63
     * characters.
     */
    val MaxLength: Int = 63 - "platform.backups.".length

    /** `ankka-<id>-rehearsal` is the namespace a project's rehearsals run in. */
    val RehearsalSuffix: String = "-rehearsal"

    /**
     * Ids no project may take, because the platform's own workloads use them.
     *
     * A workload's identity is `ankka://<project>/<service>`, read from its certificate, and it is
     * the whole of what another service's ACL or a platform listener trusts. The control plane is
     * `ankka://platform/controlplane` and the console `ankka://platform/console`, so a tenant
     * project called `platform` with a service called `controlplane` would be issued the control
     * plane's identity. The operator refuses the same ids (`Names.ReservedProjectIds`), since a
     * resource can be written by something other than the control plane, and
     * `ReservedProjectIdsSuite` holds the two lists to each other and to the identities the
     * platform's manifests ask for.
     *
     * `local` is reserved for the other reason a project's id is part of a name: a service run on a
     * developer's machine that states its name reads a topic under the group
     * `ankka.local.<service>.…`, which is exactly what a deployed service in a project called
     * `local` would be given. Two different services must never share a group.
     *
     * `cloud-provider` is reserved because a project's namespace is `ankka-<project>`, and the
     * installation's cloud provider runs in `ankka-cloud-provider` (feature 044). `machine` is
     * reserved for the same reason: a registered machine reads a topic under the group prefix
     * `ankka.machine.<organization>.<name>.`, so a project called `machine` with a service named
     * after an organization would hold read on that organization's machines' groups.
     */
    val Reserved: Set[String] = Set("platform", "local", "cloud-provider", "machine")

    /** Why `id` is reserved, in a sentence that is true of it. */
    def reservedBecause(id: String): String =
      if id == "local" then
        s"project id '$id' is reserved for services run locally, whose consumer groups it names"
      else if id == "cloud-provider" then
        s"project id '$id' is reserved for the namespace the installation's cloud provider runs in"
      else if id == "machine" then
        s"project id '$id' is reserved for registered machines, whose consumer groups it names"
      else s"project id '$id' is reserved for the platform's own workloads"

    def problems(id: String): Vector[String] =
      if id.isEmpty then Vector("project id must not be empty")
      else if id.length > MaxLength then
        Vector(s"project id '$id' is ${id.length} characters, over the $MaxLength character limit")
      else if !Valid.matches(id) then
        Vector(
          s"project id '$id' is invalid: lowercase letters, digits and '-', starting with a letter"
        )
      else if Reserved.contains(id) then Vector(reservedBecause(id))
      else if id.endsWith(RehearsalSuffix) then
        Vector(
          s"project id '$id' ends in '$RehearsalSuffix', which names another project's rehearsal " +
            "namespace"
        )
      else Vector.empty
