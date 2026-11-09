"""The planner domain: plain dataclasses, no ankka types. These records are what the agents talk
about; nothing here knows an agent exists."""

from __future__ import annotations

from dataclasses import dataclass, field

# Named once so the selector, the workflow and the agents cannot disagree.
WEATHER = "weather"
ACTIVITY = "activity"
BUDGET = "budget"
SPECIALISTS = [WEATHER, ACTIVITY, BUDGET]

NOT_STARTED = "not-started"
SELECTING = "selecting"
CONSULTING = "consulting"
COMPLETED = "completed"


@dataclass(frozen=True)
class Preferences:
    userId: str
    likes: list[str] = field(default_factory=list)
    dislikes: list[str] = field(default_factory=list)
    maxBudget: int = 0

    @property
    def summary(self) -> str:
        if not self.likes and not self.dislikes:
            return "no stated preferences"
        parts = []
        if self.likes:
            parts.append("likes " + ", ".join(self.likes))
        if self.dislikes:
            parts.append("dislikes " + ", ".join(self.dislikes))
        parts.append(f"budget {self.maxBudget}")
        return "; ".join(parts)


@dataclass(frozen=True)
class AgentSelection:
    """What the selector agent decided, and why."""

    specialists: list[str]
    reason: str


@dataclass(frozen=True)
class Contribution:
    """One specialist's contribution."""

    specialist: str
    answer: str


@dataclass(frozen=True)
class PlanState:
    """The state a planning run accumulates."""

    userId: str = ""
    destination: str = ""
    status: str = NOT_STARTED
    selection: AgentSelection | None = None
    contributions: list[Contribution] = field(default_factory=list)
    summary: str | None = None

    def contribution_from(self, specialist: str) -> str | None:
        return next((c.answer for c in self.contributions if c.specialist == specialist), None)


@dataclass(frozen=True)
class Start:
    userId: str
    destination: str
