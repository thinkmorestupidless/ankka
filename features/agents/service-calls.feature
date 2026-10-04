Feature: A tool calling another service as its own service
  A tool of an agent calls another service as the agent's service: the service called is told
  which service called it, so its ACL can admit the agent's service and nothing else. A refusal
  the called service makes reaches the tool as it was made, and the model is told of it as the
  tool's error.

  Background:
    Given a service "support" deployed in the project "shop" with an agent "helper"
    And a tool "read_balance" of "helper" that calls the service "wallet"

  Scenario: a tool's call is admitted by an ACL that names the agent's service
    Given a service "wallet" deployed in the project "shop" whose ACL admits only "support"
    When the model calls "read_balance"
    Then "wallet" is told that the call came from the service "support" in the project "shop"
    And the model is told the answer of "wallet" as the result of the tool call
    And a call to "wallet" that does not come from "support" is refused

  Scenario: a refusal by the called service reaches the model as the tool's error
    Given a service "wallet" deployed in the project "shop" whose ACL does not admit "support"
    When the model calls "read_balance"
    Then the tool is given the refusal that "wallet" made
    And the model is told of the refusal as an error of the tool call

  Scenario: a tool's call to another service is in the trace inside the tool call
    Given a service "wallet" deployed in the project "shop" whose ACL admits only "support"
    And an endpoint that calls "helper"
    When "support" handles 1 request that "helper" answers after the model calls "read_balance"
    Then the trace of the request shows the tool call to "read_balance" under "helper"
    And the trace shows the call to "wallet" inside the tool call
