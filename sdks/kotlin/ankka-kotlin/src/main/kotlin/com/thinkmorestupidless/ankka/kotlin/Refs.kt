package com.thinkmorestupidless.ankka.kotlin

import com.thinkmorestupidless.ankka.javasdk.client.CommandRef
import com.thinkmorestupidless.ankka.javasdk.client.EntityRef
import com.thinkmorestupidless.ankka.javasdk.client.Invocation
import com.thinkmorestupidless.ankka.javasdk.client.NoArgRef
import com.thinkmorestupidless.ankka.javasdk.client.NoInputStepRef
import com.thinkmorestupidless.ankka.javasdk.client.StepRef
import com.thinkmorestupidless.ankka.javasdk.effect.Effect
import com.thinkmorestupidless.ankka.javasdk.effect.StepEffect
import java.io.Serializable
import java.lang.invoke.MethodHandleInfo
import java.lang.invoke.MethodType
import java.lang.invoke.SerializedLambda
import java.lang.reflect.Method
import kotlin.reflect.KFunction
import kotlin.reflect.KFunction1
import kotlin.reflect.KFunction2
import kotlin.reflect.KSuspendFunction1
import kotlin.reflect.KSuspendFunction2
import kotlin.reflect.jvm.javaMethod

// A Kotlin function reference where the Java SDK takes a handler or step reference. A reference to
// a suspend fun cannot become a Java functional interface, so it is handed over as what a Java
// method reference writes in place of itself: the class, the method and its descriptor, which the
// SDK resolves through the same registry to the handler's wire name or the step's @Step name.

/** The step [step] names, which takes no input: `stepRef(Checkout::reserve)`. */
@JvmName("suspendingStepRef")
fun <W : Any> stepRef(step: KSuspendFunction1<W, StepEffect>): NoInputStepRef<W> = NamedStep(step)

/** The step [step] names, which takes no input. */
fun <W : Any> stepRef(step: KFunction1<W, StepEffect>): NoInputStepRef<W> = NamedStep(step)

/** The step [step] names, which takes [I]: `stepRef(Checkout::charge)`. */
@JvmName("suspendingInputStepRef")
fun <W : Any, I : Any> stepRef(step: KSuspendFunction2<W, I, StepEffect>): StepRef<W, I> = NamedInputStep(step)

/** The step [step] names, which takes [I]. */
@JvmName("inputStepRef")
fun <W : Any, I : Any> stepRef(step: KFunction2<W, I, StepEffect>): StepRef<W, I> = NamedInputStep(step)

/** The handler [handler] names, which takes [I]: `commandRef(ShoppingCart::addItem)`. */
@JvmName("suspendingCommandRef")
fun <C : Any, I : Any, O : Any> commandRef(handler: KSuspendFunction2<C, I, Effect<O>>): CommandRef<C, I, O> =
    NamedCommand(handler)

/** The handler [handler] names, which takes [I]. */
fun <C : Any, I : Any, O : Any> commandRef(handler: KFunction2<C, I, Effect<O>>): CommandRef<C, I, O> =
    NamedCommand(handler)

/** The handler [handler] names, which takes nothing: `commandRef(ShoppingCart::checkout)`. */
@JvmName("suspendingNoArgRef")
fun <C : Any, O : Any> commandRef(handler: KSuspendFunction1<C, Effect<O>>): NoArgRef<C, O> = NamedNoArg(handler)

/** The handler [handler] names, which takes nothing. */
@JvmName("noArgRef")
fun <C : Any, O : Any> commandRef(handler: KFunction1<C, Effect<O>>): NoArgRef<C, O> = NamedNoArg(handler)

/** A call to the `suspend fun` handler [handler] names, which takes [I]. */
@JvmName("callSuspendingHandler")
fun <C : Any, I : Any, O : Any> EntityRef.call(handler: KSuspendFunction2<C, I, Effect<O>>): Invocation<I, O> =
    call(commandRef(handler))

/** A call to the `suspend fun` handler [handler] names, which takes nothing. */
@JvmName("callSuspendingNoArgHandler")
fun <C : Any, O : Any> EntityRef.call(handler: KSuspendFunction1<C, Effect<O>>): Invocation<Void, O> =
    call(commandRef(handler))

private class NamedStep<W : Any>(private val step: KFunction<StepEffect>) : NoInputStepRef<W> {
    override fun apply(workflow: W): StepEffect = refused(step)

    @Suppress("unused") // read by the SDK, as it reads a method reference's
    private fun writeReplace(): Any = serialized(step, NoInputStepRef::class.java)
}

private class NamedInputStep<W : Any, I : Any>(private val step: KFunction<StepEffect>) : StepRef<W, I> {
    override fun apply(workflow: W, input: I): StepEffect = refused(step)

    @Suppress("unused") // read by the SDK, as it reads a method reference's
    private fun writeReplace(): Any = serialized(step, StepRef::class.java)
}

private class NamedCommand<C : Any, I : Any, O : Any>(private val handler: KFunction<Effect<O>>) : CommandRef<C, I, O> {
    override fun apply(component: C, input: I): Effect<O> = refused(handler)

    @Suppress("unused") // read by the SDK, as it reads a method reference's
    private fun writeReplace(): Any = serialized(handler, CommandRef::class.java)
}

private class NamedNoArg<C : Any, O : Any>(private val handler: KFunction<Effect<O>>) : NoArgRef<C, O> {
    override fun apply(component: C): Effect<O> = refused(handler)

    @Suppress("unused") // read by the SDK, as it reads a method reference's
    private fun writeReplace(): Any = serialized(handler, NoArgRef::class.java)
}

private fun refused(function: KFunction<*>): Nothing =
    throw UnsupportedOperationException("$function names a handler for the SDK to call; it is not called through this")

/**
 * What a Kotlin function reference writes in place of itself where the Java SDK takes the functional
 * interface [functional]: how the SDK's own Kotlin modules hand a reference to a `suspend fun` over.
 */
fun functionReference(function: KFunction<*>, functional: Class<*>): Serializable = serialized(function, functional)

private fun serialized(function: KFunction<*>, functional: Class<*>): Serializable {
    val method: Method =
        function.javaMethod ?: throw IllegalArgumentException("$function is not a method of a class")
    val descriptor = MethodType.methodType(method.returnType, method.parameterTypes).toMethodDescriptorString()
    val sam = functional.methods.single { it.name == "apply" }
    return SerializedLambda(
        method.declaringClass,
        functional.name.replace('.', '/'),
        sam.name,
        MethodType.methodType(sam.returnType, sam.parameterTypes).toMethodDescriptorString(),
        MethodHandleInfo.REF_invokeVirtual,
        method.declaringClass.name.replace('.', '/'),
        method.name,
        descriptor,
        descriptor,
        emptyArray(),
    )
}
