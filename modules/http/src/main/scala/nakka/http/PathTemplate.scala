package nakka.http

/**
 * A parsed route path such as `/carts/{cartId}/items/{productId}`.
 *
 * Placeholder *count* is checked against the handler's arity when the endpoint is
 * constructed, so a mismatch fails at startup alongside the other service-definition
 * checks rather than on the first matching request.
 */
final case class PathTemplate(segments: Vector[PathSegment]):

  val parameterNames: Vector[String] = segments.collect { case PathSegment.Param(name) => name }

  def arity: Int = parameterNames.size

  /** Extracts parameter values in declaration order, if this path matches. */
  def matches(path: Vector[String]): Option[Vector[String]] =
    if path.sizeIs != segments.size then None
    else
      val extracted = Vector.newBuilder[String]
      var matched   = true
      segments.zip(path).foreach {
        case (PathSegment.Literal(expected), actual) => if expected != actual then matched = false
        case (PathSegment.Param(_), actual)          => extracted += actual
      }
      if matched then Some(extracted.result()) else None

  def render: String = segments
    .map {
      case PathSegment.Literal(value) => value
      case PathSegment.Param(name)    => s"{$name}"
    }
    .mkString("/", "/", "")

enum PathSegment:
  case Literal(value: String)
  case Param(name: String)

object PathTemplate:

  private val Placeholder = "\\{([A-Za-z_][A-Za-z0-9_]*)\\}".r

  /** Parses a template, rejecting duplicate parameter names and malformed braces. */
  def parse(raw: String): PathTemplate =
    val segments = raw.split('/').iterator.filter(_.nonEmpty).map { segment =>
      segment match
        case Placeholder(name) => PathSegment.Param(name)
        case literal if literal.contains('{') || literal.contains('}') =>
          throw IllegalArgumentException(
            s"malformed path segment '$literal' in '$raw': a parameter must be the whole " +
              "segment, as in '/items/{productId}'"
          )
        case literal => PathSegment.Literal(literal)
    }.toVector

    val duplicates =
      segments.collect { case PathSegment.Param(name) => name }.groupBy(identity).collect {
        case (name, occurrences) if occurrences.sizeIs > 1 => name
      }
    if duplicates.nonEmpty then
      throw IllegalArgumentException(
        s"duplicate path parameter(s) ${duplicates.mkString(", ")} in '$raw'"
      )

    PathTemplate(segments)
