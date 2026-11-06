package com.thinkmorestupidless.ankka.runtime

import com.github.plokhotnyuk.jsoniter_scala.core.{JsonValueCodec, readFromArray}
import com.thinkmorestupidless.ankka.core.{ComponentId, Keyring, PersonalScope, Serializer}

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets.UTF_8
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * A view's lookup tokens (feature 042, R13): HMAC-SHA-256, under the project's lookup key, of a
 * lookup field's path and its value, kept in the row's `lookup` column as `{"<path>":"<token>"}`. A
 * declared query compares a value's token there, never the value. The value is the field's text
 * when its JSON is a string (`ada@example.com`), its JSON otherwise (`42`): what a caller passes
 * for it as a query's value.
 */
private[ankka] object LookupTokens:

  def token(key: IArray[Byte], path: String, value: Array[Byte]): String =
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(IArray.genericWrapArray(key).toArray, "HmacSHA256"))
    val name = path.getBytes(UTF_8)
    mac.update(ByteBuffer.allocate(4).putInt(name.length).array())
    mac.update(name)
    mac.update(value)
    Base64.getEncoder.encodeToString(mac.doFinal())

  private given JsonValueCodec[String] =
    com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker.make

  /** What a field's own JSON is tokened as: a string's text, any other value's JSON. */
  def canonical(json: Array[Byte]): Array[Byte] =
    val text = String(json, UTF_8).trim
    if text.startsWith("\"") then readFromArray[String](json).getBytes(UTF_8)
    else text.getBytes(UTF_8)

  /** A query's value, as the field it is compared with is tokened. */
  def ofValue(key: IArray[Byte], path: String, value: String): String =
    token(key, path, value.getBytes(UTF_8))

  /**
   * The row's lookup column, from the row as written and the fields sealed while writing it, or a
   * refusal naming an envelope at a path the view does not declare as personal (a hand-written
   * codec). `None` when the row holds no lookup field.
   *
   * A lookup field is never inside a collection (`View.Companion.lookup` refuses a `[*]` path), so
   * each lookup path is at most one envelope of the row and one token. The envelopes are paired
   * with the values sealed while writing, in order; a row whose counts differ cannot be paired, and
   * its write is refused rather than written with no tokens a query could find.
   */
  def forRow(
      view: ComponentId,
      json: String,
      sealedFields: Vector[Option[Array[Byte]]],
      fields: ViewFields,
      key: => IArray[Byte]
  ): Either[String, Option[String]] =
    if !json.contains("\"$personal\"") then Right(None)
    else
      val found = Envelopes.in(json)
      found.find(f => !fields.personal.contains(f.path)) match
        case Some(f) =>
          Left(
            s"view '$view' wrote a row holding a personal envelope at '${f.path}', which is not a " +
              s"personal field of its row" +
              fields.personal.mkString(" (its personal fields: ", ", ", ")") +
              "; a personal field is declared by its type, Personal[...]"
          )
        case None if fields.lookup.nonEmpty && found.size != sealedFields.size =>
          Left(
            s"view '$view' wrote a row holding ${found.size} personal envelopes, of which " +
              s"${sealedFields.size} were sealed while writing it; its lookup tokens cannot be " +
              "paired with its fields, so the row is not written"
          )
        case None =>
          val tokens = found.zip(sealedFields).collect {
            case (f, Some(value)) if fields.lookup.contains(f.path) =>
              f.path -> token(key, f.path, canonical(value))
          }
          if tokens.isEmpty then Right(None)
          else Right(Some(tokens.map((p, t) => s"\"$p\":\"$t\"").mkString("{", ",", "}")))

/**
 * Writes a view's rows (feature 042): the row under the service's keyring, and its lookup tokens
 * beside it when the view has lookup fields. A row holding an envelope where the view declares no
 * personal field fails the write, naming the path, and the projection retries it as any failed
 * write: the developer sees the path rather than a ciphertext no query can find.
 */
private[ankka] object ViewRowWriter:

  /** The writer of a view in this process, under the keyring of the service on `system`. */
  def of[A](descriptor: com.thinkmorestupidless.ankka.sdk.ViewDescriptor[?, ?, A])(using
      system: org.apache.pekko.actor.typed.ActorSystem[?]
  ): ViewRowWriter[A] =
    new ViewRowWriter(
      descriptor.componentId,
      descriptor.tableName,
      descriptor.rowSerializer,
      ViewFields(descriptor.personalFields, descriptor.lookupFields),
      com.thinkmorestupidless.ankka.runtime.keyring.ServiceKeyring.of(system)
    )

private[ankka] final class ViewRowWriter[A](
    view: ComponentId,
    table: String,
    serializer: Serializer[A],
    fields: ViewFields,
    keyring: => Keyring
):
  def upsert(key: String, row: A): SqlFragment =
    val k             = keyring
    val (bytes, seen) = Keyring.binding(k)(PersonalScope.collecting(serializer.toBytes(row)))
    val json          = String(bytes, UTF_8)
    LookupTokens.forRow(view, json, seen, fields, k.lookupKey(k.project)) match
      case Left(problem) => throw IllegalStateException(problem)
      case Right(lookup) =>
        if fields.lookup.isEmpty then ViewStore.upsert(table, key, json)
        else ViewStore.upsertWithLookup(table, key, json, lookup)

/**
 * The personal envelopes of a row's JSON, each with its path as a view's fields are written
 * (`email`, `address.line1`, `contacts[*].value`), in document order: the order its fields were
 * sealed in. A small reader of its own, since the runtime carries no JSON tree.
 */
private[ankka] object Envelopes:

  final case class Found(path: String, subject: String)

  def in(json: String): Vector[Found] =
    val out              = Vector.newBuilder[Found]
    var i                = 0
    def ws(): Unit       = while i < json.length && json(i).isWhitespace do i += 1
    def string(): String =
      val sb = StringBuilder()
      i += 1
      while json(i) != '"' do
        if json(i) == '\\' then
          json(i + 1) match
            case 'u' =>
              sb += Integer.parseInt(json.substring(i + 2, i + 6), 16).toChar
              i += 6
            case c =>
              sb += (c match
                case 'n' => '\n'
                case 't' => '\t'
                case 'r' => '\r'
                case 'b' => '\b'
                case 'f' => '\f'
                case o   => o)
              i += 2
        else
          sb += json(i)
          i += 1
      i += 1
      sb.toString
    def join(path: String, label: String) = if path.isEmpty then label else s"$path.$label"
    def value(path: String): Unit         =
      ws()
      json(i) match
        case '{' =>
          i += 1
          ws()
          var first    = true
          var subject  = ""
          var personal = false
          while json(i) != '}' do
            if !first then
              i += 1 // ','
              ws()
            first = false
            val label = string()
            ws()
            i += 1 // ':'
            ws()
            if label == "$personal" then personal = true
            if personal && label == "subject" && json(i) == '"' then subject = string()
            else value(join(path, label))
            ws()
          i += 1
          if personal then out += Found(path, subject)
        case '[' =>
          i += 1
          ws()
          var first = true
          while json(i) != ']' do
            if !first then
              i += 1
              ws()
            first = false
            value(s"$path[*]")
            ws()
          i += 1
        case '"' => string(): Unit
        case _   =>
          while i < json.length && !",}] \n\r\t".contains(json(i)) do i += 1
    value("")
    out.result()
