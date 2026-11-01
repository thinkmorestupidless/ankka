Feature: Specifying a change that crosses services
  A change that changes several services of a project is one spec, in the project's repository,
  whose plan names each service's part and the contracts it changes. The contract is changed before
  either side depends on it, and an incompatible change is a new contract, so each service can still
  be released on its own while the other is on its older version.

  Background:
    Given a project "shop" whose service "orders" publishes to a declared topic "orders-placed" carrying the contract "order.v1", which "billing" reads

  Scenario: a change that changes several services of a project is one spec whose plan names each service's part and the contracts it changes
    When the developer designs a change in which "orders" publishes a discount and "billing" invoices it
    Then the change has one spec, in the repository of "shop"
    And its plan names what changes in "orders", what changes in "billing", and the contract "order.v1"

  Scenario: the work list of a change that changes a contract changes the contract before either service depends on it
    Given the design of a change that adds a discount to "order.v1"
    When the developer reads its work list
    Then the work item that changes the contract comes before every work item of "orders" or "billing" that depends on it

  Scenario: an incompatible change to a contract is a new contract, and each side reads both until neither publishes the old one
    Given the design of a change that removes the total from what "orders-placed" carries
    When the developer reads its plan
    Then the plan declares a new contract "order.v2" rather than changing "order.v1"
    And "billing" reads both "order.v1" and "order.v2" until "orders" no longer publishes "order.v1"

  Scenario: a service moved to a repository of its own keeps a spec there that names the project's spec it implements
    Given "billing" has been moved to a repository of its own
    When the developer designs a change that changes "orders" and "billing"
    Then the spec of the change, the context map and the contracts are in the repository of "shop"
    And the repository of "billing" has a spec that names the spec of the change in "shop"
