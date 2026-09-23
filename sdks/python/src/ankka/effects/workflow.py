"""Effects for workflows: a command may change state and start a step; a step changes state and
says what happens next."""

from __future__ import annotations

from collections.abc import Callable
from dataclasses import dataclass
from datetime import timedelta
from typing import Any, Generic, TypeVar

from ankka.context import Metadata
from ankka.effects.common import Error, ErrorCode, Fail, NoReply, Outcome, Reply

S = TypeVar("S")
R = TypeVar("R")


@dataclass(frozen=True)
class StepRef:
    step: str
    input: Any = None


@dataclass(frozen=True)
class TransitionTo:
    ref: StepRef


@dataclass(frozen=True)
class Pause:
    after: timedelta | None = None
    on_timeout: StepRef | None = None


@dataclass(frozen=True)
class End:
    pass


@dataclass(frozen=True)
class StepFail:
    error: Error


StepOutcome = TransitionTo | Pause | End | StepFail


@dataclass(frozen=True)
class WorkflowEffect(Generic[S, R]):
    new_state: S | None
    transition: StepRef | None
    outcome: Outcome


@dataclass(frozen=True)
class WorkflowReadOnlyEffect(WorkflowEffect[S, R]):
    new_state: S | None = None
    transition: StepRef | None = None
    outcome: Outcome = NoReply()


@dataclass(frozen=True)
class WorkflowStepEffect(Generic[S]):
    new_state: S | None
    next: StepOutcome


@dataclass(frozen=True)
class WorkflowUpdateBuilder(Generic[S]):
    new_state: S | None

    def then_transition_to(self, step: str, input: Any = None) -> WorkflowTransitionBuilder[S]:
        return WorkflowTransitionBuilder(self.new_state, StepRef(step, input))

    def then_reply(self, compute: Callable[[S], R], metadata: Metadata | None = None) -> WorkflowEffect[S, R]:
        return WorkflowEffect(self.new_state, None, Reply(compute, metadata or Metadata()))

    def then_reply_state(self) -> WorkflowEffect[S, S]:
        return WorkflowEffect(self.new_state, None, Reply(lambda s: s))

    def then_no_reply(self) -> WorkflowEffect[S, Any]:
        return WorkflowEffect(self.new_state, None, NoReply())


@dataclass(frozen=True)
class WorkflowTransitionBuilder(Generic[S]):
    new_state: S | None
    transition: StepRef

    def then_reply(self, compute: Callable[[S], R], metadata: Metadata | None = None) -> WorkflowEffect[S, R]:
        return WorkflowEffect(self.new_state, self.transition, Reply(compute, metadata or Metadata()))

    def then_reply_state(self) -> WorkflowEffect[S, S]:
        return WorkflowEffect(self.new_state, self.transition, Reply(lambda s: s))

    def then_no_reply(self) -> WorkflowEffect[S, Any]:
        return WorkflowEffect(self.new_state, self.transition, NoReply())


class WorkflowEffects(Generic[S]):
    """Inside a command handler."""

    def update_state(self, state: S) -> WorkflowUpdateBuilder[S]:
        return WorkflowUpdateBuilder(state)

    def transition_to(self, step: str, input: Any = None) -> WorkflowTransitionBuilder[S]:
        return WorkflowTransitionBuilder(None, StepRef(step, input))

    def reply(self, value: R, metadata: Metadata | None = None) -> WorkflowReadOnlyEffect[S, R]:
        return WorkflowReadOnlyEffect(outcome=Reply(lambda _: value, metadata or Metadata()))

    def error(self, message: str, code: ErrorCode = ErrorCode.BAD_REQUEST) -> WorkflowReadOnlyEffect[S, Any]:
        return WorkflowReadOnlyEffect(outcome=Fail(Error(message, code)))


@dataclass(frozen=True)
class StepUpdateBuilder(Generic[S]):
    new_state: S | None

    def then_transition_to(self, step: str, input: Any = None) -> WorkflowStepEffect[S]:
        return WorkflowStepEffect(self.new_state, TransitionTo(StepRef(step, input)))

    def then_pause(self, after: timedelta | None = None, on_timeout: StepRef | None = None) -> WorkflowStepEffect[S]:
        return WorkflowStepEffect(self.new_state, Pause(after, on_timeout))

    def then_end(self) -> WorkflowStepEffect[S]:
        return WorkflowStepEffect(self.new_state, End())

    def then_fail(self, message: str, code: ErrorCode = ErrorCode.INTERNAL) -> WorkflowStepEffect[S]:
        return WorkflowStepEffect(self.new_state, StepFail(Error(message, code)))


class StepEffects(Generic[S]):
    """Inside a step."""

    def update_state(self, state: S) -> StepUpdateBuilder[S]:
        return StepUpdateBuilder(state)

    def transition_to(self, step: str, input: Any = None) -> WorkflowStepEffect[S]:
        return WorkflowStepEffect(None, TransitionTo(StepRef(step, input)))

    def pause(self, after: timedelta | None = None, on_timeout: StepRef | None = None) -> WorkflowStepEffect[S]:
        return WorkflowStepEffect(None, Pause(after, on_timeout))

    def end(self) -> WorkflowStepEffect[S]:
        return WorkflowStepEffect(None, End())

    def fail(self, message: str, code: ErrorCode = ErrorCode.INTERNAL) -> WorkflowStepEffect[S]:
        return WorkflowStepEffect(None, StepFail(Error(message, code)))
