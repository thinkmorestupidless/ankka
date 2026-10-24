package com.thinkmorestupidless.ankka.runtime.storage

import com.thinkmorestupidless.ankka.core.PlatformVariables
import com.thinkmorestupidless.ankka.sdk.BucketExposure
import com.typesafe.config.Config

import java.net.URI
import java.nio.file.{Path, Paths}
import scala.concurrent.duration.*
import scala.jdk.DurationConverters.*

/** What stands behind every bucket of a service. */
enum StorageBackend:
  case S3, Directory, Memory

  /**
   * No store: every call refused, naming `provisionObjectStorage`. A deployed service with no
   * bucket.
   */
  case Unavailable

/** One S3 bucket and how to reach it. */
final case class S3Target(
    endpoint: URI,
    region: String,
    bucket: String,
    credentials: Option[SigV4.Credentials],
    publicEndpoint: Option[URI]
)

/**
 * A bucket the descriptor names: its exposure, where it is, and for a public one the base it is
 * read at.
 */
final case class NamedTarget(
    name: String,
    exposure: BucketExposure,
    target: S3Target,
    publicUrl: Option[String]
)

/**
 * The storage client's settings: `ankka.storage` from the configuration, and the buckets from the
 * `ANKKA_S3_*` variables the platform renders or a service supplies (feature 055).
 *
 * The unnamed bucket is 034's family,
 * `ANKKA_S3_{ENDPOINT,REGION,BUCKET,ACCESS_KEY,SECRET_KEY,PUBLIC_ENDPOINT}`. A named bucket the
 * platform made is `ANKKA_S3_BUCKET_<NAME>`, `ANKKA_S3_EXPOSURE_<NAME>` and, for a public one,
 * `ANKKA_S3_PUBLIC_URL_<NAME>`, sharing the unnamed family's endpoint, region and key. A supplied
 * bucket is the five `ANKKA_S3_{ENDPOINT,REGION,BUCKET,ACCESS_KEY,SECRET_KEY}_<NAME>` of its own.
 * `<NAME>` is `PlatformVariables.bucketVariableSuffix` of the bucket's name.
 */
final case class StorageSettings(
    backend: StorageBackend,
    directory: Path,
    timeout: FiniteDuration,
    maxSignedUrl: FiniteDuration,
    defaultSignedUrl: FiniteDuration,
    unnamed: Option[S3Target],
    named: Vector[NamedTarget]
):
  /** The unnamed bucket's exposure: signed when the service may sign URLs for it, else private. */
  def unnamedExposure: BucketExposure =
    if unnamed.exists(_.publicEndpoint.nonEmpty) then BucketExposure.Signed
    else BucketExposure.Private

object StorageSettings:

  private val Prefix = PlatformVariables.ObjectStoragePrefix

  /**
   * `ankka.storage.variables`, when a configuration has it: variables laid over the environment, so
   * one service in a test JVM can be given a store of its own (the test kit's
   * `StorageBackendChoice.FromEnvironment`). Absent from `reference.conf`; nothing on the platform
   * sets it.
   */
  private def overlay(c: Config): Map[String, String] =
    if !c.hasPath("variables") then Map.empty
    else
      import scala.jdk.CollectionConverters.*
      val vars = c.getConfig("variables")
      vars.root.keySet.asScala.map(k => k -> vars.getString(k)).toMap

  /**
   * Reads the settings. `runningLocally` is the runtime's test (formation not `bootstrap`): only a
   * service on a developer's machine is given the directory store when nothing else is named.
   */
  def from(
      config: Config,
      environment: Map[String, String],
      runningLocally: Boolean
  ): Either[String, StorageSettings] =
    val c   = config.getConfig("ankka.storage")
    val env = environment ++ overlay(c)
    for
      unnamed <- unnamedTarget(env)
      named   <- namedTargets(env, unnamed)
      backend <- chooseBackend(c.getString("backend").trim, unnamed, named, runningLocally)
    yield StorageSettings(
      backend = backend,
      directory = Paths.get(c.getString("directory")).toAbsolutePath.normalize,
      timeout = c.getDuration("timeout").toScala,
      maxSignedUrl = c.getDuration("max-signed-url").toScala,
      defaultSignedUrl = c.getDuration("default-signed-url").toScala,
      unnamed = unnamed.filter(_.bucket.nonEmpty),
      named = named
    )

  private def chooseBackend(
      word: String,
      unnamed: Option[S3Target],
      named: Vector[NamedTarget],
      runningLocally: Boolean
  ): Either[String, StorageBackend] =
    val anyS3 = unnamed.nonEmpty || named.nonEmpty
    word match
      case "" =>
        Right(
          if anyS3 then StorageBackend.S3
          else if runningLocally then StorageBackend.Directory
          else StorageBackend.Unavailable
        )
      case "s3" =>
        if anyS3 then Right(StorageBackend.S3)
        else
          Left(
            s"${PlatformVariables.StorageBackend} is s3 and ${Prefix}ENDPOINT is not set: the s3 store needs an endpoint"
          )
      case "directory" => Right(StorageBackend.Directory)
      case "memory"    => Right(StorageBackend.Memory)
      case other       =>
        Left(
          s"${PlatformVariables.StorageBackend} is '$other'; it is one of s3, directory or memory"
        )

  /**
   * The unnamed family's endpoint, region and key, with an empty bucket when only named buckets
   * share them.
   */
  private def unnamedTarget(env: Map[String, String]): Either[String, Option[S3Target]] =
    env.get(Prefix + "ENDPOINT").filter(_.nonEmpty) match
      case None           => Right(None)
      case Some(endpoint) =>
        for
          uri    <- parseUri(Prefix + "ENDPOINT", endpoint)
          region <- required(env, Prefix + "REGION")
          public <- env.get(Prefix + "PUBLIC_ENDPOINT").filter(_.nonEmpty) match
            case None    => Right(None)
            case Some(p) => parseUri(Prefix + "PUBLIC_ENDPOINT", p).map(Some(_))
        yield Some(
          S3Target(
            uri,
            region,
            env.getOrElse(Prefix + "BUCKET", ""),
            credentials(env, Prefix + "ACCESS_KEY", Prefix + "SECRET_KEY"),
            public
          )
        )

  private def namedTargets(
      env: Map[String, String],
      shared: Option[S3Target]
  ): Either[String, Vector[NamedTarget]] =
    val suffixes =
      env.keys.toVector
        .flatMap(k =>
          Vector(Prefix + "BUCKET_", Prefix + "ENDPOINT_").collect {
            case p if k.startsWith(p) => k.drop(p.length)
          }
        )
        .filter(_.nonEmpty)
        .distinct
        .sorted
    suffixes.foldLeft[Either[String, Vector[NamedTarget]]](Right(Vector.empty)) { (acc, suffix) =>
      acc.flatMap(done => namedTarget(env, suffix, shared).map(done :+ _))
    }

  private def namedTarget(
      env: Map[String, String],
      suffix: String,
      shared: Option[S3Target]
  ): Either[String, NamedTarget] =
    val name = PlatformVariables.bucketNameOf(suffix)
    env.get(s"${Prefix}ENDPOINT_$suffix").filter(_.nonEmpty) match
      case Some(endpoint) =>
        for
          uri    <- parseUri(s"${Prefix}ENDPOINT_$suffix", endpoint)
          region <- required(env, s"${Prefix}REGION_$suffix")
          bucket <- required(env, s"${Prefix}BUCKET_$suffix")
          access <- required(env, s"${Prefix}ACCESS_KEY_$suffix")
          secret <- required(env, s"${Prefix}SECRET_KEY_$suffix")
        yield NamedTarget(
          name,
          BucketExposure.Supplied,
          S3Target(uri, region, bucket, Some(SigV4.Credentials(access, secret)), None),
          None
        )
      case None =>
        for
          bucket <- required(env, s"${Prefix}BUCKET_$suffix")
          base   <- shared.toRight(
            s"bucket '$name' (${Prefix}BUCKET_$suffix) is made by the platform and ${Prefix}ENDPOINT is not set"
          )
          exposure <- env.get(s"${Prefix}EXPOSURE_$suffix").filter(_.nonEmpty) match
            case None       => Right(BucketExposure.Private)
            case Some(word) =>
              BucketExposure
                .fromWord(word)
                .filter(_ != BucketExposure.Supplied)
                .toRight(
                  s"${Prefix}EXPOSURE_$suffix is '$word'; it is one of private, signed or public"
                )
          publicUrl = env
            .get(s"${Prefix}PUBLIC_URL_$suffix")
            .filter(_.nonEmpty)
            .map(_.stripSuffix("/"))
          _ <-
            if exposure == BucketExposure.Public && publicUrl.isEmpty then
              Left(s"bucket '$name' is public and ${Prefix}PUBLIC_URL_$suffix is not set")
            else Right(())
        yield NamedTarget(name, exposure, base.copy(bucket = bucket), publicUrl)

  private def credentials(
      env: Map[String, String],
      access: String,
      secret: String
  ): Option[SigV4.Credentials] =
    for
      a <- env.get(access).filter(_.nonEmpty)
      s <- env.get(secret).filter(_.nonEmpty)
    yield SigV4.Credentials(a, s)

  private def required(env: Map[String, String], name: String): Either[String, String] =
    env.get(name).filter(_.nonEmpty).toRight(s"$name is not set")

  private def parseUri(name: String, value: String): Either[String, URI] =
    scala.util
      .Try(URI.create(value))
      .toEither
      .left
      .map(_ => s"$name is not a URL: '$value'")
      .filterOrElse(
        u => u.getScheme != null && u.getHost != null,
        s"$name is not a URL with a scheme and a host: '$value'"
      )
