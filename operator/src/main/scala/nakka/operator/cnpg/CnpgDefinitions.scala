package nakka.operator.cnpg

import io.fabric8.kubernetes.api.model.HasMetadata

/**
 * CloudNativePG's identity, shared by the three kinds nakka renders.
 *
 * One group and version for `Cluster`, `Database` and `DatabaseRole` alike — CNPG ships them
 * together and versions them together, unlike nakka's own `NakkaService`, which is why this is one
 * object rather than three copies of `NakkaServiceDefinition`'s shape.
 *
 * Serialization needs no separate setup here: the operator's single `KubernetesClient` is already
 * built with `nakka.crd.NakkaSerialization()` (Scala-aware Jackson), and that serializer is
 * client-wide, not per-resource-kind — so CNPG's partial models get the same `Option`/collection
 * handling for free.
 */
/**
 * The status shape `Database` and `DatabaseRole` share: whether CNPG's reconciler considers the
 * object settled, and its own words for why not. Lets `Executor.observeDatabase` read either kind
 * through one function instead of two copies of the same `Option` plumbing.
 */
trait CnpgReconcileStatus:
  def applied: Boolean
  def message: Option[String]

object CnpgDefinitions:

  val Group: String   = "postgresql.cnpg.io"
  val Version: String = "v1"

  /**
   * Derives kind/plural/apiVersion from a model's own fabric8 annotations, the same way
   * `NakkaServiceDefinition` derives them for `NakkaService` — so the three CNPG model companions
   * below share one implementation rather than repeating the reflection calls.
   */
  final case class Identity(kind: String, plural: String, apiVersion: String, crdName: String)

  def identityOf(resourceClass: Class[?]): Identity =
    val kind   = HasMetadata.getKind(resourceClass)
    val plural = HasMetadata.getPlural(resourceClass)
    Identity(
      kind = kind,
      plural = plural,
      apiVersion = HasMetadata.getApiVersion(resourceClass),
      crdName = s"$plural.${HasMetadata.getGroup(resourceClass)}"
    )
