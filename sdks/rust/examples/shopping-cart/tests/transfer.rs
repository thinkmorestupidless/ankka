//! The transfer, the quote and the onboarding with nothing running.

use ankka::prelude::*;
use ankka::testkit::{EndpointTestKit, KeyValueEntityTestKit, StepNext, WorkflowTestKit};
use shopping_cart::transfer::{
    QuoteEndpoint, QuoteRequest, QuoteWorkflow, Transfer, TransferApi, TransferWorkflow,
    WalletEntity,
};

fn transfer(amount: i32) -> Transfer {
    Transfer {
        from: "w-1".into(),
        to: "w-2".into(),
        amount,
    }
}

#[test]
fn a_wallet_refuses_an_overdraft_and_a_frozen_one_refuses_a_deposit() {
    let mut kit = KeyValueEntityTestKit::<WalletEntity>::new("w-1");
    assert_eq!(kit.command("deposit", 10).answer.reply::<Done>(), Ok(Done));
    assert_eq!(
        kit.command("withdraw", 11).answer.error().map(|e| e.code),
        Some(ErrorCode::Conflict)
    );
    kit.command("freeze", ());
    assert_eq!(
        kit.command("deposit", 1).answer.error().map(|e| e.code),
        Some(ErrorCode::Conflict)
    );
    assert_eq!(kit.command("balance", ()).answer.reply::<i32>(), Ok(10));
}

// docs:start transfer-test
#[test]
fn a_transfer_starts_at_the_withdrawal_and_refuses_what_it_should() {
    let mut kit = WorkflowTestKit::<TransferWorkflow>::new("t-1")
        .with_service(shopping_cart::transfer::build());
    assert_eq!(
        kit.command("start", transfer(0)).error().map(|e| e.code),
        Some(ErrorCode::BadRequest)
    );
    assert_eq!(kit.command("start", transfer(5)).reply::<Done>(), Ok(Done));
    assert_eq!(kit.state().status, "started");
    assert_eq!(
        kit.command("start", transfer(5)).error().map(|e| e.code),
        Some(ErrorCode::Conflict)
    );
    // The wallets answer from memory, and the one the transfer draws on holds nothing: the
    // withdrawal is refused and the step fails with it. The runtime then applies the settings'
    // recovery.
    match kit.run_step() {
        StepNext::Fail(e) => assert_eq!(e.code, ErrorCode::Conflict),
        other => panic!("the step ran without its wallet: {other:?}"),
    }
}
// docs:end transfer-test

#[test]
fn a_quote_runs_its_two_steps_to_the_end() {
    let mut kit = WorkflowTestKit::<QuoteWorkflow>::new("q-1");
    assert_eq!(
        kit.command("start", QuoteRequest { amount: 200 })
            .reply::<Done>(),
        Ok(Done)
    );
    assert_eq!(kit.run_step(), StepNext::TransitionTo("offer".into()));
    assert_eq!(kit.run_to_end(), StepNext::End);
    assert_eq!(kit.state().total, Some(206));
}

#[test]
fn a_start_the_kit_refuses_begins_no_wait() {
    // The unit kit answers event sourced and key value entities only: the start is refused, the route answers
    // that refusal, and no wait begins.
    let kit = EndpointTestKit::<QuoteEndpoint>::with_service(shopping_cart::transfer::build());
    let answered = kit.post("/quotes/q-1", &QuoteRequest { amount: 200 });
    assert_eq!(answered.status, 503, "{}", answered.text());
}

#[test]
#[should_panic(expected = "does not answer AwaitEnd")]
fn a_wait_for_a_workflow_needs_the_runtime() {
    // A wait is the runtime's: the unit kit stops at it, and the integration kit proves it.
    let kit = EndpointTestKit::<QuoteEndpoint>::with_service(shopping_cart::transfer::build());
    kit.get("/quotes/q-1/wait");
}

#[test]
fn a_transfers_lifecycle_answers_the_engines_view() {
    let failed = Lifecycle {
        failure: Some("the bank was shut".into()),
        ..Lifecycle::of(WorkflowStatus::Failed)
    };
    let mut kit =
        EndpointTestKit::<TransferApi>::new().with_lifecycle(TransferWorkflow, "t1", failed);
    assert_eq!(
        kit.get("/transfers/t1/lifecycle").text(),
        "Failed: the bank was shut"
    );
    // A transfer the kit runs is read from where it got to.
    let mut t2 = kit.workflow::<TransferWorkflow>("t2");
    assert_eq!(kit.get("/transfers/t2/lifecycle").text(), "NotStarted");
    t2.command("start", transfer(5));
    assert_eq!(kit.get("/transfers/t2/lifecycle").text(), "Running");
}
