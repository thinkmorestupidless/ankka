package com.thinkmorestupidless.ankka.runtime.storage

import com.thinkmorestupidless.ankka.sdk.*

import java.net.http.HttpClient
import java.time.Duration as JDuration

/** The store a service was given, and what was chosen. */
final case class BuiltStorage(
    backend: StorageBackend,
    storage: ObjectStorage,
    directory: Option[DirectoryObjectStorage]
)

/**
 * The one place a service's store is chosen (feature 055), from `StorageSettings`: the S3 store for
 * the buckets the variables name, the directory store on a developer's machine, or none. The memory
 * store is the test kit's and is handed to the service, never built here.
 */
object ObjectStorages:

  def build(
      settings: StorageSettings,
      localBuckets: => Either[String, Option[LocalBuckets.Declared]],
      addressBase: () => String,
      client: Option[HttpClient] = None
  ): Either[String, BuiltStorage] =
    settings.backend match
      case StorageBackend.Unavailable =>
        Right(BuiltStorage(StorageBackend.Unavailable, ObjectStorage.unavailable, None))
      case StorageBackend.Memory =>
        Left(
          "ankka.storage.backend is memory, which is the test kit's store: a test hands it to the service " +
            "with ServiceBuilder.withStorage"
        )
      case StorageBackend.Directory =>
        for
          declared  <- localBuckets
          directory <- DirectoryObjectStorage.open(settings.directory, declared, addressBase)
        yield BuiltStorage(StorageBackend.Directory, directory, Some(directory))
      case StorageBackend.S3 =>
        val http = client.getOrElse(defaultClient(settings))
        def bucket(
            target: S3Target,
            name: Option[String],
            exposure: BucketExposure,
            publicUrl: Option[String]
        ) =
          S3Bucket(target, name, exposure, publicUrl, settings.timeout, settings.maxSignedUrl, http)
        val unnamed = settings.unnamed.map(t => bucket(t, None, settings.unnamedExposure, None))
        val named   = settings.named
          .map(n => n.name -> bucket(n.target, Some(n.name), n.exposure, n.publicUrl))
          .toMap
        Right(BuiltStorage(StorageBackend.S3, S3ObjectStorage(unnamed, named), None))

  private def defaultClient(settings: StorageSettings): HttpClient =
    HttpClient
      .newBuilder()
      .version(HttpClient.Version.HTTP_1_1)
      .connectTimeout(JDuration.ofMillis(settings.timeout.toMillis))
      .build()

/** The S3 store's buckets, by name. */
final class S3ObjectStorage(unnamed: Option[Bucket], named: Map[String, Bucket])
    extends ObjectStorage:
  def default: Bucket              = unnamed.getOrElse(throw StorageRefusals.noBucket())
  def bucket(name: String): Bucket =
    named.getOrElse(name, throw StorageRefusals.unknown(name, named.keys))
  def buckets: Vector[Bucket] = named.values.toVector
