package digest.application

import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.runtime.{SqlFragment, SqlSyntax, ViewClient}
import com.thinkmorestupidless.ankka.sdk.*
import digest.domain.Entry

import java.time.Instant

/** Every entry, as a row, so the digest can ask which papers were first found in its period. */
final class EntriesView extends View[Entry, Entry]:
  def onChange(entry: Entry): Effect = effects.updateRow(entry)

object EntriesView
    extends View.Companion[EntriesView, Entry, Entry](
      ComponentId("entries"),
      ChangeSource.stateOf(PapersEntity),
      Codecs.serializer[Entry]("entry-row")
    ):

  def create(context: ViewComponentContext) = new EntriesView

  /** The entries first found from `from` up to but not including `to`, oldest first. */
  def foundBetween(views: ViewClient, from: Instant, to: Instant): Vector[Entry] =
    import SqlSyntax.sql
    val found = SqlSyntax.jsonNumber("firstFound")
    views
      .forView(EntriesView)
      .ordered(
        found ++ sql" >= ${from.toEpochMilli}" ++ SqlFragment
          .raw(" AND ") ++ found ++ sql" < ${to.toEpochMilli}",
        found
      )
