package com.thinkmorestupidless.ankka.proxy

import com.thinkmorestupidless.ankka.proxy.core.{Located, Locator}
import com.thinkmorestupidless.ankka.runtime.HttpServiceClients

import java.net.URI

/**
 * Where a service is in a cluster: the runtime's own lookup, unchanged, so a web-hosted service's
 * proxy and an ankka service's client cannot disagree about it. The host is the Service's
 * in-cluster name and the port is the one its SRV record publishes for `http`.
 */
object ClusterLocator extends Locator:

  def locate(project: String, service: String): Option[Located] =
    HttpServiceClients
      .kubernetes(project, service)
      .map((host, port) => Located(URI.create(s"https://$host:$port")))
