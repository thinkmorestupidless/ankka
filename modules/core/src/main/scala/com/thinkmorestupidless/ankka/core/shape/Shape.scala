package com.thinkmorestupidless.ankka.core.shape

import com.thinkmorestupidless.ankka.core.graph.GraphJson

/**
 * A type's name, for a shape that is referred to rather than written out (feature 067): `full` is
 * the fully qualified name, type arguments included, which tells two types apart; `simple` is what
 * a document calls it when nothing else has the same simple name.
 */
final case class TypeName(full: String, simple: String):
  /** The dotted qualifiers before the simple name: `players` for `shoppingcart.players.Profile`. */
  def qualifiers: Vector[String] =
    full.takeWhile(_ != '[').split('.').toVector.dropRight(1)

/** What a `Shape.Ref` refers to. */
final case class Named(name: TypeName, shape: Shape)

/**
 * The JSON form of a value, as the platform's codec writes it (feature 067, data-model.md). Plain
 * data: nothing here reads or writes JSON but `render`.
 */
enum Shape:
  /** `kind` is `string`, `integer`, `number` or `boolean`; `format` one of JSON Schema's. */
  case Scalar(kind: String, format: Option[String] = None)

  /** An object; `properties` in declaration order, `required` the names a reader needs. */
  case Obj(properties: Vector[(String, Shape)], required: Vector[String])
  case Arr(items: Shape)

  /** An object of any keys whose values all have one shape. */
  case Dict(values: Shape)

  /** One of several cases, each an object told apart by a `type` field holding the case's name. */
  case OneOf(cases: Vector[(String, Shape)])

  /** `of`, or `null`. */
  case Nullable(of: Shape)
  case Ref(name: TypeName)

  /** JSON Schema written by hand, a JSON object, written into a document as it is. */
  case Raw(json: String)

object Shape:
  val string: Shape  = Scalar("string")
  val integer: Shape = Scalar("integer")
  val number: Shape  = Scalar("number")
  val boolean: Shape = Scalar("boolean")

  /** The discriminator `Codecs.make` writes in every case of a sealed hierarchy. */
  val Discriminator = "type"

  /** What is wrong with `shape`, each a sentence: a `Raw` that is not a JSON object. */
  def problems(shape: Shape): Vector[String] = shape match
    case Raw(json) =>
      GraphJson.parse(json.getBytes("UTF-8")) match
        case Right(GraphJson.Obj(_)) => Vector.empty
        case Right(_)                => Vector(s"the JSON Schema $json is not a JSON object")
        case Left(reason)            => Vector(s"the JSON Schema $json is not JSON: $reason")
    case Obj(properties, _) => properties.flatMap((_, s) => problems(s))
    case Arr(items)         => problems(items)
    case Dict(values)       => problems(values)
    case OneOf(cases)       => cases.flatMap((_, s) => problems(s))
    case Nullable(of)       => problems(of)
    case Scalar(_, _)       => Vector.empty
    case Ref(_)             => Vector.empty

  /** Every name `shape` refers to, in the order met. */
  def references(shape: Shape): Vector[TypeName] = shape match
    case Ref(name)          => Vector(name)
    case Obj(properties, _) => properties.flatMap((_, s) => references(s))
    case Arr(items)         => references(items)
    case Dict(values)       => references(values)
    case OneOf(cases)       => cases.flatMap((_, s) => references(s))
    case Nullable(of)       => references(of)
    case Scalar(_, _)       => Vector.empty
    case Raw(_)             => Vector.empty

  /**
   * `shape` as JSON Schema 2020-12, its keys in contracts/document.md's order; `ref` says where a
   * reference points (`#/components/schemas/<name>` in a document, `#/$defs/<name>` alone).
   */
  def render(shape: Shape, ref: TypeName => String): GraphJson = shape match
    case Scalar(kind, format) =>
      J.Obj(Vector("type" -> J.Str(kind)) ++ format.map(f => "format" -> J.Str(f)))
    case Obj(properties, required) => objectSchema(properties, required, ref, None)
    case Arr(items)                =>
      J.Obj(Vector("type" -> J.Str("array"), "items" -> render(items, ref)))
    case Dict(values) =>
      J.Obj(Vector("type" -> J.Str("object"), "additionalProperties" -> render(values, ref)))
    case OneOf(cases) =>
      J.Obj(Vector("oneOf" -> J.Arr(cases.map { (name, s) =>
        s match
          case Obj(properties, required) => objectSchema(properties, required, ref, Some(name))
          case other                     => render(other, ref)
      })))
    case Nullable(of) =>
      render(of, ref) match
        case J.Obj(("type", J.Str(kind)) +: rest) =>
          J.Obj(("type" -> J.Arr(Vector(J.Str(kind), J.Str("null")))) +: rest)
        case other =>
          J.Obj(Vector("anyOf" -> J.Arr(Vector(other, J.Obj(Vector("type" -> J.Str("null")))))))
    case Ref(name) => J.Obj(Vector("$ref" -> J.Str(ref(name))))
    case Raw(json) =>
      GraphJson.parse(json.getBytes("UTF-8")) match
        case Right(parsed) => sorted(parsed)
        case Left(_)       => J.Obj(Vector.empty)

  private val J = GraphJson

  private def objectSchema(
      properties: Vector[(String, Shape)],
      required: Vector[String],
      ref: TypeName => String,
      discriminated: Option[String]
  ): GraphJson =
    val tag = discriminated.toVector.map(name =>
      Discriminator -> J.Obj(Vector("type" -> J.Str("string"), "const" -> J.Str(name)))
    )
    val props = tag ++ properties.map((n, s) => n -> render(s, ref))
    val needs = discriminated.map(_ => Discriminator).toVector ++ required
    J.Obj(
      Vector("type" -> J.Str("object")) ++
        (if props.isEmpty then Vector.empty else Vector("properties" -> J.Obj(props))) ++
        (if needs.isEmpty then Vector.empty else Vector("required" -> J.Arr(needs.map(J.Str(_)))))
    )

  /** A hand-written schema's keys, sorted at every level, so it renders the same on every run. */
  private def sorted(json: GraphJson): GraphJson = json match
    case J.Obj(fields) => J.Obj(fields.sortBy(_._1).map((k, v) => k -> sorted(v)))
    case J.Arr(items)  => J.Arr(items.map(sorted))
    case other         => other

  /**
   * `shape` alone, with the definitions it reaches under `$defs`, as JSON Schema 2020-12: what a
   * validator is handed.
   */
  def standalone(shape: Shape, definitions: Vector[Named]): GraphJson =
    def key(name: TypeName): String = name.full.replaceAll("[^A-Za-z0-9._-]", "_")
    def ref(name: TypeName): String = s"#/$$defs/${key(name)}"
    val defs                        = definitions.distinctBy(_.name)
    val body                        = render(shape, ref) match
      case J.Obj(fields) => fields
      case other         => Vector("allOf" -> J.Arr(Vector(other)))
    J.Obj(
      ("$schema" -> J.Str("https://json-schema.org/draft/2020-12/schema")) +: body :++
        (if defs.isEmpty then Vector.empty
         else Vector("$defs" -> J.Obj(defs.map(n => key(n.name) -> render(n.shape, ref)))))
    )

  /** `json` written compactly. */
  def write(json: GraphJson): String =
    com.github.plokhotnyuk.jsoniter_scala.core.writeToString(json)(using GraphJson.codec)
