package com.thinkmorestupidless.ankka.controlplane.deploy

import com.github.plokhotnyuk.jsoniter_scala.core.{readFromArray, writeToArray}
import com.thinkmorestupidless.ankka.runtime.erasure.{KeyringApi, ObjectStoreClient}
import com.thinkmorestupidless.ankka.runtime.erasure.KeyringApi.given

/**
 * The erasure log's second copy (FR-017): one object per erasure in the platform bucket,
 * `erasure-log/<project>/<erasureId>.json`, never overwritten, so append-only needs no versioning.
 * An installation with no object store has no second copy, and the keyring's status says so.
 */
trait ErasureLogBucket:
  def append(entry: KeyringApi.LogEntry): Unit
  def entries(): Vector[KeyringApi.LogEntry]

object ErasureLogBucket:
  val none: ErasureLogBucket = new ErasureLogBucket:
    def append(entry: KeyringApi.LogEntry): Unit = ()
    def entries(): Vector[KeyringApi.LogEntry]   = Vector.empty

  def over(client: ObjectStoreClient): ErasureLogBucket = new ErasureLogBucket:
    def append(entry: KeyringApi.LogEntry): Unit =
      val key = s"erasure-log/${entry.project}/${entry.erasureId}.json"
      if !client.put(key, writeToArray(entry), ifAbsent = true) then
        // Already there: the same erasure written again by a retried sweep; refuse only a different one.
        client.get(key).map(readFromArray[KeyringApi.LogEntry](_)).filter(_ != entry).foreach {
          other =>
            throw IllegalStateException(
              s"the erasure log's bucket already holds a different $key: $other"
            )
        }
    def entries(): Vector[KeyringApi.LogEntry] =
      client
        .list("erasure-log/")
        .map(_.key)
        .distinct
        .flatMap(k => client.get(k).map(readFromArray[KeyringApi.LogEntry](_)))

  def fromEnvironment(env: String => Option[String] = sys.env.get): ErasureLogBucket =
    ObjectStoreClient.fromEnvironment(env).fold(none)(over)
