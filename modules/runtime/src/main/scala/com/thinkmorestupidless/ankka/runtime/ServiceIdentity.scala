package com.thinkmorestupidless.ankka.runtime

import com.typesafe.config.Config

import java.io.ByteArrayInputStream
import java.nio.file.{Files, NoSuchFileException, Paths}
import java.security.cert.{CertificateFactory, X509Certificate}
import scala.jdk.CollectionConverters.*

/**
 * Who this service is: what names the consumer group each of its topic sources reads under.
 *
 * Three shapes and no others. A deployed service has a project and a name; a service run locally
 * that states its name has a name and no project; one that states nothing has neither. A project
 * without a name cannot be built.
 *
 * A deployed service's identity is read from the certificate the platform issued it,
 * `ankka://<project>/<service>`, and never from its configuration: configuration can be written by
 * the service itself (its own `application.conf` sits above the platform's overlay), and a service
 * that could name its groups as another's would read the other's messages. A local run states its
 * name with `ankka.service.name`, which the Kubernetes overlay never reads.
 */
final case class ServiceIdentity private (project: Option[String], service: Option[String]):

  override def toString: String = (project, service) match
    case (Some(p), Some(s)) => s"$p/$s"
    case (None, Some(s))    => s"local/$s"
    case _                  => "unnamed"

object ServiceIdentity:

  /** Where a local run states its name; `ANKKA_SERVICE_NAME` sets it. */
  val NameKey: String = "ankka.service.name"

  /**
   * The project a named local run's groups are named in, which is why no deployed project may take
   * it.
   */
  val LocalProject: String = "local"

  /** A DNS label, as a deployed service's name is — so no name can carry a `.`. */
  private val ValidName = "[a-z]([-a-z0-9]{0,61}[a-z0-9])?".r

  /** A service run locally that states no name: its groups keep the names they always had. */
  val unnamed: ServiceIdentity = ServiceIdentity(None, None)

  /** A service run locally that states `name`. */
  def local(name: String): ServiceIdentity =
    nameProblem(name).foreach(problem => throw IllegalArgumentException(problem))
    ServiceIdentity(None, Some(name))

  /**
   * A deployed service. Only the platform makes one, from a certificate; outside ankka's own tests
   * the way to be a deployed service is to be deployed.
   */
  private[ankka] def deployed(project: String, service: String): ServiceIdentity =
    if project == LocalProject then
      throw IllegalArgumentException(
        s"project '$project' is reserved for services run locally and cannot be deployed"
      )
    require(project.nonEmpty && service.nonEmpty, "a deployed identity names both")
    ServiceIdentity(Some(project), Some(service))

  private def nameProblem(name: String): Option[String] =
    Option.when(!ValidName.matches(name))(
      s"$NameKey '$name' is not a service name: lowercase letters, digits and '-', starting " +
        "with a letter, at most 63 characters"
    )

  /**
   * Reads the identity from where the process runs, or says what is wrong.
   *
   * Running under Kubernetes (formation `bootstrap`), the service certificate's `ankka://` URI; a
   * certificate that carries none, or a directory that holds none, is the answer's `Left`, naming
   * the file. Running locally, `ankka.service.name`, empty meaning none stated.
   *
   * Never throws. A service with no topic source never looks at the answer, so a `Left` must not
   * stop one from starting.
   */
  def resolve(config: Config): Either[String, ServiceIdentity] =
    def string(key: String) = if config.hasPath(key) then config.getString(key) else ""

    if string(ClusterFormation.FormationKey) == ClusterFormation.Bootstrap then
      fromCertificateDirectory(string(ServiceDirectoryKey))
    else
      string(NameKey).trim match
        case ""   => Right(unnamed)
        case name => nameProblem(name).toLeft(ServiceIdentity(None, Some(name)))

  private val ServiceDirectoryKey = "ankka.tls.service-directory"

  private[runtime] def fromCertificateDirectory(
      directory: String
  ): Either[String, ServiceIdentity] =
    if directory.isEmpty then
      Left(
        s"$ServiceDirectoryKey is not set, so this deployed service cannot read its identity " +
          "from its certificate"
      )
    else
      val file = Paths.get(directory).resolve("tls.crt")
      try
        val pem = Files.readAllBytes(file)
        val certificates = CertificateFactory
          .getInstance("X.509")
          .generateCertificates(ByteArrayInputStream(pem))
          .asScala
          .collect { case c: X509Certificate => c }
          .toVector
        fromCertificate(certificates.headOption, file.toString)
      catch
        case _: NoSuchFileException =>
          Left(s"no certificate at $file, so this deployed service cannot read its identity")
        case failure: Exception =>
          Left(s"the certificate at $file could not be read: ${failure.getMessage}")

  private[runtime] def fromCertificate(
      certificate: Option[X509Certificate],
      describedAs: String
  ): Either[String, ServiceIdentity] =
    certificate.flatMap(RotatingTls.identityOf) match
      case None =>
        Left(s"the certificate at $describedAs carries no ankka://<project>/<service> identity")
      case Some(RotatingTls.Identity(LocalProject, service)) =>
        Left(
          s"the certificate at $describedAs names the project '$LocalProject', which is reserved " +
            s"for services run locally (ankka://$LocalProject/$service)"
        )
      case Some(RotatingTls.Identity(project, service)) =>
        Right(ServiceIdentity(Some(project), Some(service)))
