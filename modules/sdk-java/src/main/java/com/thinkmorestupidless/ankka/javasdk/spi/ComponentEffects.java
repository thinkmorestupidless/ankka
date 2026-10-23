package com.thinkmorestupidless.ankka.javasdk.spi;

import com.thinkmorestupidless.ankka.javasdk.bridge.EntityCell;
import com.thinkmorestupidless.ankka.javasdk.bridge.Effects;
import com.thinkmorestupidless.ankka.javasdk.component.Consumer;
import com.thinkmorestupidless.ankka.javasdk.component.EventSourcedEntity;
import com.thinkmorestupidless.ankka.javasdk.component.KeyValueEntity;
import com.thinkmorestupidless.ankka.javasdk.component.KeyedView;
import com.thinkmorestupidless.ankka.javasdk.component.TimedAction;
import com.thinkmorestupidless.ankka.javasdk.component.View;
import com.thinkmorestupidless.ankka.javasdk.component.Workflow;
import com.thinkmorestupidless.ankka.javasdk.effect.ConsumerEffects;
import com.thinkmorestupidless.ankka.javasdk.effect.EventSourcedEffects;
import com.thinkmorestupidless.ankka.javasdk.effect.KeyValueEffects;
import com.thinkmorestupidless.ankka.javasdk.effect.KeyedViewEffects;
import com.thinkmorestupidless.ankka.javasdk.effect.StepEffects;
import com.thinkmorestupidless.ankka.javasdk.effect.TimedActionEffects;
import com.thinkmorestupidless.ankka.javasdk.effect.ViewEffects;
import com.thinkmorestupidless.ankka.javasdk.effect.WorkflowEffects;
import java.lang.reflect.Field;

/**
 * The effects a component's own {@code effects()} answers, for a builder written outside the class:
 * the Kotlin SDK's {@code effects { ... }}, an extension, cannot call a protected method. Each
 * answers exactly what the component's {@code effects()} would.
 */
@SuppressWarnings("unchecked")
public final class ComponentEffects {

  public static <S, E> EventSourcedEffects<S, E> of(EventSourcedEntity<S, E> entity) {
    return (EventSourcedEffects<S, E>) Effects.eventSourced();
  }

  public static <S> KeyValueEffects<S> of(KeyValueEntity<S> entity) {
    return (KeyValueEffects<S>) Effects.keyValue();
  }

  public static <Row> ViewEffects<Row> of(View<?, Row> view) {
    return (ViewEffects<Row>) Effects.view();
  }

  public static <Row> KeyedViewEffects<Row> of(KeyedView<Row> view) {
    return (KeyedViewEffects<Row>) Effects.keyedView();
  }

  public static <Out> ConsumerEffects<Out> of(Consumer<?, Out> consumer) {
    return (ConsumerEffects<Out>) Effects.consumer();
  }

  public static TimedActionEffects of(TimedAction action) {
    return Effects.timedAction();
  }

  public static <S> WorkflowEffects<S> of(Workflow<S> workflow) {
    return (WorkflowEffects<S>) cellOf(workflow).workflowEffects();
  }

  public static <S> StepEffects<S> stepsOf(Workflow<S> workflow) {
    return (StepEffects<S>) cellOf(workflow).stepEffects();
  }

  // A workflow's effects name its own steps, so they are its cell's, which only the class holds.
  private static final Field CELL;

  static {
    try {
      CELL = Workflow.class.getDeclaredField("cell");
      CELL.setAccessible(true);
    } catch (NoSuchFieldException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  private static EntityCell cellOf(Workflow<?> workflow) {
    try {
      return (EntityCell) CELL.get(workflow);
    } catch (IllegalAccessException e) {
      throw new IllegalStateException(e);
    }
  }

  private ComponentEffects() {}
}
