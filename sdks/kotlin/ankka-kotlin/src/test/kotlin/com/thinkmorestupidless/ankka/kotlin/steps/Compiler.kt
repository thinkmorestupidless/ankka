package com.thinkmorestupidless.ankka.kotlin.steps

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler

/** What compiling one Kotlin source against this test's classpath said. */
data class Compiled(val succeeded: Boolean, val messages: String)

/**
 * Compiles [source] as a developer's build would: against the Java and Kotlin SDKs on this test's
 * classpath, with the same strict reading of the Java SDK's nullness the SDK's own build has.
 */
fun compile(source: String): Compiled {
    val dir = Files.createTempDirectory("ankka-kotlin-compile")
    val file = dir.resolve("Service.kt")
    Files.writeString(file, source)
    val out = ByteArrayOutputStream()
    val exit =
        PrintStream(out, true, Charsets.UTF_8).use { stream ->
            K2JVMCompiler()
                .exec(
                    stream,
                    "-classpath", System.getProperty("java.class.path"),
                    "-d", dir.resolve("classes").toString(),
                    "-no-stdlib",
                    "-no-reflect",
                    "-jvm-target", "21",
                    "-Xjspecify-annotations=strict",
                    file.toString(),
                )
        }
    dir.toFile().deleteRecursively()
    return Compiled(exit == ExitCode.OK, out.toString(Charsets.UTF_8))
}
