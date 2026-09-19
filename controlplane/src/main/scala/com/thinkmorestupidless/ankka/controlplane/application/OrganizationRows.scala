package com.thinkmorestupidless.ankka.controlplane.application

import com.thinkmorestupidless.ankka.controlplane.api.OrganizationDetail
import com.thinkmorestupidless.ankka.controlplane.domain.OrganizationEvent
import com.thinkmorestupidless.ankka.controlplane.domain.OrganizationEvent.*
import com.thinkmorestupidless.ankka.core.{Codecs, ComponentId}
import com.thinkmorestupidless.ankka.sdk.*

/** Every organization, so `ankka organizations list` needs no known ids. */
final class OrganizationRowsView extends View[OrganizationEvent, OrganizationDetail]:

  def onChange(event: OrganizationEvent): Effect = event match
    case OrganizationCreated(name) =>
      effects.updateRow(OrganizationDetail(updateContext.subject, name))

    case OrganizationRenamed(name) =>
      rowState match
        case Some(row) => effects.updateRow(row.copy(name = name))
        case None      => effects.ignore()

    case OrganizationDeleted => effects.deleteRow()

object OrganizationRows
    extends View.Companion[OrganizationRowsView, OrganizationEvent, OrganizationDetail](
      componentId = ComponentId("organization-rows"),
      source = ChangeSource.eventsOf(OrganizationEntity),
      rowSerializer = Codecs.serializer[OrganizationDetail]("organization-detail")
    ):
  def create(ctx: ViewComponentContext) = new OrganizationRowsView
