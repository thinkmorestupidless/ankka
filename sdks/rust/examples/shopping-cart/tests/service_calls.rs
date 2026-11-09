//! Calls to another service with nothing running: a scripted service answers in place of the
//! runtime, and records what it was asked.

use ankka::testkit::{EndpointTestKit, ScriptedServices};
use shopping_cart::service_calls::NeighbourEndpoint;

// docs:start call-another-service-test
#[test]
fn the_route_answers_what_the_other_service_answered() {
    let services = ScriptedServices::new();
    services.answer("orders", |request| match request.path.as_str() {
        "/callers/whoami" => ScriptedServices::text("service:local/shopping-cart"),
        _ => ScriptedServices::status(404, "text/plain", "no such route"),
    });
    services.unresolvable("nobody");
    let kit = EndpointTestKit::<NeighbourEndpoint>::new();
    services.run(|| {
        let answered = kit.get("/neighbour/call/orders");
        assert_eq!(
            (answered.status, answered.text()),
            (200, "service:local/shopping-cart".to_string())
        );
        // A service the project does not have is a 503; the attempt is recorded, nothing sent.
        assert_eq!(kit.get("/neighbour/call/nobody").status, 503);
    });
    let asked = services.requests();
    assert_eq!(asked.len(), 2);
    assert_eq!(asked[0].path, "/callers/whoami");
    assert_eq!(asked[1].service, "nobody");
}
// docs:end call-another-service-test
