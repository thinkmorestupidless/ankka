package com.thinkmorestupidless.ankka.controlplane.api

import com.thinkmorestupidless.ankka.controlplane.deploy.DeployConfig
import com.thinkmorestupidless.ankka.controlplane.domain.{Service, ServiceKey}
import com.thinkmorestupidless.ankka.crd.Hostnames

/**
 * Whether a service may be exposed, and why not.
 *
 * Cross-entity and descriptor-level checks, so they live beside the endpoint rather than in the
 * entity, which cannot see another service or the platform's configuration. Pure, so the four
 * refusals — including "no base domain", which an HTTP suite against a configured control plane
 * cannot reach — are tested as values.
 */
object ExposureRules:

  /**
   * @param holder
   *   another exposed service whose derived hostname equals this one's, if any — the listing view's
   *   answer, looked up by the endpoint.
   */
  def refusal(service: Service, config: DeployConfig, holder: Option[ServiceKey]): Option[String] =
    if config.baseDomain.isEmpty then
      Some(
        "the control plane has no base domain configured (ANKKA_BASE_DOMAIN); nothing can be exposed"
      )
    else if !service.descriptor.exists(_.service.http) then
      Some(
        s"""service '${service.name}' serves no HTTP ("http": false); there is nothing to expose"""
      )
    else
      Hostnames
        .problems(service.name, service.projectId)
        .headOption
        .orElse(
          holder.map(h =>
            s"hostname ${Hostnames.of(service.name, service.projectId, config.baseDomain.get)} is " +
              s"already exposed by service '${h.name}' in project '${h.projectId}'"
          )
        )
