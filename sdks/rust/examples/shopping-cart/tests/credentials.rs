//! The secret store with nothing running: the native host a unit test runs against keeps what a
//! handler puts, in memory, under the runtime's rules.

use ankka::prelude::*;
use ankka::testkit::{ConsumerTestKit, EndpointTestKit, StepNext, WorkflowTestKit};
use shopping_cart::credentials::{
    Charge, ChargeWorkflow, KeepSecret, ProviderCredentialsEndpoint, StockEvent,
    WarehouseCredentialKeeper,
};

/// The store a test reads: the same one every handler in this thread is given.
fn store() -> ankka::Secrets {
    Context::new("test", "", 0, Default::default())
        .with_secrets()
        .secrets()
        .expect("a context given the store has one")
}

// docs:start secrets-unit-test
#[test]
fn the_keeper_keeps_a_warehouses_credential_the_first_time_it_sees_it() {
    let kit = ConsumerTestKit::<WarehouseCredentialKeeper>::new();
    kit.on_message(
        "sku-1",
        StockEvent {
            sku: "sku-1".into(),
            delta: 1,
            warehouse: "w9".into(),
        },
    );
    assert_eq!(
        store().get("warehouse/w9"),
        Ok(Some("token-w9".to_string()))
    );
}
// docs:end secrets-unit-test

#[test]
fn the_endpoint_keeps_reads_and_removes_a_credential() {
    let kit = EndpointTestKit::<ProviderCredentialsEndpoint>::new();
    let keep = KeepSecret {
        name: "provider/stripe".into(),
        value: "sk_test_1".into(),
    };
    assert_eq!(kit.post("/credentials/", &keep).status, 204);
    let read = kit
        .request("GET", "/credentials/")
        .query("name", "provider/stripe")
        .send();
    assert_eq!((read.status, read.text()), (200, "sk_test_1".to_string()));
    let removed = kit
        .request("DELETE", "/credentials/")
        .query("name", "provider/stripe")
        .send();
    assert_eq!(removed.status, 204);
    let gone = kit
        .request("GET", "/credentials/")
        .query("name", "provider/stripe")
        .send();
    assert_eq!(gone.status, 404);
    // The name is required.
    assert_eq!(kit.get("/credentials/").status, 400);
}

#[test]
fn the_charge_step_reads_the_credential_and_records_only_that_it_charged() {
    store().put("provider/adyen", "adyen-secret").unwrap();
    let mut kit = WorkflowTestKit::<ChargeWorkflow>::new("ch-1");
    let charge = Charge {
        provider: "adyen".into(),
        amount: 12,
    };
    assert_eq!(kit.command("start", charge).reply::<Done>(), Ok(Done));
    assert_eq!(kit.run_step(), StepNext::End);
    let state = kit.state();
    assert_eq!(
        (state.status.as_str(), state.credential_length),
        ("charged", 12)
    );
    assert!(
        !ankka::serde_json::to_string(&state)
            .unwrap()
            .contains("adyen-secret")
    );

    // No credential kept for the provider: the step fails, and nothing is charged.
    let mut unknown = WorkflowTestKit::<ChargeWorkflow>::new("ch-2");
    unknown.command(
        "start",
        Charge {
            provider: "nobody".into(),
            amount: 1,
        },
    );
    match unknown.run_step() {
        StepNext::Fail(e) => assert!(e.message.contains("no credential for nobody")),
        other => panic!("the step charged without a credential: {other:?}"),
    }
}
