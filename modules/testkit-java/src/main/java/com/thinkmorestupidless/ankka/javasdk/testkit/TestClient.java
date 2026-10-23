package com.thinkmorestupidless.ankka.javasdk.testkit;

import com.thinkmorestupidless.ankka.javasdk.client.CommandRef;
import com.thinkmorestupidless.ankka.javasdk.client.ComponentClient;
import com.thinkmorestupidless.ankka.javasdk.client.NoArgRef;
import com.thinkmorestupidless.ankka.javasdk.http.Problem;
import com.thinkmorestupidless.ankka.javasdk.testkit.bridge.KindKits;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The component client a component test kit hands the component it drives, answering only what
 * the test stubbed. A call to a handler nobody stubbed fails, naming it, rather than answering a
 * default a test could pass on.
 *
 * <pre>{@code
 * TestClient wallets = TestClient.of(Wallet.class)
 *     .stub(Wallet::withdraw, amount -> Done.DONE)
 *     .refuse(Wallet::deposit, Problem.conflict("frozen"));
 * WorkflowTestKit<Transfer> kit = WorkflowTestKit.of(TransferWorkflow.class, "t-1", wallets);
 * }</pre>
 *
 * Every input and answer crosses the handler's own serializers. Immutable: each stub is a new
 * client.
 */
public final class TestClient {

  private final KindKits.Stubs stubs;

  private TestClient(KindKits.Stubs stubs) {
    this.stubs = stubs;
  }

  /** A client that may stub the handlers of {@code components}, and has stubbed none. */
  public static TestClient of(Class<?>... components) {
    return new TestClient(KindKits.stubs(components));
  }

  /** Answers {@code handler} with what {@code answer} makes of its input. */
  public <C, I, O> TestClient stub(
      CommandRef<C, I, O> handler, Function<? super I, ? extends O> answer) {
    return new TestClient(stubs.answer(handler, input -> answer.apply(TestClient.<I>cast(input))));
  }

  @SuppressWarnings("unchecked")
  private static <I> I cast(Object input) {
    return (I) input;
  }

  /** Answers {@code handler}, which takes nothing, with what {@code answer} gives. */
  public <C, O> TestClient stub(NoArgRef<C, O> handler, Supplier<? extends O> answer) {
    return new TestClient(stubs.answer(handler, ignored -> answer.get()));
  }

  /** Refuses every call of {@code handler} with {@code problem}. */
  public <C, I, O> TestClient refuse(CommandRef<C, I, O> handler, Problem problem) {
    return new TestClient(stubs.refuse(handler, problem));
  }

  /** Refuses every call of {@code handler}, which takes nothing, with {@code problem}. */
  public <C, O> TestClient refuse(NoArgRef<C, O> handler, Problem problem) {
    return new TestClient(stubs.refuse(handler, problem));
  }

  /** The client itself, for a test that calls through it directly. */
  public ComponentClient client() {
    return stubs.client();
  }

  /** What the kits read: the classes and the transport. */
  public KindKits.Stubs stubs() {
    return stubs;
  }
}
