package com.thinkmorestupidless.ankka.controlplane.api

import java.util.Hashtable
import javax.naming.{Context, NameNotFoundException, NamingException}
import javax.naming.directory.InitialDirContext
import scala.util.control.NonFatal

/**
 * The one DNS read the platform makes: the `TXT` records of a custom hostname's proof record,
 * `_ankka.<hostname>`, looked up once when a member adds the hostname and never again.
 *
 * The record sits beside the name, not at it, because a name that is a `CNAME` (as the record to
 * create usually is) can carry no other record. Its value is `ankka-project=<project id>`: only the
 * owner of the zone can write it, which is the whole proof, so nothing secret is needed.
 *
 * Over the JDK's own JNDI DNS provider (module `jdk.naming.dns`), as `HttpServiceClients` reads
 * `SRV` records: no dependency is added. It blocks, which is free on the virtual thread an endpoint
 * runs on.
 */
trait ProofLookup:

  /** The `TXT` strings at `name`, or why there are none. */
  def txt(name: String): Either[ProofLookup.Failure, Vector[String]]

  /** Whether the hostname carries the project's proof record. */
  final def proves(hostname: String, projectId: String): Either[ProofLookup.Failure, Boolean] =
    txt(ProofLookup.recordName(hostname)).map(_.contains(ProofLookup.recordValue(projectId)))

object ProofLookup:

  enum Failure:
    /** The name exists with no `TXT`, or does not exist: the record has not been created. */
    case NoRecord

    /** The resolver could not be asked, or did not answer in time. Nothing is known. */
    case Unreachable(detail: String)

  def recordName(hostname: String): String = s"_ankka.$hostname"

  def recordValue(projectId: String): String = s"ankka-project=$projectId"

  /**
   * Over JNDI. `resolver` is `host:port` (`ANKKA_DNS_RESOLVER`); absent, the machine's own
   * resolver, which in a pod is the cluster's.
   */
  def jndi(resolver: Option[String]): ProofLookup = new ProofLookup:
    def txt(name: String): Either[Failure, Vector[String]] =
      val env = new Hashtable[String, String]()
      env.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.dns.DnsContextFactory")
      resolver.foreach(r => env.put(Context.PROVIDER_URL, s"dns://$r"))
      // Two seconds, then once more at four: a member waits at most six seconds for a refusal.
      env.put("com.sun.jndi.dns.timeout.initial", "2000")
      env.put("com.sun.jndi.dns.timeout.retries", "2")
      try
        val context = new InitialDirContext(env)
        try
          val attribute = Option(context.getAttributes(name, Array("TXT")).get("TXT"))
          val values = attribute.toVector.flatMap { a =>
            (0 until a.size()).map(i => unquote(String.valueOf(a.get(i))))
          }
          if values.isEmpty then Left(Failure.NoRecord) else Right(values)
        finally context.close()
      catch
        case _: NameNotFoundException => Left(Failure.NoRecord)
        case e: NamingException =>
          Left(Failure.Unreachable(Option(e.getExplanation).getOrElse(e.getClass.getSimpleName)))
        case NonFatal(e) => Left(Failure.Unreachable(e.getClass.getSimpleName))

  /**
   * JNDI returns a `TXT` record's character strings joined by spaces, each quoted only when it has
   * a space in it. A record of one string, which the proof is, reads back as itself.
   */
  private def unquote(value: String): String =
    if value.length >= 2 && value.startsWith("\"") && value.endsWith("\"") then
      value.substring(1, value.length - 1)
    else value
