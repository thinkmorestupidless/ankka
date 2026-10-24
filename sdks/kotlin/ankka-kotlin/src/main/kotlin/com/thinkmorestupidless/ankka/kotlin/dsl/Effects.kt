package com.thinkmorestupidless.ankka.kotlin.dsl

import com.thinkmorestupidless.ankka.javasdk.component.Consumer
import com.thinkmorestupidless.ankka.javasdk.component.EventSourcedEntity
import com.thinkmorestupidless.ankka.javasdk.component.GraphConsumer
import com.thinkmorestupidless.ankka.javasdk.component.KeyValueEntity
import com.thinkmorestupidless.ankka.javasdk.component.KeyedView
import com.thinkmorestupidless.ankka.javasdk.component.TimedAction
import com.thinkmorestupidless.ankka.javasdk.component.View
import com.thinkmorestupidless.ankka.javasdk.component.Workflow
import com.thinkmorestupidless.ankka.javasdk.effect.ConsumerEffect
import com.thinkmorestupidless.ankka.javasdk.effect.ConsumerEffects
import com.thinkmorestupidless.ankka.javasdk.effect.Effect
import com.thinkmorestupidless.ankka.javasdk.effect.EventSourcedEffects
import com.thinkmorestupidless.ankka.javasdk.effect.GraphEffect
import com.thinkmorestupidless.ankka.javasdk.effect.GraphEffects
import com.thinkmorestupidless.ankka.javasdk.effect.KeyValueEffects
import com.thinkmorestupidless.ankka.javasdk.effect.KeyedViewEffect
import com.thinkmorestupidless.ankka.javasdk.effect.KeyedViewEffects
import com.thinkmorestupidless.ankka.javasdk.effect.PersistBuilder
import com.thinkmorestupidless.ankka.javasdk.effect.StepBuilder
import com.thinkmorestupidless.ankka.javasdk.effect.StepEffect
import com.thinkmorestupidless.ankka.javasdk.effect.StepEffects
import com.thinkmorestupidless.ankka.javasdk.effect.TimedActionEffect
import com.thinkmorestupidless.ankka.javasdk.effect.TimedActionEffects
import com.thinkmorestupidless.ankka.javasdk.effect.UpdateBuilder
import com.thinkmorestupidless.ankka.javasdk.effect.ViewEffect
import com.thinkmorestupidless.ankka.javasdk.effect.ViewEffects
import com.thinkmorestupidless.ankka.javasdk.effect.WorkflowBuilder
import com.thinkmorestupidless.ankka.javasdk.effect.WorkflowEffects
import com.thinkmorestupidless.ankka.javasdk.spi.ComponentEffects
import com.thinkmorestupidless.ankka.kotlin.stepRef
import java.util.function.Function
import kotlin.reflect.KFunction1
import kotlin.reflect.KFunction2
import kotlin.reflect.KSuspendFunction1
import kotlin.reflect.KSuspendFunction2

// The effects a handler returns, built in a block whose receiver is the component's own effects:
//
//   @Command("add-item")
//   fun addItem(item: LineItem): Effect<Done> = effects { persist(ItemAdded(item)) thenReply { Done.DONE } }
//
// Each block produces exactly the Java effect the chained form does; nothing here is a second
// implementation. The builders are inline, so a block is no method of the component's class.
// A block answers the type its last expression has, so a query's block that ends in
// reply(...) is a ReadOnlyEffect.

inline fun <S : Any, E : Any, X : Effect<*>> EventSourcedEntity<S, E>.effects(build: EventSourcedEffects<S, E>.() -> X): X =
    ComponentEffects.of(this).build()

inline fun <S : Any, X : Effect<*>> KeyValueEntity<S>.effects(build: KeyValueEffects<S>.() -> X): X =
    ComponentEffects.of(this).build()

inline fun <S : Any, X : Effect<*>> Workflow<S>.effects(build: WorkflowEffects<S>.() -> X): X =
    ComponentEffects.of(this).build()

inline fun <S : Any> Workflow<S>.stepEffects(build: StepEffects<S>.() -> StepEffect): StepEffect =
    ComponentEffects.stepsOf(this).build()

inline fun <Src : Any, Row : Any> View<Src, Row>.effects(build: ViewEffects<Row>.() -> ViewEffect<Row>): ViewEffect<Row> =
    ComponentEffects.of(this).build()

inline fun <Row : Any> KeyedView<Row>.effects(build: KeyedViewEffects<Row>.() -> KeyedViewEffect<Row>): KeyedViewEffect<Row> =
    ComponentEffects.of(this).build()

inline fun <Src : Any, Out : Any> Consumer<Src, Out>.effects(
    build: ConsumerEffects<Out>.() -> ConsumerEffect<Out>
): ConsumerEffect<Out> = ComponentEffects.of(this).build()

inline fun <Src : Any> GraphConsumer<Src>.effects(build: GraphEffects.() -> GraphEffect): GraphEffect =
    ComponentEffects.of(this).build()

inline fun TimedAction.effects(build: TimedActionEffects.() -> TimedActionEffect): TimedActionEffect =
    ComponentEffects.of(this).build()

// The infix forms of the builders' last step.

/** Persist, then reply with what [reply] makes of the state the events produced. */
infix fun <S : Any, E : Any, R : Any> PersistBuilder<S, E>.thenReply(reply: (S) -> R): Effect<R> =
    thenReply(Function { state: S -> reply(state) })

/** Update, then reply with what [reply] makes of the new state. */
infix fun <S : Any, R : Any> UpdateBuilder<S>.thenReply(reply: (S) -> R): Effect<R> =
    thenReply(Function { state: S -> reply(state) })

/** Update the state, then go to the step [step] names, which takes no input. */
@JvmName("thenTransitionToSuspending")
infix fun <S : Any, W : Any> StepBuilder<S>.thenTransitionTo(step: KSuspendFunction1<W, StepEffect>): StepEffect =
    thenTransitionTo(stepRef(step))

/** Update the state, then go to the step [step] names, which takes no input. */
infix fun <S : Any, W : Any> StepBuilder<S>.thenTransitionTo(step: KFunction1<W, StepEffect>): StepEffect =
    thenTransitionTo(stepRef(step))

/** Update the state, then go to the step [step] names with [input]. */
@JvmName("thenTransitionToSuspendingWithInput")
fun <S : Any, W : Any, I : Any> StepBuilder<S>.thenTransitionTo(
    step: KSuspendFunction2<W, I, StepEffect>,
    input: I,
): StepEffect = thenTransitionTo(stepRef(step), input)

/** Update the state, then go to the step [step] names with [input]. */
@JvmName("thenTransitionToWithInput")
fun <S : Any, W : Any, I : Any> StepBuilder<S>.thenTransitionTo(step: KFunction2<W, I, StepEffect>, input: I): StepEffect =
    thenTransitionTo(stepRef(step), input)

/** Go to the step [step] names, which takes no input, once the command is answered. */
@JvmName("transitionToSuspending")
infix fun <S : Any, W : Any> WorkflowBuilder<S>.transitionTo(step: KSuspendFunction1<W, StepEffect>): WorkflowBuilder<S> =
    transitionTo(stepRef(step))

/** Go to the step [step] names, which takes no input, once the command is answered. */
infix fun <S : Any, W : Any> WorkflowBuilder<S>.transitionTo(step: KFunction1<W, StepEffect>): WorkflowBuilder<S> =
    transitionTo(stepRef(step))

/** Start at the step [step] names, which takes no input. */
@JvmName("startAtSuspending")
fun <S : Any, W : Any> WorkflowEffects<S>.transitionTo(step: KSuspendFunction1<W, StepEffect>): WorkflowBuilder<S> =
    transitionTo(stepRef(step))

/** Start at the step [step] names, which takes no input. */
@JvmName("startAt")
fun <S : Any, W : Any> WorkflowEffects<S>.transitionTo(step: KFunction1<W, StepEffect>): WorkflowBuilder<S> =
    transitionTo(stepRef(step))
