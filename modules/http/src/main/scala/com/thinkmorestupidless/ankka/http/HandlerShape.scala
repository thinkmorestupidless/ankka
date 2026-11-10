package com.thinkmorestupidless.ankka.http

import scala.annotation.implicitNotFound
import scala.quoted.*

/**
 * A route handler's shape: the function `F` as one function of a tuple of its parameters.
 *
 * It is what lets a route take a handler of any number of parameters. A route is declared with a
 * function of its path parameters, `(cartId: String, productId: String) => ...`, and Scala has one
 * function type per number of parameters, so a declaration over each of them would stop at
 * whichever was written last. An instance is made where the route is declared, for whatever
 * function is there.
 */
@implicitNotFound(
  "a route's handler is a function of its path parameters, each with its type, such as " +
    "(cartId: String) => ...; ${F} is not one"
)
sealed trait HandlerShape[F]:

  /** The handler's parameter types, in order. */
  type Args <: Tuple

  /** What the handler answers. */
  type Out

  def tupled(handler: F): Args => Out

object HandlerShape:

  final class Of[F, A <: Tuple, R](untupled: F => A => R) extends HandlerShape[F]:
    type Args = A
    type Out  = R
    def tupled(handler: F): A => R = untupled(handler)

  transparent inline given derived[F]: HandlerShape[F] = ${ derivedImpl[F] }

  private def derivedImpl[F: Type](using Quotes): Expr[HandlerShape[F]] =
    import quotes.reflect.*
    val function = TypeRepr.of[F].dealias
    val types    = function match
      case AppliedType(_, arguments)
          if function.isFunctionType && !function.isContextFunctionType =>
        arguments
      case _ =>
        // Said by the trait's `implicitNotFound`: a given that aborts is reported as not found.
        report.errorAndAbort(s"${function.show} is not a function")
    val parameters = types.init
    val arguments  = parameters.foldRight(TypeRepr.of[EmptyTuple]) { (head, tail) =>
      TypeRepr.of[*:].appliedTo(List(head, tail))
    }
    (arguments.asType, types.last.asType).runtimeChecked match
      case ('[type a <: Tuple; a], '[r]) =>
        '{
          new Of[F, a, r]((handler: F) =>
            (values: a) =>
              ${
                val passed = parameters.zipWithIndex.map { (parameter, index) =>
                  parameter.asType match
                    case '[t] => '{ values.productElement(${ Expr(index) }).asInstanceOf[t] }.asTerm
                }
                Apply(Select.unique('handler.asTerm, "apply"), passed).asExprOf[r]
              }
          )
        }

/**
 * How a route's path parameters are read: one `FromPath` for each, in the template's order. There
 * is one for a handler of any number of parameters, each of a type a path segment can be read as.
 */
@implicitNotFound(
  "every parameter of this route's handler is a path parameter, of a type with a FromPath " +
    "(String, Int, Long, Boolean, UUID); ${Args} has one that is not"
)
sealed trait PathArguments[Args <: Tuple]:
  private[http] def parsers: List[FromPath[?]]

  def arity: Int = parsers.size

  private[http] def read(values: Vector[String], template: String): Args =
    Tuple.fromArray(PathArguments.parse(parsers, values, template)).asInstanceOf[Args]

object PathArguments:

  given none: PathArguments[EmptyTuple] with
    private[http] val parsers: List[FromPath[?]] = Nil

  given more[H, T <: Tuple](using head: FromPath[H], tail: PathArguments[T]): PathArguments[H *: T]
  with
    private[http] val parsers: List[FromPath[?]] = head :: tail.parsers

  /** Every path value as its parameter's type, or the 400 that names the first that is not. */
  private[http] def parse(
      parsers: List[FromPath[?]],
      values: Vector[String],
      template: String
  ): Array[Object] =
    val parsed = new Array[Object](parsers.size)
    var index  = 0
    parsers.foreach { parser =>
      parser.parse(values(index)) match
        case Right(value) => parsed(index) = value.asInstanceOf[Object]
        case Left(reason) => throw HttpProblem.badRequest(s"$reason in '$template'")
      index += 1
    }
    parsed

/**
 * How the parameters of a handler that takes something after its path parameters are read: a
 * decoded body, a body read as it arrives, or a socket. `Last` is that final parameter's type.
 */
@implicitNotFound(
  "this route's handler takes its path parameters, each of a type with a FromPath (String, Int, " +
    "Long, Boolean, UUID), and then one ${Last}; ${Args} is not that"
)
sealed trait PathArgumentsThen[Args <: Tuple, Last]:
  private[http] def parsers: List[FromPath[?]]

  def arity: Int = parsers.size

  /** Reads the path now, so a value that does not parse is refused before `Last` exists. */
  private[http] def read(values: Vector[String], template: String): Last => Args =
    val parsed = PathArguments.parse(parsers, values, template)
    last => Tuple.fromArray(parsed :+ last.asInstanceOf[Object]).asInstanceOf[Args]

object PathArgumentsThen:

  given last[L]: PathArgumentsThen[L *: EmptyTuple, L] with
    private[http] val parsers: List[FromPath[?]] = Nil

  given more[H, T <: Tuple, L](using
      head: FromPath[H],
      tail: PathArgumentsThen[T, L]
  ): PathArgumentsThen[H *: T, L] with
    private[http] val parsers: List[FromPath[?]] = head :: tail.parsers
