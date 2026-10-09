package com.thinkmorestupidless.ankka.controlplane.application

import com.thinkmorestupidless.ankka.controlplane.api.{ByteRatesRequest, MachineSummary, Machines}
import com.thinkmorestupidless.ankka.controlplane.domain.MachineEvent
import com.thinkmorestupidless.ankka.controlplane.domain.MachineEvent.*
import com.thinkmorestupidless.ankka.core.{Codecs, ComponentId}
import com.thinkmorestupidless.ankka.sdk.*

import java.time.Instant

/**
 * One row per registered machine that is not deleted (feature 040), so an organization lists its
 * machines without opening an entity each. Neither the secret nor its digest is here.
 */
final case class MachineRow(
    id: String,
    organizationId: String = "",
    name: String = "",
    registeredBy: Option[String] = None,
    registeredAt: Option[Instant] = None,
    byteRates: Option[ByteRatesRequest] = None
):
  def summary: MachineSummary =
    MachineSummary(
      name,
      Machines.clientId(organizationId, name),
      registeredBy,
      registeredAt,
      byteRates
    )

final class MachineRowsView extends View[MachineEvent, MachineRow]:

  private def row = rowState.getOrElse(MachineRow(updateContext.subject))

  def onChange(event: MachineEvent): Effect = event match
    case MachineRegistered(organizationId, name, _, actor, at) =>
      // A machine registered again after a deletion is a new one: nothing of the old row is kept.
      effects.updateRow(
        MachineRow(row.id, organizationId, name, actor.flatMap(_.display), at)
      )
    case MachineByteRatesSet(produce, consume, percentage, _, _) =>
      effects.updateRow(row.copy(byteRates = Some(ByteRatesRequest(produce, consume, percentage))))
    case _: MachineDeleted => effects.deleteRow()

object MachineRows
    extends View.Companion[MachineRowsView, MachineEvent, MachineRow](
      componentId = ComponentId("machine-rows"),
      source = ChangeSource.eventsOf(MachineEntity),
      rowSerializer = Codecs.serializer[MachineRow]("machine-row")
    ):
  def create(ctx: ViewComponentContext) = new MachineRowsView
