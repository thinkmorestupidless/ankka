Feature: A deployed service that serves gRPC
  A service whose descriptor declares gRPC is given a gRPC address that other services of the
  installation reach. Who called is read from the certificate, never from what the call says, and
  a service that declares no gRPC is deployed as it always was.

  Scenario: a service that declares gRPC has a gRPC address other services reach
    Given a service "cart" whose descriptor declares gRPC
    And a deployed service "checkout"
    When a member deploys the service "cart"
    Then the service "cart" has a gRPC address
    And a call from the service "checkout" to the gRPC address of "cart" ends with the status "ok"

  Scenario: a service that does not declare gRPC is deployed as it was before
    Given a deployed service "orders" whose descriptor does not declare gRPC
    When the platform is upgraded to a version that serves gRPC
    Then the service "orders" has no gRPC address
    And no instance of the service "orders" is restarted

  Scenario: a service may serve gRPC and no HTTP
    Given a service "cart" whose descriptor declares gRPC and declares no HTTP
    When a member deploys the service "cart"
    Then the service "cart" has a gRPC address
    And every instance of the service "cart" is ready

  Scenario: a deployed gRPC endpoint reads its calling workload from the certificate
    Given a deployed service "cart" that serves gRPC
    And a deployed service "checkout" in the project "shop"
    When the service "checkout" calls the method "GetCart" of the service "cart"
    Then the handler for the method "GetCart" reads the calling workload as the service "checkout" of the project "shop"

  Scenario: a call that says it is from another service is not believed
    Given a deployed service "cart" that serves gRPC
    And a deployed service "checkout"
    When the service "checkout" calls the method "GetCart" of the service "cart" with metadata that says the call is from the service "billing"
    Then the handler for the method "GetCart" reads the calling workload as the service "checkout"

  Scenario: a service that opts into reflection answers another service at its gRPC address
    Given a deployed service "cart" that serves gRPC
    And the service "cart" opts into reflection with an ACL that admits only the service "checkout"
    And a deployed service "checkout"
    When the service "checkout" asks the service "cart" for reflection
    Then the service "checkout" is told the service definition "CartService"

  Scenario: a service that reflection's ACL does not admit is refused at the gRPC address
    Given a deployed service "cart" that serves gRPC
    And the service "cart" opts into reflection with an ACL that admits only the gateway
    And a deployed service "checkout"
    When the service "checkout" asks the service "cart" for reflection
    Then the call ends with the status "permission denied"
    And the service "checkout" is told nothing of what the service "cart" serves

  Scenario: a workload that is not of the installation cannot connect to a gRPC address
    Given a deployed service "cart" that serves gRPC
    And a workload in the cluster that is not of the installation
    When the workload connects to the gRPC address of "cart"
    Then the workload is refused
    And no handler runs

  Scenario: an instance that cannot yet answer a gRPC call is not ready
    Given a deployed service "cart" that serves gRPC
    When an instance of the service "cart" has started and cannot yet answer a gRPC call
    Then the instance is not ready
    And no call is sent to the instance

  Scenario: a service that declares gRPC and serves none is reported as failed, with the reason
    Given a service "cart" whose descriptor declares gRPC
    And the service "cart" has no gRPC endpoint
    When a member deploys the service "cart"
    Then the service "cart" is reported as failed
    And the member is shown that the service "cart" declares gRPC and serves none

  Scenario: a descriptor that declares gRPC for a service that is not embedded is refused
    Given a descriptor for a service hosted as a process that declares gRPC
    When a member applies the descriptor
    Then the member is refused
    And the member is shown that only an embedded service serves gRPC

  Scenario: a descriptor whose gRPC port is its HTTP port is refused
    Given a descriptor whose gRPC port and HTTP port are both "9000"
    When a member applies the descriptor
    Then the member is refused
    And the member is shown that the gRPC port and the HTTP port must differ

  Scenario: a descriptor that sets the platform's gRPC port variable itself is refused
    Given a descriptor that sets the variable the platform tells a service its gRPC port by
    When a member applies the descriptor
    Then the member is refused
    And the member is shown that the descriptor declares the gRPC port instead
