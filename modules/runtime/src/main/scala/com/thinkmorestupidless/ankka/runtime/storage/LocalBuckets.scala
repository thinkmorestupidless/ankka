package com.thinkmorestupidless.ankka.runtime.storage

import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.github.plokhotnyuk.jsoniter_scala.macros.*
import com.thinkmorestupidless.ankka.sdk.BucketExposure

import java.nio.file.{Files, Path, Paths}

/**
 * The buckets a service's own `service.json` declares, read where it runs on a developer's machine
 * and in a test (feature 055, research R12), so the directory store and the test kit's store refuse
 * the names a deployed service would. Only the two fields that say which buckets there are; the
 * descriptor's other rules are the control plane's, and the runtime does not depend on it.
 */
object LocalBuckets:

  /** What a descriptor declares: whether the unnamed bucket is asked for, and each named one. */
  final case class Declared(unnamed: Boolean, buckets: Map[String, BucketExposure])

  /** `service.json` in `directory`, read; `None` when there is no such file. */
  def read(directory: Path = Paths.get("").toAbsolutePath): Either[String, Option[Declared]] =
    val file = directory.resolve("service.json")
    if !Files.isRegularFile(file) then Right(None)
    else
      scala.util
        .Try(readFromArray[Descriptor](Files.readAllBytes(file)))
        .toEither
        .left
        .map(e => s"$file cannot be read: ${e.getMessage}")
        .flatMap(declared)
        .map(Some(_))

  private def declared(d: Descriptor): Either[String, Declared] =
    val service = d.service.getOrElse(Service())
    service.buckets
      .foldLeft[Either[String, Map[String, BucketExposure]]](Right(Map.empty)) { (acc, b) =>
        acc.flatMap { done =>
          val exposure =
            if b.supplied.nonEmpty then Right(BucketExposure.Supplied)
            else
              val word = b.exposure.getOrElse("private")
              BucketExposure
                .fromWord(word)
                .filter(_ != BucketExposure.Supplied)
                .toRight(
                  s"buckets: '${b.name}' has exposure '$word'; it is one of private, signed or public"
                )
          exposure.map(e => done + (b.name -> e))
        }
      }
      .map(Declared(service.provisionObjectStorage, _))

  private final case class Descriptor(service: Option[Service] = None)
  private final case class Service(
      provisionObjectStorage: Boolean = false,
      buckets: Vector[BucketEntry] = Vector.empty
  )
  private final case class BucketEntry(
      name: String,
      exposure: Option[String] = None,
      supplied: Option[Supplied] = None
  )
  private final case class Supplied(endpoint: Option[String] = None)

  private given JsonValueCodec[Descriptor] = JsonCodecMaker.make
