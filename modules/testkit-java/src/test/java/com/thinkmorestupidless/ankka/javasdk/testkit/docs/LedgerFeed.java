package com.thinkmorestupidless.ankka.javasdk.testkit.docs;

import com.thinkmorestupidless.ankka.javasdk.annotations.ComponentId;
import com.thinkmorestupidless.ankka.javasdk.component.ChangeSource;
import com.thinkmorestupidless.ankka.javasdk.component.Consumer;
import com.thinkmorestupidless.ankka.javasdk.component.Publication;
import com.thinkmorestupidless.ankka.javasdk.effect.ConsumerEffect;
import com.thinkmorestupidless.ankka.javasdk.testkit.kinds.Ledger;
import java.util.Optional;

/** Publishes every entry of a replicated ledger, from every installation that holds it. */
@ComponentId("ledger-feed")
public class LedgerFeed extends Consumer<Ledger.Entered, LedgerFeed.Entry> {

  /** An entry, with where it was written and whether that was here. */
  public record Entry(String ledger, String text, String writtenAt, boolean local) {}

  @Override
  protected ChangeSource<Ledger.Entered> source() {
    return ChangeSource.of(Ledger.class);
  }

  @Override
  protected Optional<Publication> publishes() {
    return Optional.of(Publication.to("ledger-entries"));
  }

  @Override
  protected boolean mirroredChanges() {
    return true;
  }

  // docs:start mirrored-change
  @Override
  protected ConsumerEffect<Entry> onChange(Ledger.Entered event) {
    var change = messageContext();
    return effects()
        .publish(
            new Entry(
                change.subject(),
                event.text(),
                change.installation().orElse("unreplicated"),
                change.localOrigin()));
  }
  // docs:end mirrored-change
}
