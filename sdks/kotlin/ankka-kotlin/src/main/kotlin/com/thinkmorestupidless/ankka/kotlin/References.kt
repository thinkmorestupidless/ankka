package com.thinkmorestupidless.ankka.kotlin

import com.thinkmorestupidless.ankka.javasdk.client.NoInputStepRef
import com.thinkmorestupidless.ankka.javasdk.client.StepRef
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

// A Kotlin function reference where the Java SDK takes a step reference. A reference to a suspend
// fun cannot become a Java functional interface, so it is handed over as what a Java method
// reference writes in place of itself: the class, the method and its descriptor, which the SDK
// resolves to the step's @Step name.

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

private class NamedStep<W : Any>(private val step: KFunction<StepEffect>) : NoInputStepRef<W> {
    override fun apply(workflow: W): StepEffect = throw UnsupportedOperationException("$step names a step; the SDK runs it")

    @Suppress("unused") // read by the SDK, as it reads a method reference's
    private fun writeReplace(): Any = serialized(step, NoInputStepRef::class.java)
}

private class NamedInputStep<W : Any, I : Any>(private val step: KFunction<StepEffect>) : StepRef<W, I> {
    override fun apply(workflow: W, input: I): StepEffect =
        throw UnsupportedOperationException("$step names a step; the SDK runs it")

    @Suppress("unused") // read by the SDK, as it reads a method reference's
    private fun writeReplace(): Any = serialized(step, StepRef::class.java)
}

private fun serialized(function: KFunction<*>, functional: Class<*>): Serializable {
    val method: Method =
        function.javaMethod ?: throw IllegalArgumentException("$function is not a method of a class")
    val descriptor = MethodType.methodType(method.returnType, method.parameterTypes).toMethodDescriptorString()
    val owner = method.declaringClass.name.replace('.', '/')
    val sam = functional.methods.single { it.name == "apply" }
    return SerializedLambda(
        method.declaringClass,
        functional.name.replace('.', '/'),
        sam.name,
        MethodType.methodType(sam.returnType, sam.parameterTypes).toMethodDescriptorString(),
        MethodHandleInfo.REF_invokeVirtual,
        owner,
        method.name,
        descriptor,
        descriptor,
        emptyArray(),
    )
}
