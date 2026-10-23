package com.thinkmorestupidless.ankka.kotlin

import com.thinkmorestupidless.ankka.javasdk.client.CommandException
import com.thinkmorestupidless.ankka.javasdk.client.NoInputStepRef
import com.thinkmorestupidless.ankka.javasdk.client.ViewQueries
import com.thinkmorestupidless.ankka.javasdk.client.WorkflowFailure
import com.thinkmorestupidless.ankka.javasdk.client.WorkflowLifecycle
import com.thinkmorestupidless.ankka.javasdk.component.ChangeContext
import com.thinkmorestupidless.ankka.javasdk.component.Contract
import com.thinkmorestupidless.ankka.javasdk.component.EntityContext
import com.thinkmorestupidless.ankka.javasdk.component.EventContext
import com.thinkmorestupidless.ankka.javasdk.component.KeyedChange
import com.thinkmorestupidless.ankka.javasdk.component.Publication
import com.thinkmorestupidless.ankka.javasdk.component.RecoverStrategy
import com.thinkmorestupidless.ankka.javasdk.component.ViewRows
import com.thinkmorestupidless.ankka.javasdk.component.WorkflowSettings
import com.thinkmorestupidless.ankka.javasdk.http.Principal
import com.thinkmorestupidless.ankka.javasdk.http.Request
import com.thinkmorestupidless.ankka.javasdk.http.Response
import com.thinkmorestupidless.ankka.javasdk.http.SseEvent
import com.thinkmorestupidless.ankka.javasdk.http.WebSocket
import com.thinkmorestupidless.ankka.javasdk.secret.SecretStore
import com.thinkmorestupidless.ankka.javasdk.storage.Bucket
import com.thinkmorestupidless.ankka.javasdk.storage.ObjectInfo
import com.thinkmorestupidless.ankka.javasdk.storage.StoredObject
import java.time.Duration
import java.util.Optional

// Every public Java SDK method that answers Optional<T> has a Kotlin form here answering T?, named
// with OrNull. The protected ones (an endpoint's principal(), a view's rowState()) cannot be
// extended from outside the class and keep their Optional; read them with .orNull().

/** The value, or `null` when there is none. */
fun <T : Any> Optional<T>.orNull(): T? = orElse(null)

// http
val Request.principalOrNull: Principal? get() = principal().orNull()

fun Request.queryOrNull(name: String): String? = query(name).orNull()

fun Request.headerOrNull(name: String): String? = header(name).orNull()

val Response.bodyOrNull: Any? get() = body().orNull()

val Response.contentTypeOrNull: String? get() = contentType().orNull()

val SseEvent.nameOrNull: String? get() = name().orNull()

val SseEvent.textOrNull: String? get() = text().orNull()

val SseEvent.jsonOrNull: String? get() = json().orNull()

/** The next text frame, or `null` once the socket has closed. */
fun WebSocket.receiveOrNull(): String? = receive().orNull()

// components
val EntityContext.attributionOrNull: String? get() = attribution().orNull()

val EntityContext.installationOrNull: String? get() = installation().orNull()

val EntityContext.forwardedFromOrNull: String? get() = forwardedFrom().orNull()

fun EntityContext.metadataOrNull(key: String): String? = metadata(key).orNull()

val EventContext.attributionOrNull: String? get() = attribution().orNull()

val EventContext.installationOrNull: String? get() = installation().orNull()

val EventContext.forwardedFromOrNull: String? get() = forwardedFrom().orNull()

val ChangeContext.installationOrNull: String? get() = installation().orNull()

val ChangeContext.standingOrNull: WorkflowLifecycle? get() = standing().orNull()

val <Row : Any> KeyedChange<Row>.standingOrNull: WorkflowLifecycle? get() = standing().orNull()

fun <Row : Any> ViewRows<Row>.getOrNull(key: String): Row? = get(key).orNull()

val Publication.contractOrNull: Contract? get() = contract().orNull()

val Publication.brokerOrNull: String? get() = broker().orNull()

val Publication.projectOrNull: String? get() = project().orNull()

val RecoverStrategy.failoverOrNull: NoInputStepRef<*>? get() = failover().orNull()

val WorkflowSettings.timeoutOrNull: Duration? get() = timeout().orNull()

// clients
fun <Row : Any> ViewQueries<Row>.getOrNull(key: String): Row? = get(key).orNull()

val CommandException.workflowFailureOrNull: WorkflowFailure? get() = workflowFailure().orNull()

fun SecretStore.getOrNull(name: String): String? = get(name).orNull()

val Bucket.nameOrNull: String? get() = name().orNull()

fun Bucket.getOrNull(name: String): StoredObject? = get(name).orNull()

fun Bucket.statOrNull(name: String): ObjectInfo? = stat(name).orNull()
