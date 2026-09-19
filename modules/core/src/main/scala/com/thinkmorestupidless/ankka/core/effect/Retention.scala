package com.thinkmorestupidless.ankka.core.effect

import scala.concurrent.duration.FiniteDuration

/** What should become of an entity's stored state once this command completes. */
enum Retention:
  /** Mark the entity deleted now. Its id must not be reused with an expectation of state. */
  case DeleteNow

  /** Delete automatically after `duration` elapses with no further update. */
  case ExpireAfter(duration: FiniteDuration)
