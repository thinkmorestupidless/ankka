package nakka.operator

/**
 * Every name the operator renders, in one place.
 *
 * Naming is where a Kubernetes controller goes wrong quietly: a name that is one character too
 * long, or that is computed slightly differently in two places, produces an object the operator
 * then cannot find again.
 */
object Names:

  /** Kubernetes DNS label ceiling. Namespaces, Deployments and containers all obey it. */
  val MaxLabelLength: Int = 63

  private val ValidLabel = "[a-z]([-a-z0-9]{0,61}[a-z0-9])?".r

  def isLabel(value: String): Boolean = ValidLabel.matches(value)

  /**
   * One namespace per project.
   *
   * Per project rather than per service because a project is the tenancy boundary, and because
   * owner references only work within a namespace — the resource has to live beside the objects it
   * owns for cascade deletion to be structural.
   */
  def namespace(prefix: String, projectId: String): String = s"$prefix-$projectId"

  /** Problems with a rendered namespace name, reported all at once. */
  def namespaceProblems(prefix: String, projectId: String): Vector[String] =
    val rendered = namespace(prefix, projectId)
    Vector(
      Option.when(projectId.isEmpty)("project id must not be empty"),
      Option.when(projectId.nonEmpty && !isLabel(projectId))(
        s"project id '$projectId' is not a DNS label"
      ),
      Option.when(rendered.length > MaxLabelLength)(
        s"namespace '$rendered' is ${rendered.length} characters, over the $MaxLabelLength limit"
      )
    ).flatten

  /** The Deployment, the container, the resource and the Service all share the service's name. */
  def deployment(serviceName: String): String = serviceName

  def container(serviceName: String): String = serviceName

  /**
   * The Service, which is also the service's in-cluster address: `<name>` within the project's
   * namespace, `<name>.<namespace>.svc.cluster.local` from outside it. Sharing the name is what
   * makes that address predictable without looking anything up.
   */
  def service(serviceName: String): String = serviceName

  /** What the service's pods run as. One per service, never the namespace default. */
  def serviceAccount(serviceName: String): String = serviceName

  /** The Role and RoleBinding that let those pods read their own project's pods. */
  def peersRole(serviceName: String): String = s"$serviceName-peers"

  def serviceNameProblems(serviceName: String): Vector[String] =
    if serviceName.isEmpty then Vector("service name must not be empty")
    else if !isLabel(serviceName) then Vector(s"service name '$serviceName' is not a DNS label")
    else Vector.empty
