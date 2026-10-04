Feature: A broker the descriptor names
  A descriptor may name a broker of its own by giving a broker variable. The service then uses that
  broker and the platform makes nothing for it on the installation's: no topic and no credential.
  A broker variable is given to both programs of a service hosted as a process, because the
  platform's program is what connects to the broker and the process registers what needs one only
  where there is one.

  Scenario: a service hosted as a process is ready with the broker its descriptor names
    Given a broker in the cluster that is not the installation's
    And a descriptor for a service "cart" written in "Python" with a consumer that publishes to the topic "cart-changes"
    And the descriptor gives the variable "ANKKA_KAFKA_BOOTSTRAP_SERVERS", naming that broker
    When a member applies the descriptor
    Then "cart" is ready

  Scenario: a consumer of a service hosted as a process publishes to the broker its descriptor names
    Given a deployed service "cart" written in "Python" whose descriptor names a broker in the cluster
    And a consumer "cart-changes" of "cart" that publishes to the topic "cart-changes"
    When "cart-changes" handles an event
    Then what "cart-changes" published is read from the topic "cart-changes" on that broker

  Scenario: a broker variable is given to both programs of a service hosted as a process
    Given a descriptor for a service "cart" hosted as a process that gives the variable "ANKKA_KAFKA_BOOTSTRAP_SERVERS"
    When a member applies the descriptor
    Then the platform's program of "cart" is given the variable "ANKKA_KAFKA_BOOTSTRAP_SERVERS"
    And the process of "cart" is given the variable "ANKKA_KAFKA_BOOTSTRAP_SERVERS"

  Scenario: a service whose descriptor names a broker is given nothing on the installation's
    Given an installation with a broker
    And a descriptor for a service "wallet" that gives the variable "ANKKA_KAFKA_BOOTSTRAP_SERVERS" and declares no topic
    When a member applies the descriptor
    Then the status of "wallet" says that its broker is "Supplied"
    And the installation's broker has no credential for "wallet"
