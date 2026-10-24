package com.thinkmorestupidless.ankka.kotlin.codec

import com.thinkmorestupidless.ankka.javasdk.codec.EncodingException
import com.thinkmorestupidless.ankka.javasdk.codec.RecordShapes
import java.lang.reflect.InvocationTargetException
import kotlin.reflect.KParameter
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.jvm.isAccessible
import kotlin.reflect.jvm.javaConstructor

/**
 * How the Java SDK's codec reads a Kotlin class: a data class by its primary constructor, in the
 * order its parameters are declared, and an `object` (a `data object` case of a sealed type) as a
 * record with no fields. A parameter's default is taken for a field the encoded form does not have;
 * a nullable parameter is the encoding's absent form. The codec finds this class by its name the
 * first time it meets a Kotlin class (`RecordShapes.Kotlin`).
 */
class DataClassShapes : RecordShapes {

    override fun shapeOf(type: Class<*>): RecordShapes.Shape? {
        val kotlinClass = type.kotlin
        kotlinClass.objectInstance?.let { return ObjectShape(it) }
        if (!kotlinClass.isData) return null
        val constructor =
            kotlinClass.primaryConstructor
                ?: throw EncodingException(type.name, "is a data class with no primary constructor")
        constructor.isAccessible = true
        // The Java signature, not the Kotlin type: it is what a Java record of the same shape has,
        // `List<? extends E>` included, which the planner reads as `List<E>`.
        val javaTypes = constructor.javaConstructor!!.genericParameterTypes
        val properties = kotlinClass.memberProperties.associateBy { it.name }
        val parameters = constructor.parameters
        val fields =
            parameters.mapIndexed { i, parameter ->
                val name = parameter.name!!
                val property =
                    properties[name]
                        ?: throw EncodingException(
                            "${type.name}.$name",
                            "is a constructor parameter that is not a property; declare it val",
                        )
                property.isAccessible = true
                RecordShapes.FieldShape(
                    name,
                    javaTypes[i],
                    { value -> property.getter.call(value) },
                    parameter.type.isMarkedNullable,
                    parameter.isOptional,
                )
            }
        return DataClassShape(type, fields, parameters) { arguments -> constructor.callBy(arguments) }
    }

    private class ObjectShape(private val instance: Any) : RecordShapes.Shape {
        override fun fields(): List<RecordShapes.FieldShape> = emptyList()

        override fun construct(values: Array<Any?>, present: BooleanArray): Any = instance
    }

    private class DataClassShape(
        private val type: Class<*>,
        private val fields: List<RecordShapes.FieldShape>,
        private val parameters: List<KParameter>,
        private val call: (Map<KParameter, Any?>) -> Any,
    ) : RecordShapes.Shape {
        override fun fields(): List<RecordShapes.FieldShape> = fields

        override fun construct(values: Array<Any?>, present: BooleanArray): Any {
            // Only the fields the encoded form had: an absent one with a default takes the default.
            val arguments = HashMap<KParameter, Any?>(parameters.size)
            parameters.forEachIndexed { i, parameter ->
                when {
                    present[i] -> arguments[parameter] = values[i]
                    parameter.isOptional -> Unit
                    parameter.type.isMarkedNullable -> arguments[parameter] = null
                    else -> throw EncodingException("${type.name}.${parameter.name}", "is missing")
                }
            }
            return try {
                call(arguments)
            } catch (e: InvocationTargetException) {
                throw e.cause ?: e
            }
        }
    }
}
