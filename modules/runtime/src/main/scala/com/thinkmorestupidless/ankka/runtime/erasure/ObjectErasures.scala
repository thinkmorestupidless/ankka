package com.thinkmorestupidless.ankka.runtime.erasure

import com.thinkmorestupidless.ankka.sdk.{ErasedObjects, ObjectErasure}
import com.typesafe.config.Config

import java.time.Instant

/**
 * A service's object erasure (FR-026): every object and every version under the data subject's
 * prefix in the service's bucket, deleted with the service's own credential. On a store that keeps
 * one version (Garage) the deletion is final at once; on one with a soft-delete window it is final
 * when the window has passed, which the bucket's status says once object storage on Google Cloud is
 * built (feature 039) — until then every bucket is Garage's.
 */
object ObjectErasures:

  def over(
      client: ObjectStoreClient,
      subject: String,
      softDeleteWindow: Option[java.time.Duration] = None
  ): ObjectErasure =
    () =>
      val items = client.list(ObjectErasure.prefix(subject))
      items.foreach(client.delete)
      val keys = items.map(_.key).distinct.size.toLong
      ErasedObjects(keys, softDeleteWindow.fold(Instant.now())(Instant.now().plus(_)))

  /**
   * The bucket `ankka.erasure.bucket` names — the service's `ANKKA_S3_*` variables, unless the
   * configuration says otherwise — or `noBucket` without one.
   */
  def fromConfig(config: Config): String => ObjectErasure =
    ObjectStoreClient.fromConfig(config) match
      case Some(client) => subject => over(client, subject)
      case None         => _ => ObjectErasure.noBucket
