package com.thinkmorestupidless.ankka.proxy

import com.thinkmorestupidless.ankka.proxy.core.{Located, Locator}

import java.net.URI

/**
 * Where a service is when the proxy runs in its local mode (feature 066): only where
 * `ANKKA_PROXY_LOCAL_SERVICES` says, as `name=url` pairs. A service it does not name is not
 * located, and the engine answers 503 naming it, as `ankka local web` does.
 */
final class LocalLocator(services: Map[String, URI]) extends Locator:
  def locate(project: String, service: String): Option[Located] =
    services.get(service).map(Located(_))

object LocalLocator:

  val Variable: String = "ANKKA_PROXY_LOCAL_SERVICES"

  /** The pairs `text` lists, or every entry that is not `name=url`. */
  def parse(text: String): Either[Vector[String], LocalLocator] =
    val entries = text.split(",").map(_.trim).filter(_.nonEmpty).toVector.map { entry =>
      entry.split("=", 2) match
        case Array(name, url)
            if name.nonEmpty && (url.startsWith("http://") || url.startsWith("https://")) =>
          Right(name -> URI.create(url))
        case _ => Left(s"$Variable has an entry that is not <service>=<url>: '$entry'")
    }
    val problems = entries.flatMap(_.left.toOption)
    if problems.nonEmpty then Left(problems)
    else Right(LocalLocator(entries.flatMap(_.toOption).toMap))
