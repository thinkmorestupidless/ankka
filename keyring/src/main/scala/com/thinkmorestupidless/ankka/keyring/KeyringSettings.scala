package com.thinkmorestupidless.ankka.keyring

import java.net.URI
import java.nio.file.{Path, Paths}

/**
 * What the keyring reads from its environment (feature 042), which `components/keyring` sets. A
 * setting that is wrong is named with every other one that is, and the keyring does not start.
 */
final case class KeyringSettings(
    port: Int,
    probePort: Int,
    /** The control plane, whose `GET /internal/erasure-log` is the log's first copy. */
    controlPlane: URI,
    /** The root key: a KMS key's name on Google Cloud, else 32 bytes kept under the secret key. */
    root: KeyringSettings.Root,
    /** Where machine tokens are verified, when the installation issues them. */
    machines: Option[(String, URI)],
    /** A directory of `<project>/grants.json`, in place of the cluster's ConfigMaps. */
    grantsDirectory: Option[Path],
    /** This replica's name: its pod's. */
    replica: String
)

object KeyringSettings:

  enum Root:
    case Kms(keyName: String)
    case Postgres(secretKey: String)

  val Port          = "ANKKA_KEYRING_PORT"
  val ProbePort     = "ANKKA_KEYRING_PROBE_PORT"
  val ControlPlane  = "ANKKA_CONTROLPLANE_URL"
  val KmsKey        = "ANKKA_KEYRING_KMS_KEY"
  val GrantsDir     = "ANKKA_KEYRING_GRANTS_DIR"
  val MachineIssuer = "ANKKA_MACHINE_ISSUER"
  val MachineJwks   = "ANKKA_MACHINE_JWKS_URL"
  val BaseDomain    = "ANKKA_BASE_DOMAIN"
  val HttpsPort     = "ANKKA_HTTPS_PORT"

  /** Where the control plane serves its machine keys inside the cluster: its `keys` port. */
  val DefaultMachineJwks =
    "https://ankka-controlplane.ankka-controlplane.svc:7629/.well-known/jwks.json"

  def fromEnvironment(env: String => Option[String]): Either[Vector[String], KeyringSettings] =
    def port(name: String, default: Int): Either[String, Int] =
      env(name).fold(Right(default))(text =>
        text.toIntOption.filter(p => p >= 0 && p < 65536).toRight(s"$name is a port, not '$text'")
      )
    def uri(name: String, text: String): Either[String, URI] =
      scala.util
        .Try(URI.create(text))
        .toOption
        .filter(u => u.getScheme == "https" || u.getScheme == "http")
        .toRight(s"$name is an http or https URL, not '$text'")
    val main  = port(Port, 8443)
    val probe = port(ProbePort, 7627)
    val cp    = env(ControlPlane)
      .toRight(s"$ControlPlane names the control plane")
      .flatMap(uri(ControlPlane, _))
    val root = env(KmsKey).filter(_.nonEmpty) match
      case Some(k) => Right(Root.Kms(k))
      case None    =>
        env(com.thinkmorestupidless.ankka.core.PlatformVariables.SecretKey)
          .filter(_.nonEmpty)
          .map(Root.Postgres(_))
          .toRight(
            s"${com.thinkmorestupidless.ankka.core.PlatformVariables.SecretKey} keeps the root key " +
              s"when $KmsKey names no KMS key"
          )
    val machines =
      (env(MachineIssuer).filter(_.nonEmpty), env(MachineJwks).filter(_.nonEmpty)) match
        case (Some(i), Some(j)) => uri(MachineJwks, j).map(u => Some(i -> u))
        case (None, None)       =>
          // The issuer the control plane derives from the installation's base domain and port,
          // its keys read on the control plane's `keys` port, as the operator tells services.
          env(BaseDomain).filter(_.nonEmpty) match
            case None       => Right(None)
            case Some(base) =>
              port(HttpsPort, 443).map(p =>
                Some(
                  s"https://api.$base${if p == 443 then "" else s":$p"}" ->
                    URI.create(DefaultMachineJwks)
                )
              )
        case _ => Left(s"$MachineIssuer and $MachineJwks are set together or not at all")
    val problems = Vector(main, probe, cp, root, machines).collect { case Left(p) => p }
    if problems.nonEmpty then Left(problems)
    else
      Right(
        KeyringSettings(
          main.toOption.get,
          probe.toOption.get,
          cp.toOption.get,
          root.toOption.get,
          machines.toOption.get,
          env(GrantsDir).filter(_.nonEmpty).map(Paths.get(_)),
          env("HOSTNAME").filter(_.nonEmpty).getOrElse(java.util.UUID.randomUUID().toString)
        )
      )
