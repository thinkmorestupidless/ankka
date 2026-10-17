Feature: The documentation of installation models
  A developer learns from the documentation what an installation model is, how a service names
  one, what the platform refuses and why, which models are known to work, and that nothing of a
  call leaves the installation. An administrator learns how to add the model server locally and on
  a cloud, and what it does not do.

  Scenario: the documentation describes installation models and how a service names one
    Given the published documentation
    When a reader reads about installation models
    Then the documentation describes the model server and the models it serves
    And the documentation describes naming an installation model with the preset "installation" and a model, in each language
    And the documentation says what the platform refuses and why
    And the documentation names models known to call tools on a CPU and on a GPU
    And the documentation says that nothing of a call to an installation model leaves the installation

  Scenario: the install guides describe adding the model server locally and on a cloud
    Given the published documentation
    When a reader reads about installing the platform
    Then the documentation describes adding the model server to a local platform, to an installation on GKE and to one on any cloud
    And the documentation describes each setting of the model server and its default
    And the documentation says that accelerator machines are the installation's to provide

  Scenario: the documentation's limitations say what the model server does not do
    Given the published documentation
    When a reader looks up installation models in the limitations of the documentation
    Then the documentation says there is one model server per cluster, that a platform administrator chooses its models, and that no model is uploaded through the platform
    And the documentation says an installation model answers no judgment, makes no embedding and serves nothing outside the installation
    And the documentation says that an installation without a way out to fetch models supplies them itself
