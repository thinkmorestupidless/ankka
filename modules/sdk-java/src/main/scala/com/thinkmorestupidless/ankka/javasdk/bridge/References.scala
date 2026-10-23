package com.thinkmorestupidless.ankka.javasdk.bridge

import com.thinkmorestupidless.ankka.javasdk.annotations.ComponentId

import java.io.{DataInputStream, InputStream}
import scala.collection.mutable
import scala.util.Using

/**
 * The components a class names, read from its class file's constant pool once at start. A method
 * reference such as `ShoppingCart::addItem` is a constant of the class that writes it, so a call to
 * a component the service does not register is found before any request. A call by wire name is a
 * string, and is not found here.
 */
private[ankka] object References:

  def components(cls: Class[?]): Vector[Class[?]] =
    classNames(cls).iterator
      .flatMap(name =>
        try Some(Class.forName(name, false, cls.getClassLoader))
        catch case _: ClassNotFoundException | _: LinkageError => None
      )
      .filter(c => c != cls && c.isAnnotationPresent(classOf[ComponentId]))
      .toVector
      .distinct

  /**
   * The binary names of every class constant in `cls`'s class file; none when it cannot be read.
   */
  private def classNames(cls: Class[?]): Vector[String] =
    val resource = cls.getName.replace('.', '/') + ".class"
    Option(cls.getClassLoader)
      .flatMap(loader => Option(loader.getResourceAsStream(resource)))
      .fold(Vector.empty[String])(in => Using.resource(in)(read))

  private def read(stream: InputStream): Vector[String] =
    val in = DataInputStream(stream)
    in.readInt() // magic
    in.readUnsignedShort()
    in.readUnsignedShort()
    val count   = in.readUnsignedShort()
    val strings = mutable.Map.empty[Int, String]
    val classes = mutable.ArrayBuffer.empty[Int]
    var index   = 1
    while index < count do
      in.readUnsignedByte() match
        case 1                => strings(index) = in.readUTF()
        case 7                => classes += in.readUnsignedShort()
        case 8 | 16 | 19 | 20 => in.readUnsignedShort(): Unit
        case 15 =>
          in.readUnsignedByte()
          in.readUnsignedShort(): Unit
        case 3 | 4 | 9 | 10 | 11 | 12 | 17 | 18 => in.readInt(): Unit
        case 5 | 6 =>
          in.readLong()
          index += 1 // a long or a double takes two slots
        case tag => throw IllegalStateException(s"unknown constant pool tag $tag")
      index += 1
    classes.toVector.flatMap(strings.get).filterNot(_.startsWith("[")).map(_.replace('/', '.'))
