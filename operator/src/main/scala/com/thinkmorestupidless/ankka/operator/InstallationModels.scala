package com.thinkmorestupidless.ankka.operator

import com.thinkmorestupidless.ankka.core.{ModelServerDocument, ModelState}
import com.thinkmorestupidless.ankka.crd.{AnkkaServiceSpec, InstallationModelsStatus, ServedModel}

/** What the operator does about a service's installation models this pass (feature 053). */
enum InstallationModelsPlan:
  /** The service names none: nothing is read, rendered or reported. */
  case NotNeeded

  /** Every model the service names is served, with these digests. */
  case Ready(models: Vector[ServedModel])

  /**
   * A model is still being fetched, or the server's status cannot be read: held back, in progress.
   */
  case Waiting(detail: String)

  /** A model is not served, or the service named one wrongly: held back, failed. */
  case Failed(detail: String)

/**
 * Which installation models a service names, and whether this cluster serves them (feature 053).
 *
 * A service names one through a model provider whose preset is `installation`
 * (`ANKKA_MODEL_<NAME>_PRESET=installation`, feature 052's family), with the model in
 * `ANKKA_MODEL_<NAME>_MODEL`. The platform supplies the address and admits the service by its
 * certificate, so an address or a key beside the preset is refused rather than ignored.
 *
 * The decision is pure: the caller reads the model server's document once and hands it in, as
 * `ObjectStorage.decide` is handed what the store answered. Its details are the words a member
 * reads in `services get`.
 */
object InstallationModels:

  /** The preset's value that names the installation's model server. */
  val Preset: String = "installation"

  private val PresetVariable = "^ANKKA_MODEL_([A-Z0-9_]+)_PRESET$".r

  /** A model provider of the service whose preset is `installation`. */
  final case class Named(
      provider: String,
      variable: String,
      model: Option[String],
      address: Option[String],
      key: Option[String]
  )

  /** Every provider the service names with the preset, in the order its descriptor lists them. */
  def named(spec: AnkkaServiceSpec): Vector[Named] =
    val declared = spec.env.map(e => e.name -> e).toMap
    def present(name: String): Option[String] =
      declared
        .get(name)
        .flatMap(e => e.value.filter(_.nonEmpty).orElse(e.secretName.map(_ => "(secret)")))
    spec.env.toVector.flatMap { entry =>
      entry.name match
        case PresetVariable(name) if entry.value.exists(_.trim.equalsIgnoreCase(Preset)) =>
          val prefix = s"ANKKA_MODEL_${name}_"
          Some(
            Named(
              provider = name.toLowerCase,
              variable = prefix,
              model =
                declared.get(s"${prefix}MODEL").flatMap(_.value).map(_.trim).filter(_.nonEmpty),
              address = present(s"${prefix}URL").map(_ => s"${prefix}URL"),
              key = present(s"${prefix}KEY").map(_ => s"${prefix}KEY")
            )
          )
        case _ => None
    }

  /** Ollama's own form of a tag: `llama3` is `llama3:latest`, as the keeper reads a declaration. */
  def canonical(tag: String): String =
    val name = tag.trim
    val last = name.substring(name.lastIndexOf('/') + 1)
    if last.contains(':') then name else s"$name:latest"

  val NoServer: String     = "this cluster has no model server"
  val NotAnswering: String = "the model server is not answering"

  def decide(
      named: Vector[Named],
      settings: Option[ModelServerSettings],
      observed: => Either[String, ModelServerDocument]
  ): InstallationModelsPlan =
    if named.isEmpty then InstallationModelsPlan.NotNeeded
    else
      val misnamed = named.collectFirst {
        case n if n.address.isDefined =>
          s"an installation model takes no address (${n.address.get})"
        case n if n.key.isDefined => s"an installation model takes no key (${n.key.get})"
        case n if n.model.isEmpty =>
          s"an installation model is named by ${n.variable}MODEL, which is not set"
      }
      misnamed match
        case Some(why) => InstallationModelsPlan.Failed(why)
        case None =>
          settings match
            case None => InstallationModelsPlan.Failed(NoServer)
            case Some(_) =>
              observed match
                case Left(_)         => InstallationModelsPlan.Waiting(NotAnswering)
                case Right(document) => against(named.flatMap(_.model).distinct, document)

  private def against(
      models: Vector[String],
      document: ModelServerDocument
  ): InstallationModelsPlan =
    val lines  = document.models.map(m => canonical(m.name) -> m).toMap
    val served = document.served
    def notServed(m: String) =
      s"installation model \"$m\" is not served; served: ${
          if served.isEmpty then "none" else served.mkString(", ")
        }"
    val each = models.map { m =>
      lines.get(canonical(m)) match
        case Some(line) if line.state == ModelState.Served =>
          Right(ServedModel(m, line.digest.getOrElse("")))
        case Some(line) if line.state == ModelState.Fetching =>
          Left(Left(s"installation model \"$m\" is still being fetched"))
        case Some(line) if line.state == ModelState.Failed =>
          Left(
            Right(s"installation model \"$m\" failed: ${line.detail.getOrElse("no reason given")}")
          )
        case _ => Left(Right(notServed(m)))
    }
    each.collectFirst { case Left(Right(failed)) => failed } match
      case Some(why) => InstallationModelsPlan.Failed(why)
      case None =>
        each.collectFirst { case Left(Left(waiting)) => waiting } match
          case Some(why) => InstallationModelsPlan.Waiting(why)
          case None      => InstallationModelsPlan.Ready(each.collect { case Right(m) => m })

  /** Why the Deployment is held back this pass, or nothing. */
  def withheld(plan: InstallationModelsPlan): Option[String] = plan match
    case InstallationModelsPlan.Waiting(why) => Some(why)
    case InstallationModelsPlan.Failed(why)  => Some(why)
    case _                                   => None

  /** Why the service is failed, or nothing. */
  def refused(plan: InstallationModelsPlan): Option[String] = plan match
    case InstallationModelsPlan.Failed(why) => Some(why)
    case _                                  => None

  /** The resource's status block; absent for a service that names none. */
  def status(plan: InstallationModelsPlan): Option[InstallationModelsStatus] = plan match
    case InstallationModelsPlan.NotNeeded => None
    case InstallationModelsPlan.Ready(models) =>
      Some(InstallationModelsStatus("Ready", models.toList))
    case InstallationModelsPlan.Waiting(why) =>
      Some(InstallationModelsStatus("Waiting", Nil, Some(why)))
    case InstallationModelsPlan.Failed(why) =>
      Some(InstallationModelsStatus("Failed", Nil, Some(why)))
