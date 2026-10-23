package com.thinkmorestupidless.ankka.javasdk

import java.lang.reflect.{
  GenericArrayType,
  Member,
  Modifier,
  ParameterizedType,
  Type,
  TypeVariable,
  WildcardType
}
import java.nio.file.{Files, Paths}
import scala.jdk.CollectionConverters.*

/**
 * Nothing Scala on the Java surface (R12): every public class of a Java module's API, and every
 * type its public and protected members name, is Java's, JSpecify's or the SDK's own. Run over the
 * compiled classes, so a Scala type that reaches the surface through inference is caught too.
 */
abstract class SurfaceChecks(anchor: Class[?], root: String) extends munit.FunSuite:

  /** Packages a surface type may come from. */
  protected def allowed: Vector[String] =
    Vector(
      "java.",
      "javax.",
      "org.jspecify.",
      "com.thinkmorestupidless.ankka.javasdk.",
      "com.thinkmorestupidless.ankka.agent.javasdk."
    )

  /** Packages inside `root` that are not surface. */
  protected def internal: Vector[String] = Vector(".bridge.")

  private def classes: Vector[Class[?]] =
    val dir  = Paths.get(anchor.getProtectionDomain.getCodeSource.getLocation.toURI)
    val base = dir.resolve(root.replace('.', '/'))
    Files
      .walk(base)
      .iterator
      .asScala
      .filter(p => p.toString.endsWith(".class"))
      .map(p => dir.relativize(p).toString.stripSuffix(".class").replace('/', '.'))
      .filterNot(n => internal.exists(n.contains))
      .map(n => Class.forName(n, false, anchor.getClassLoader))
      .filter(c => Modifier.isPublic(c.getModifiers))
      .toVector

  /** Every surface problem: the class or member, and the type it names. */
  def problems: Vector[String] =
    classes.flatMap { cls =>
      val members: Vector[(String, Seq[Type])] =
        Vector(
          s"${cls.getName} extends" -> (Option(cls.getGenericSuperclass).toSeq ++
            cls.getGenericInterfaces)
        ) ++
          cls.getDeclaredMethods.toVector
            .filter(visible)
            .map(m =>
              s"${cls.getName}.${m.getName}" -> (m.getGenericParameterTypes.toSeq ++
                m.getGenericExceptionTypes :+ m.getGenericReturnType)
            ) ++
          cls.getDeclaredConstructors.toVector
            .filter(visible)
            .map(c => s"${cls.getName}.<init>" -> c.getGenericParameterTypes.toSeq) ++
          cls.getDeclaredFields.toVector
            .filter(visible)
            .map(f => s"${cls.getName}.${f.getName}" -> Seq(f.getGenericType))
      members.flatMap((where, types) => types.flatMap(names).filterNot(ok).map(t => s"$where: $t"))
    }

  private def visible(m: Member): Boolean =
    !m.isSynthetic && (Modifier.isPublic(m.getModifiers) || Modifier.isProtected(m.getModifiers))

  private def ok(name: String): Boolean =
    !name.contains('.') || (allowed.exists(name.startsWith) && !internal.exists(name.contains))

  private def names(t: Type): Seq[String] = t match
    case c: Class[?] =>
      if c.isArray then names(c.getComponentType) else if c.isPrimitive then Nil else Seq(c.getName)
    case p: ParameterizedType => names(p.getRawType) ++ p.getActualTypeArguments.flatMap(names)
    case g: GenericArrayType  => names(g.getGenericComponentType)
    case w: WildcardType      => (w.getUpperBounds ++ w.getLowerBounds).flatMap(names).toSeq
    case v: TypeVariable[?]   => v.getBounds.flatMap(names).toSeq
    case _                    => Nil

  test("nothing Scala, Pekko or jsoniter on the surface") {
    assert(classes.nonEmpty, s"no classes found under $root")
    assertEquals(problems, Vector.empty)
  }

class SurfaceSuite extends SurfaceChecks(classOf[Ankka], "com.thinkmorestupidless.ankka.javasdk")
