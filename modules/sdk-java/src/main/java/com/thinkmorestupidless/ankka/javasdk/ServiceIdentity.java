package com.thinkmorestupidless.ankka.javasdk;

import java.util.Optional;

/**
 * Who the running service is: its project and its name, as the platform gives them, or as {@code
 * ANKKA_SERVICE_NAME} names it on a developer's machine, where there is no project. A service that
 * was given neither is unnamed.
 */
public record ServiceIdentity(Optional<String> project, Optional<String> service) {

  @Override
  public String toString() {
    if (project.isPresent() && service.isPresent()) return project.get() + "/" + service.get();
    return service.map(s -> "local/" + s).orElse("unnamed");
  }
}
