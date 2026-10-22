package com.thinkmorestupidless.ankka.testkit

import com.thinkmorestupidless.ankka.runtime.storage.MemoryObjectStorage
import com.thinkmorestupidless.ankka.sdk.BucketExposure

/**
 * A storage client in memory for a unit test kit (feature 055): the unnamed bucket and the named
 * ones given, every rule the runtime's stores apply.
 *
 * {{{
 * val storage = InMemoryObjectStorage("photos" -> BucketExposure.Public)
 * val kit     = ConsumerTestKit(ListingPhotos(_), storage = storage)
 * }}}
 */
object InMemoryObjectStorage:
  def apply(buckets: (String, BucketExposure)*): MemoryObjectStorage = MemoryObjectStorage(
    buckets.toMap
  )
