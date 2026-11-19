package com.thinkmorestupidless.ankka.javasdk.testkit

import com.thinkmorestupidless.ankka.core.graph.GraphJson
import com.thinkmorestupidless.ankka.core.graph.GraphJson as J

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}

/**
 * A service's OpenAPI document as `ankka openapi` writes it, and the comparisons the suites make.
 */
object WrittenDocument:

  /** `main` of `mainClass` with `ankka.document` set, in a fresh JVM with an empty environment. */
  def of(mainClass: String): Either[String, Array[Byte]] =
    val dir = Files.createTempDirectory("openapi")
    val out = dir.resolve("openapi.json")
    try
      val java    = Path.of(sys.props("java.home"), "bin", "java").toString
      val builder = ProcessBuilder(
        java,
        "-cp",
        sys.props("java.class.path"),
        "-Dankka.document=openapi",
        s"-Dankka.document.out=$out",
        "-Dankka.service.name=shopping-cart",
        mainClass
      ).redirectErrorStream(true)
      builder.environment().clear()
      sys.env.get("PATH").foreach(builder.environment().put("PATH", _))
      val process = builder.start()
      val said    = String(process.getInputStream.readAllBytes(), UTF_8)
      if process.waitFor() != 0 then Left(said) else Right(Files.readAllBytes(out))
    finally
      Files.deleteIfExists(out)
      Files.deleteIfExists(dir): Unit

  def parse(bytes: Array[Byte]): GraphJson =
    GraphJson.parse(bytes).fold(e => throw IllegalArgumentException(e), identity)

  def at(json: GraphJson, path: String*): Option[GraphJson] =
    path.foldLeft(Option(json))((j, key) => j.flatMap(_.field(key)))

  /** `json` with every `$ref` replaced by the schema it names, so two names for one shape agree. */
  def resolved(json: GraphJson, document: GraphJson): GraphJson = json match
    case J.Obj(Vector(("$ref", J.Str(ref)))) =>
      val name = ref.stripPrefix("#/components/schemas/")
      resolved(at(document, "components", "schemas", name).getOrElse(json), document)
    case J.Obj(fields) => J.Obj(fields.map((k, v) => k -> resolved(v, document)))
    case J.Arr(items)  => J.Arr(items.map(resolved(_, document)))
    case other         => other

  /**
   * Where `other`'s operation differs from `expected`'s in what the language feature compares: the
   * path parameters, the body's shape and each answer's status and shape. The media type is not
   * compared: a top-level number is JSON in Scala and text in Java, and each document says so.
   */
  def differences(
      route: String,
      expected: GraphJson,
      expectedDoc: GraphJson,
      other: GraphJson,
      otherDoc: GraphJson
  ): Vector[String] =
    def shapes(content: Option[GraphJson], doc: GraphJson): Vector[GraphJson] =
      content.toVector.flatMap {
        case J.Obj(media) => media.flatMap((_, m) => m.field("schema").map(resolved(_, doc)))
        case _            => Vector.empty
      }
    def params(op: GraphJson, doc: GraphJson): GraphJson =
      op.field("parameters").map(resolved(_, doc)).getOrElse(J.Arr(Vector.empty))
    def answers(op: GraphJson, doc: GraphJson): Map[String, Vector[GraphJson]] =
      op.field("responses") match
        case Some(J.Obj(rs)) =>
          rs.map((status, r) => status -> shapes(r.field("content"), doc)).toMap
        case _ => Map.empty
    val found = Vector.newBuilder[String]
    if params(expected, expectedDoc) != params(other, otherDoc) then
      found += s"$route: the parameters differ"
    if shapes(at(expected, "requestBody", "content"), expectedDoc) !=
        shapes(at(other, "requestBody", "content"), otherDoc)
    then found += s"$route: the body's shape differs"
    val (a, b) = (answers(expected, expectedDoc), answers(other, otherDoc))
    if a.keySet != b.keySet then
      found += s"$route: answers ${a.keySet.toVector.sorted} against ${b.keySet.toVector.sorted}"
    a.keySet.intersect(b.keySet).toVector.sorted.foreach { status =>
      if a(status) != b(status) then found += s"$route: the $status answer's shape differs"
    }
    found.result()

  /** Every operation of `document` under `prefix`, as `METHOD path` with its operation. */
  def operations(document: GraphJson, prefix: String): Map[String, GraphJson] =
    at(document, "paths") match
      case Some(J.Obj(paths)) =>
        paths
          .collect {
            case (path, J.Obj(ops)) if path.startsWith(prefix) =>
              ops.map((m, op) => s"${m.toUpperCase} $path" -> op)
          }
          .flatten
          .toMap
      case _ => Map.empty
