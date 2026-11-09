package com.thinkmorestupidless.ankka.javasdk.testkit.docs;

import com.thinkmorestupidless.ankka.javasdk.Done;
import com.thinkmorestupidless.ankka.javasdk.annotations.Command;
import com.thinkmorestupidless.ankka.javasdk.annotations.ComponentId;
import com.thinkmorestupidless.ankka.javasdk.annotations.Query;
import com.thinkmorestupidless.ankka.javasdk.codec.Manifest;
import com.thinkmorestupidless.ankka.javasdk.component.EventSourcedEntity;
import com.thinkmorestupidless.ankka.javasdk.effect.Effect;
import com.thinkmorestupidless.ankka.javasdk.effect.ReadOnlyEffect;
import java.util.List;
import java.util.stream.Stream;

// docs:start entity
/** A listing's photos: their names in the bucket, never their bytes. */
@ComponentId("listing")
public class Listing extends EventSourcedEntity<Listing.ListingState, Listing.PhotoRecorded> {

  /** What a listing records about a photo: its name in the bucket. */
  @Manifest("photo-recorded")
  public record PhotoRecorded(String name) {}

  @Manifest("listing")
  public record ListingState(List<String> photos) {}

  @Override
  protected ListingState emptyState() {
    return new ListingState(List.of());
  }

  @Override
  protected ListingState applyEvent(ListingState state, PhotoRecorded event) {
    var photos = Stream.concat(state.photos().stream(), Stream.of(event.name())).toList();
    return new ListingState(photos);
  }

  @Command("record-photo")
  public Effect<Done> recordPhoto(String name) {
    return effects().persist(new PhotoRecorded(name)).thenDone();
  }

  @Query("photos")
  public ReadOnlyEffect<ListingState> photos() {
    return effects().reply(currentState());
  }
}
// docs:end entity
