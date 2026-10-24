package com.thinkmorestupidless.ankka.javasdk.bridge

import com.thinkmorestupidless.ankka.javasdk.annotations.ComponentId

import java.io.{ByteArrayInputStream, DataInputStream, InputStream}
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

  /**
   * The method a compiler-made adapter calls: Kotlin turns `ShoppingCart::getCart` into a Java
   * functional interface through a method of the calling class (`_init_$getCart`), so a
   * `SerializedLambda` names the adapter, not the handler. Its body loads its arguments and makes
   * one call; that call, other than to Kotlin's own runtime, is the handler. `None` when the class
   * file cannot be read or the method makes no such call.
   */
  def adapted(cls: Class[?], name: String, descriptor: String): Option[(String, String, String)] =
    val resource = cls.getName.replace('.', '/') + ".class"
    Option(cls.getClassLoader)
      .flatMap(loader => Option(loader.getResourceAsStream(resource)))
      .flatMap(in => Using.resource(in)(stream => calls(stream, name, descriptor)))
      .flatMap(_.filterNot(_._1.startsWith("kotlin.")).lastOption)

  /** Every invokevirtual, invokeinterface and invokespecial in one method, in order. */
  private def calls(
      stream: InputStream,
      name: String,
      descriptor: String
  ): Option[Vector[(String, String, String)]] =
    val in = DataInputStream(stream)
    in.readInt()
    in.readUnsignedShort()
    in.readUnsignedShort()
    val count       = in.readUnsignedShort()
    val strings     = mutable.Map.empty[Int, String]
    val classes     = mutable.Map.empty[Int, Int]
    val members     = mutable.Map.empty[Int, (Int, Int)]
    val nameAndType = mutable.Map.empty[Int, (Int, Int)]
    var index       = 1
    while index < count do
      in.readUnsignedByte() match
        case 1       => strings(index) = in.readUTF()
        case 7       => classes(index) = in.readUnsignedShort()
        case 10 | 11 => members(index) = (in.readUnsignedShort(), in.readUnsignedShort())
        case 12      => nameAndType(index) = (in.readUnsignedShort(), in.readUnsignedShort())
        case 8 | 16 | 19 | 20 => in.readUnsignedShort(): Unit
        case 15               =>
          in.readUnsignedByte()
          in.readUnsignedShort(): Unit
        case 3 | 4 | 9 | 17 | 18 => in.readInt(): Unit
        case 5 | 6               =>
          in.readLong()
          index += 1
        case tag => throw IllegalStateException(s"unknown constant pool tag $tag")
      index += 1
    def member(i: Int): Option[(String, String, String)] =
      for
        (c, nt)    <- members.get(i)
        owner      <- classes.get(c).flatMap(strings.get)
        (n, d)     <- nameAndType.get(nt)
        memberName <- strings.get(n)
        desc       <- strings.get(d)
      yield (owner.replace('/', '.'), memberName, desc)
    def skipAttributes(): Unit =
      (0 until in.readUnsignedShort()).foreach { _ =>
        in.readUnsignedShort()
        in.skipNBytes(in.readInt().toLong)
      }
    in.readUnsignedShort()                          // access
    in.readUnsignedShort()                          // this
    in.readUnsignedShort()                          // super
    in.skipNBytes(2L * in.readUnsignedShort())      // interfaces
    (0 until in.readUnsignedShort()).foreach { _ => // fields
      in.skipNBytes(6)
      skipAttributes()
    }
    val methods = in.readUnsignedShort()
    var found   = Option.empty[Vector[(String, String, String)]]
    (0 until methods).foreach { _ =>
      in.readUnsignedShort()
      val n = strings.get(in.readUnsignedShort())
      val d = strings.get(in.readUnsignedShort())
      (0 until in.readUnsignedShort()).foreach { _ =>
        val attribute = strings.get(in.readUnsignedShort())
        val bytes     = in.readNBytes(in.readInt())
        if found.isEmpty && n.contains(name) && d.contains(descriptor) && attribute.contains("Code")
        then
          val code = DataInputStream(ByteArrayInputStream(bytes))
          code.readUnsignedShort()
          code.readUnsignedShort()
          found = Some(invocations(code.readNBytes(code.readInt())).flatMap(member))
      }
    }
    found

  /** The constant pool index of every call instruction in `code`, walking it opcode by opcode. */
  private def invocations(code: Array[Byte]): Vector[Int] =
    def u1(i: Int) = code(i) & 0xff
    def u2(i: Int) = (u1(i) << 8) | u1(i + 1)
    def s4(i: Int) = (u1(i) << 24) | (u1(i + 1) << 16) | (u1(i + 2) << 8) | u1(i + 3)
    val out        = Vector.newBuilder[Int]
    var pc         = 0
    while pc < code.length do
      val op = u1(pc)
      if op == 0xb6 || op == 0xb7 || op == 0xb9 then out += u2(pc + 1)
      pc += (op match
        case 0x10 | 0x12 | 0xbc | 0xa9               => 2
        case o if o >= 0x15 && o <= 0x19             => 2
        case o if o >= 0x36 && o <= 0x3a             => 2
        case 0x11 | 0x13 | 0x14 | 0x84               => 3
        case o if o >= 0x99 && o <= 0xa8             => 3
        case o if o >= 0xb2 && o <= 0xb8             => 3
        case 0xbb | 0xbd | 0xc0 | 0xc1 | 0xc6 | 0xc7 => 3
        case 0xc5                                    => 4
        case 0xb9 | 0xba | 0xc8 | 0xc9               => 5
        case 0xc4                                    => if u1(pc + 1) == 0x84 then 6 else 4
        case 0xaa                                    =>
          val at   = (pc + 4) & ~3
          val low  = s4(at + 4)
          val high = s4(at + 8)
          at - pc + 12 + 4 * (high - low + 1)
        case 0xab =>
          val at = (pc + 4) & ~3
          at - pc + 8 + 8 * s4(at + 4)
        case _ => 1)
    out.result()

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
        case 15               =>
          in.readUnsignedByte()
          in.readUnsignedShort(): Unit
        case 3 | 4 | 9 | 10 | 11 | 12 | 17 | 18 => in.readInt(): Unit
        case 5 | 6                              =>
          in.readLong()
          index += 1 // a long or a double takes two slots
        case tag => throw IllegalStateException(s"unknown constant pool tag $tag")
      index += 1
    classes.toVector.flatMap(strings.get).filterNot(_.startsWith("[")).map(_.replace('/', '.'))
