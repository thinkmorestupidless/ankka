package com.thinkmorestupidless.ankka.javasdk.reference;

import com.thinkmorestupidless.ankka.javasdk.http.Caller;
import java.util.Collection;
import java.util.concurrent.Flow;
import java.util.function.Function;
import java.util.stream.Collectors;

/** The little the reference's routes write by hand: JSON text, a caller's word, a mapped stream. */
final class Wire {
  private Wire() {}

  /** A string as a JSON string. */
  static String quoted(String text) {
    var out = new StringBuilder("\"");
    for (char c : text.toCharArray()) {
      switch (c) {
        case '"' -> out.append("\\\"");
        case '\\' -> out.append("\\\\");
        case '\n' -> out.append("\\n");
        case '\r' -> out.append("\\r");
        case '\t' -> out.append("\\t");
        default -> {
          if (c < ' ') out.append(String.format("\\u%04x", (int) c));
          else out.append(c);
        }
      }
    }
    return out.append('"').toString();
  }

  /** A string as a JSON string, or {@code null}. */
  static String quotedOrNull(String text) {
    return text == null ? "null" : quoted(text);
  }

  /** Strings as a JSON array, sorted. */
  static String sortedArray(Collection<String> values) {
    return values.stream().sorted().map(Wire::quoted).collect(Collectors.joining(",", "[", "]"));
  }

  /** A caller as every reference names it. */
  static String callerWord(Caller caller) {
    return switch (caller) {
      case Caller.Service s -> "service:" + s.project() + "/" + s.name();
      case Caller.Machine m -> "machine:" + m.organization() + "/" + m.name();
      case Caller.Gateway g -> "gateway";
      case Caller.Local l -> "local";
    };
  }

  /** {@code source}, each element passed through {@code f}, at the subscriber's pace. */
  static <A, B> Flow.Publisher<B> map(Flow.Publisher<A> source, Function<A, B> f) {
    return subscriber ->
        source.subscribe(
            new Flow.Subscriber<A>() {
              @Override
              public void onSubscribe(Flow.Subscription subscription) {
                subscriber.onSubscribe(subscription);
              }

              @Override
              public void onNext(A item) {
                subscriber.onNext(f.apply(item));
              }

              @Override
              public void onError(Throwable failure) {
                subscriber.onError(failure);
              }

              @Override
              public void onComplete() {
                subscriber.onComplete();
              }
            });
  }
}
