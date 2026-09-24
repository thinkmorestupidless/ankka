"""Effects for timed actions: done, or failed (and retried by the sweeper)."""

from __future__ import annotations

from dataclasses import dataclass

from ankka.effects.common import Error, ErrorCode


@dataclass(frozen=True)
class Done:
    pass


@dataclass(frozen=True)
class Failed:
    error: Error


TimedActionEffect = Done | Failed


class TimedActionEffects:
    def done(self) -> Done:
        return Done()

    def fail(self, message: str, code: ErrorCode = ErrorCode.INTERNAL) -> Failed:
        return Failed(Error(message, code))
