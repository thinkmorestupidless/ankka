package com.thinkmorestupidless.ankka.sidecar.conformance

import java.net.{URL, URLClassLoader}
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

/**
 * The class loader of a reference built outside sbt (`classpath:<file>:<class>`): `file` is a jar
 * or a directory of classes, or a `.txt` listing one jar or directory a line, as the Kotlin build's
 * `conformance` task writes its runtime class path less what sbt already holds. A class is looked
 * for in the listed entries first and then in the suite's loader: the Java SDK, the runtime and
 * Scala are not listed, so they are the suite's, while the reference's language libraries are its
 * own even where the suite holds an older copy (a `kotlin-stdlib` the Java SDK reaches).
 */
object ClasspathReference:

  def loader(file: Path, parent: ClassLoader): URLClassLoader =
    new URLClassLoader(entries(file).map(_.toUri.toURL).toArray[URL], parent):
      override protected def loadClass(name: String, resolve: Boolean): Class[?] =
        getClassLoadingLock(name).synchronized {
          val found = Option(findLoadedClass(name)).orElse {
            if name.startsWith("java.") then None
            else
              try Some(findClass(name))
              catch case _: ClassNotFoundException => None
          }
          val loaded = found.getOrElse(super.loadClass(name, false))
          if resolve then resolveClass(loaded)
          loaded
        }

  /** What `file` names, each of which must exist: a missing entry is a broken build, not a skip. */
  def entries(file: Path): Vector[Path] =
    if !Files.exists(file) then
      throw IllegalArgumentException(s"ankka.conformance.reference: no file at $file")
    val listed =
      if file.getFileName.toString.endsWith(".txt") then
        Files
          .readAllLines(file)
          .asScala
          .map(_.trim)
          .filter(_.nonEmpty)
          .map(Path.of(_))
          .toVector
      else Vector(file)
    if listed.isEmpty then
      throw IllegalArgumentException(s"ankka.conformance.reference: $file lists nothing")
    listed.filterNot(Files.exists(_)) match
      case Vector() => listed
      case missing =>
        throw IllegalArgumentException(
          s"ankka.conformance.reference: $file lists ${missing.mkString(", ")}, which do not exist"
        )
