package com.thinkmorestupidless.ankka.javasdk.bridge

import com.thinkmorestupidless.ankka.core
import com.thinkmorestupidless.ankka.javasdk.codec.Serializer as JavaSerializer

/**
 * A Java `Serializer` seen by the Scala runtime, and a Scala one seen by Java. The bytes are the
 * same either way: this changes the interface, never the encoding.
 */
private[ankka] object CodecBridge:

  def toScala[A](java: JavaSerializer[A]): core.Serializer[A] = java match
    case FromScala(scala) => scala
    case _                => ToScala(java)

  def toJava[A](scala: core.Serializer[A]): JavaSerializer[A] = scala match
    case ToScala(java) => java
    case _             => FromScala(scala)

  private final case class ToScala[A](java: JavaSerializer[A]) extends core.Serializer[A]:
    def manifest: String                 = java.manifest
    def toBytes(value: A): Array[Byte]   = java.toBytes(value)
    def fromBytes(bytes: Array[Byte]): A = java.fromBytes(bytes)
    override def toString: String        = s"Java($java)"

  private final case class FromScala[A](scala: core.Serializer[A]) extends JavaSerializer[A]:
    def manifest: String                 = scala.manifest
    def toBytes(value: A): Array[Byte]   = scala.toBytes(value)
    def fromBytes(bytes: Array[Byte]): A = scala.fromBytes(bytes)
    override def toString: String        = s"Scala($scala)"
