package com.thinkmorestupidless.ankka.grpc

import com.thinkmorestupidless.ankka.http.LocalCallers
import io.grpc.Metadata

import scala.jdk.CollectionConverters.*

/**
 * The names and values a caller sent with a call beside its request — what HTTP calls headers.
 *
 * Text keys only: a `-bin` key is binary and has no text to show. The key a test names a local
 * caller with is withheld, as the HTTP server withholds its header, because its value is this
 * process's secret; the caller it names is `caller`.
 */
final class CallMetadata private[grpc] (entries: Vector[(String, String)]):

  /** The first value of `name`, compared case-insensitively. */
  def get(name: String): Option[String] =
    entries.collectFirst { case (key, value) if key.equalsIgnoreCase(name) => value }

  /** Every value of `name`, in the order sent. */
  def all(name: String): Vector[String] =
    entries.collect { case (key, value) if key.equalsIgnoreCase(name) => value }

  /** Every entry, in the order sent: what an ACL sees as a request's headers. */
  def toSeq: Vector[(String, String)] = entries

object CallMetadata:

  private val LocalCallerKey = LocalCallers.Header.toLowerCase

  private[grpc] def of(headers: Metadata): CallMetadata =
    CallMetadata(
      headers
        .keys()
        .asScala
        .toVector
        .filterNot(key => key.endsWith(Metadata.BINARY_HEADER_SUFFIX) || key == LocalCallerKey)
        .flatMap { key =>
          headers
            .getAll(Metadata.Key.of(key, Metadata.ASCII_STRING_MARSHALLER))
            .asScala
            .map(key -> _)
        }
    )

  /** The local-caller key's value, when a test sent one; read only outside TLS. */
  private[grpc] def localCaller(headers: Metadata): Option[String] =
    Option(headers.get(Metadata.Key.of(LocalCallerKey, Metadata.ASCII_STRING_MARSHALLER)))
