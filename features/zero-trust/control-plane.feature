Feature: The control plane on the terms it gives every service
  The control plane is deployed by the installation rather than by the operator, and it is held to
  what every deployed service is held to: its instances join their service cluster over proven
  connections, the gateway reaches it as the gateway, and it checks the certificate of the issuer
  it fetches keys from.

  Background:
    Given a deployed control plane

  Scenario: the control plane's instances join their service cluster over proven connections nothing else can open
    When the instances of the control plane start
    Then they form one service cluster over connections proven by the certificate of the control plane
    And a workload that is not an instance of the control plane cannot connect to where its service cluster talks

  Scenario: a member reaches the control plane through the gateway, which it reads as the calling workload
    When a member lists the services of the project "shop"
    Then the member is shown the services of the project "shop"
    And the control plane read the calling workload as the gateway, over a proven connection

  Scenario: the control plane checks the issuer's certificate when it fetches the issuer's keys
    When the control plane fetches the keys of the installation's issuer
    Then the connection to the issuer is not in the clear
    And the control plane checked the issuer's certificate
