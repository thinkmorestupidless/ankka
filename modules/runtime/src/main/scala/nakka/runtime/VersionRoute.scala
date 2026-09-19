package nakka.runtime

import nakka.core.BuildInfo
import org.apache.pekko.actor.ExtendedActorSystem
import org.apache.pekko.http.scaladsl.model.{ContentTypes, HttpEntity}
import org.apache.pekko.http.scaladsl.server.Directives.*
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.management.scaladsl.{
  ManagementRouteProvider,
  ManagementRouteProviderSettings
}

/**
 * `GET /nakka/version` on the management port: the runtime version this image actually carries.
 *
 * A descriptor *declares* a runtime version (feature 006) and the platform checks the declaration
 * before anything starts; this is the measurement beside it — what a person compares the
 * declaration against when the two might differ, and what a later feature may check automatically.
 * Registered through Pekko Management's route providers in the Kubernetes overlay, so it exists
 * wherever `/ready` does.
 */
final class VersionRoute(system: ExtendedActorSystem) extends ManagementRouteProvider:
  val _ = system

  def routes(settings: ManagementRouteProviderSettings): Route =
    path("nakka" / "version") {
      get {
        complete(
          HttpEntity(ContentTypes.`application/json`, s"""{"version":"${BuildInfo.version}"}""")
        )
      }
    }
