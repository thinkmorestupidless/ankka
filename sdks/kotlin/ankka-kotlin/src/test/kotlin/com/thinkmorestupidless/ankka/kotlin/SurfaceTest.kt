package com.thinkmorestupidless.ankka.kotlin

import java.lang.reflect.GenericArrayType
import java.lang.reflect.Member
import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.lang.reflect.TypeVariable
import java.lang.reflect.WildcardType
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.reflect.KVisibility
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Nothing Scala, Pekko or jsoniter on the Kotlin surface, as `SurfaceSuite` holds the Java one: every
 * public class the Kotlin SDK compiles to, and every type its public and protected members name, is
 * Kotlin's, Java's, JSpecify's or the SDKs' own. Read from the compiled classes, so a type that
 * reaches the surface through inference is caught too.
 */
class SurfaceTest {
    private val allowed =
        listOf(
            "kotlin.",
            "kotlinx.",
            "java.",
            "javax.",
            "org.jspecify.",
            "com.thinkmorestupidless.ankka.javasdk.",
            "com.thinkmorestupidless.ankka.kotlin.",
        )

    // `RefsKt`, a file facade Kotlin source cannot name; its class loader is the compiled SDK's.
    private val anchor: Class<*> = Class.forName("com.thinkmorestupidless.ankka.kotlin.RefsKt")

    private val classes: List<Class<*>> by lazy {
        val dir = Path.of(anchor.protectionDomain.codeSource.location.toURI())
        Files.walk(dir).use { paths ->
            paths
                .filter { it.extension == "class" }
                .map { dir.relativize(it).invariantSeparatorsPathString.removeSuffix(".class").replace('/', '.') }
                .toList()
        }
            .map { Class.forName(it, false, anchor.classLoader) }
            .filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic && kotlinVisible(it) }
    }

    // `internal` and `private` Kotlin classes can be public in the bytecode; Kotlin's own metadata
    // says which they are. A file's facade (`RefsKt`) has no visibility of its own.
    private fun kotlinVisible(cls: Class<*>): Boolean =
        try {
            cls.kotlin.visibility.let { it == KVisibility.PUBLIC || it == KVisibility.PROTECTED }
        } catch (_: UnsupportedOperationException) {
            true
        }

    /** Every surface problem: the class or member, and the type it names. */
    private fun problems(): List<String> =
        classes.flatMap { cls ->
            val members =
                listOf("${cls.name} extends" to (listOfNotNull(cls.genericSuperclass) + cls.genericInterfaces)) +
                    cls.declaredMethods.filter(::visible).map {
                        "${cls.name}.${it.name}" to (it.genericParameterTypes.toList() + it.genericExceptionTypes + it.genericReturnType)
                    } +
                    cls.declaredConstructors.filter(::visible).map { "${cls.name}.<init>" to it.genericParameterTypes.toList() } +
                    cls.declaredFields.filter(::visible).map { "${cls.name}.${it.name}" to listOf(it.genericType) }
            members.flatMap { (where, types) -> types.flatMap(::names).filterNot(::ok).map { "$where: $it" } }
        }

    // An `internal` function's name carries `$` and its module; so does every synthetic accessor.
    private fun visible(member: Member): Boolean =
        !member.isSynthetic &&
            !member.name.contains('$') &&
            (Modifier.isPublic(member.modifiers) || Modifier.isProtected(member.modifiers))

    private fun ok(name: String): Boolean = !name.contains('.') || allowed.any(name::startsWith)

    private fun names(type: Type): List<String> =
        when (type) {
            is Class<*> ->
                when {
                    type.isArray -> names(type.componentType)
                    type.isPrimitive -> emptyList()
                    else -> listOf(type.name)
                }
            is ParameterizedType -> names(type.rawType) + type.actualTypeArguments.flatMap(::names)
            is GenericArrayType -> names(type.genericComponentType)
            is WildcardType -> (type.upperBounds + type.lowerBounds).flatMap(::names)
            is TypeVariable<*> -> type.bounds.flatMap(::names)
            else -> emptyList()
        }

    @Test
    fun `nothing Scala, Pekko or jsoniter on the surface`() {
        assertTrue(classes.any { it.name.endsWith(".dsl.EffectsKt") }, "the DSL was not read: ${classes.map { it.name }}")
        assertEquals(emptyList(), problems())
    }

    @Test
    fun `the check finds a Scala type when one is named`() {
        // A check that cannot fail proves nothing: the same reading over a Java SDK class that is not
        // surface, the bridge's, finds Scala.
        val bridge = Class.forName("com.thinkmorestupidless.ankka.javasdk.bridge.References")
        val named = bridge.declaredMethods.flatMap { it.genericParameterTypes.toList() + it.genericReturnType }.flatMap(::names)
        assertTrue(named.any { it.startsWith("scala.") }, named.toString())
        assertTrue(named.filter { it.startsWith("scala.") }.none(::ok))
    }
}
