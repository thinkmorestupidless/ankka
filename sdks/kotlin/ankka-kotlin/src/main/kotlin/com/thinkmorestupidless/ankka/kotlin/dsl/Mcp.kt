package com.thinkmorestupidless.ankka.kotlin.dsl

import com.thinkmorestupidless.ankka.javasdk.effect.Effect
import com.thinkmorestupidless.ankka.javasdk.mcp.McpTool
import com.thinkmorestupidless.ankka.javasdk.mcp.ToolBuilder
import com.thinkmorestupidless.ankka.kotlin.commandRef
import com.thinkmorestupidless.ankka.kotlin.suspend.onHandlerThread
import kotlin.reflect.KFunction1
import kotlin.reflect.KFunction2
import kotlin.reflect.KSuspendFunction1
import kotlin.reflect.KSuspendFunction2

// An MCP tool's handler as a suspending block, and a tool that is a handler named by a Kotlin
// function reference, suspending or not:
//
//   tool("add_item").describedAs("Adds an item").handling<AddItemArgs> { args -> ... }
//   tool("get_cart").describedAs("Reads one cart").query(ShoppingCart::getCart, "cartId")

/** A tool whose arguments are read as [A] and handed to [handler]. */
inline fun <reified A : Any> ToolBuilder.handling(noinline handler: suspend (A) -> Any?): McpTool =
    arguments(A::class.java).handle { args -> onHandlerThread { handler(args) } }

/** A tool that takes no arguments, answered by [handler]. */
fun ToolBuilder.handling(handler: suspend () -> Any?): McpTool = handle { onHandlerThread { handler() } }

/** A tool that is the command handler [handler], on the entity whose id is the argument [id]. */
@JvmName("suspendingCommand")
fun <C : Any, I : Any, O : Any> ToolBuilder.command(handler: KSuspendFunction2<C, I, Effect<O>>, id: String): McpTool =
    command(commandRef(handler), id)

/** A tool that is the command handler [handler], on the entity whose id is the argument [id]. */
@JvmName("suspendingNoArgCommand")
fun <C : Any, O : Any> ToolBuilder.command(handler: KSuspendFunction1<C, Effect<O>>, id: String): McpTool =
    command(commandRef(handler), id)

/** A tool that is the query [handler], on the entity whose id is the argument [id]. */
@JvmName("suspendingQuery")
fun <C : Any, I : Any, O : Any> ToolBuilder.query(handler: KSuspendFunction2<C, I, Effect<O>>, id: String): McpTool =
    query(commandRef(handler), id)

/** A tool that is the query [handler], on the entity whose id is the argument [id]. */
@JvmName("suspendingNoArgQuery")
fun <C : Any, O : Any> ToolBuilder.query(handler: KSuspendFunction1<C, Effect<O>>, id: String): McpTool =
    query(commandRef(handler), id)

/** A tool that is the command handler [handler], named by a Kotlin function reference. */
@JvmName("kotlinCommand")
fun <C : Any, I : Any, O : Any> ToolBuilder.command(handler: KFunction2<C, I, Effect<O>>, id: String): McpTool =
    command(commandRef(handler), id)

/** A tool that is the command handler [handler], named by a Kotlin function reference. */
@JvmName("kotlinNoArgCommand")
fun <C : Any, O : Any> ToolBuilder.command(handler: KFunction1<C, Effect<O>>, id: String): McpTool =
    command(commandRef(handler), id)
