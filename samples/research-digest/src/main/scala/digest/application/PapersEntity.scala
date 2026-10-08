package digest.application

import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.sdk.*
import digest.domain.Entry

/**
 * The entry for one paper, keyed by the paper's identifier. Keeping a finding twice keeps it once:
 * the first finding sets when the paper was first found, a later one from another source adds the
 * source, and one from a source already named changes nothing.
 */
final class PapersEntity(context: KeyValueEntityContext) extends KeyValueEntity[Entry]:

  private val identifier: String = context.entityId

  def emptyState: Entry = Entry.empty(identifier)

  def keep(finding: PapersEntity.Keep): Effect[Entry] =
    if finding.source.isBlank then effects.error("a finding names its source", ErrorCode.BadRequest)
    else
      val entry = currentState
      val next =
        if !entry.exists then
          Entry(identifier, finding.title, Vector(finding.source), finding.foundAt)
        else if entry.sources.contains(finding.source) then entry
        else entry.copy(sources = entry.sources :+ finding.source)
      if next == entry then effects.reply(entry)
      else effects.updateState(next).thenReplyState

  def get: ReadOnlyEffect[Entry] =
    if currentState.exists then effects.reply(currentState)
    else effects.error(s"no entry for '$identifier'", ErrorCode.NotFound)

object PapersEntity
    extends KeyValueEntity.Companion[PapersEntity, Entry](
      componentId = ComponentId("papers"),
      stateSerializer = Codecs.serializer[Entry]("entry")
    ):

  /** One source finding one paper, at a time. */
  final case class Keep(source: String, title: String, foundAt: Long)

  given Serializer[Keep] = Codecs.serializer[Keep]("keep")

  def create(context: KeyValueEntityContext) = new PapersEntity(context)

  val keep = command("keep")(_.keep)
  val get  = query("get")(_.get)
