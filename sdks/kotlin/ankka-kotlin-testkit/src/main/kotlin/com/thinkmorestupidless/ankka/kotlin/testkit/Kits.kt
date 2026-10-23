package com.thinkmorestupidless.ankka.kotlin.testkit

import com.thinkmorestupidless.ankka.javasdk.ServiceBuilder
import com.thinkmorestupidless.ankka.javasdk.component.Consumer
import com.thinkmorestupidless.ankka.javasdk.component.EventSourcedEntity
import com.thinkmorestupidless.ankka.javasdk.component.KeyValueEntity
import com.thinkmorestupidless.ankka.javasdk.component.View
import com.thinkmorestupidless.ankka.javasdk.component.Workflow
import com.thinkmorestupidless.ankka.javasdk.testkit.AnkkaTestKit
import com.thinkmorestupidless.ankka.javasdk.testkit.ConsumerTestKit
import com.thinkmorestupidless.ankka.javasdk.testkit.EventSourcedTestKit
import com.thinkmorestupidless.ankka.javasdk.testkit.KeyValueEntityTestKit
import com.thinkmorestupidless.ankka.javasdk.testkit.Settings
import com.thinkmorestupidless.ankka.javasdk.testkit.ViewTestKit
import com.thinkmorestupidless.ankka.javasdk.testkit.WorkflowTestKit
import kotlin.reflect.KClass
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.delay

// The Java test kits, as Kotlin reaches for them: a class as a KClass, a whole service in a block
// that closes it, and a suspending eventually. Everything else is the Java kit as it is.

/** Starts [service] against a throwaway database, runs [block], and closes the service. */
fun <T> ankkaTestKit(
    service: ServiceBuilder,
    settings: Settings = Settings.defaults(),
    block: AnkkaTestKit.() -> T,
): T = AnkkaTestKit.start(service, settings).use { it.block() }

/**
 * Retries [assertion] until it passes or [within] has gone, suspending between tries; the last
 * failure is thrown when time runs out.
 */
suspend fun AnkkaTestKit.eventually(within: Duration = 10.seconds, assertion: suspend () -> Unit) {
    val deadline = TimeSource.Monotonic.markNow() + within
    while (true) {
        try {
            return assertion()
        } catch (failed: AssertionError) {
            if (deadline.hasPassedNow()) throw failed
        } catch (failed: Exception) {
            if (deadline.hasPassedNow()) throw failed
        }
        delay(100.milliseconds)
    }
}

/**
 * How many platform threads the JVM has now: a handler waiting in a `suspend fun` adds none, so a
 * count taken before and during a wait shows whether a waiting handler holds one.
 */
@Suppress("UnusedReceiverParameter") // asked of the kit, which is what a test holds
val AnkkaTestKit.platformThreads: Int
    get() = Thread.getAllStackTraces().keys.count { !it.isVirtual }

fun <S : Any, E : Any> eventSourcedTestKit(entity: KClass<out EventSourcedEntity<S, E>>): EventSourcedTestKit<S, E> =
    EventSourcedTestKit.of(entity.java)

fun <S : Any, E : Any> eventSourcedTestKit(
    entity: KClass<out EventSourcedEntity<S, E>>,
    entityId: String,
): EventSourcedTestKit<S, E> = EventSourcedTestKit.of(entity.java, entityId)

fun <S : Any> keyValueEntityTestKit(entity: KClass<out KeyValueEntity<S>>): KeyValueEntityTestKit<S> =
    KeyValueEntityTestKit.of(entity.java)

fun <S : Any> workflowTestKit(workflow: KClass<out Workflow<S>>): WorkflowTestKit<S> = WorkflowTestKit.of(workflow.java)

fun <Src : Any, Row : Any> viewTestKit(view: KClass<out View<Src, Row>>): ViewTestKit<Src, Row> = ViewTestKit.of(view.java)

fun <Src : Any, Out : Any> consumerTestKit(consumer: KClass<out Consumer<Src, Out>>): ConsumerTestKit<Src, Out> =
    ConsumerTestKit.of(consumer.java)
