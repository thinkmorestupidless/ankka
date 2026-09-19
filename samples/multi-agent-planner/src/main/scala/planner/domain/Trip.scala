package planner.domain

/**
 * The planner domain.
 *
 * Plain Scala, no ankka types — the same discipline as the shopping cart. These records are what
 * the agents talk about; nothing here knows an agent exists.
 */
final case class Preferences(
    userId: String,
    likes: List[String],
    dislikes: List[String],
    maxBudget: Int
):
  def isEmpty: Boolean = likes.isEmpty && dislikes.isEmpty

  def summary: String =
    if isEmpty then "no stated preferences"
    else
      val liked    = if likes.isEmpty then "" else s"likes ${likes.mkString(", ")}"
      val disliked = if dislikes.isEmpty then "" else s"dislikes ${dislikes.mkString(", ")}"
      List(liked, disliked, s"budget $maxBudget").filter(_.nonEmpty).mkString("; ")

object Preferences:
  def empty(userId: String): Preferences = Preferences(userId, Nil, Nil, 0)

/** What the selector agent decided, and why. */
final case class AgentSelection(specialists: List[String], reason: String)

/** One specialist's contribution. */
final case class Contribution(specialist: String, answer: String)

/** The state a planning run accumulates. */
final case class PlanState(
    userId: String,
    destination: String,
    status: String,
    selection: Option[AgentSelection],
    contributions: List[Contribution],
    summary: Option[String]
):
  def contributionFrom(specialist: String): Option[String] =
    contributions.find(_.specialist == specialist).map(_.answer)

object PlanState:
  val NotStarted = "not-started"
  val Selecting  = "selecting"
  val Consulting = "consulting"
  val Completed  = "completed"

  def empty: PlanState = PlanState("", "", NotStarted, None, Nil, None)
