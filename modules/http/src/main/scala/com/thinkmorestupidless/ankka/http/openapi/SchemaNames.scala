package com.thinkmorestupidless.ankka.http.openapi

import com.thinkmorestupidless.ankka.core.shape.TypeName

/**
 * The name each type's schema has in a document (feature 067, research R3): its simple name, and
 * for two distinct types of one simple name the shortest trailing part of their package that tells
 * them apart (`players.Profile`, `reporting.Profile`). Decided over every type the document holds,
 * in an order of their full names, so the names are the same whatever order the routes met them in.
 */
private[ankka] object SchemaNames:

  /** The platform's refusal schema; no service type may take its name. */
  val Problem = "AnkkaProblem"

  /** Each type's document name, or the problems that keep the document from being built. */
  def assign(types: Iterable[TypeName]): Either[Vector[String], Map[TypeName, String]] =
    val distinct = types.toVector.distinct.sortBy(_.full)
    val named    = distinct.groupBy(_.simple).toVector.sortBy(_._1).flatMap { (_, group) =>
      if group.sizeIs == 1 then group.map(t => t -> t.simple)
      else
        val depth = (1 to group.map(_.qualifiers.size).max).find { k =>
          group.map(_.qualifiers.takeRight(k)).distinct.sizeIs == group.size
        }
        group.map { t =>
          val qualifiers = depth.fold(t.qualifiers)(t.qualifiers.takeRight)
          t -> (qualifiers :+ t.simple).mkString(".")
        }
    }
    val problems = named.collect { case (t, Problem) =>
      s"the type ${t.full} is named $Problem, the platform's own refusal schema: rename it"
    }
    if problems.nonEmpty then Left(problems) else Right(named.toMap)
