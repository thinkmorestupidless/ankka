package com.thinkmorestupidless.ankka.core

/**
 * What the installation's model server reports about itself (feature 053): the keeper writes it,
 * and the operator, the control plane and the sidecar read it.
 *
 * The operator depends on `crd` alone, so it compiles **this same file** (`operator`'s
 * `unmanagedSources` in `build.sbt`), as it compiles `PlatformVariables`. That is why the file
 * imports nothing outside the standard library, and why the codec is elsewhere: `core` decodes with
 * jsoniter (`ModelServerDocumentJson`), the operator with the Jackson it already has, and both are
 * held to one fixture, `ankka/models/document.json`.
 *
 * A state is a word, not an enum, for the same reason: Jackson reads a string, and a reader that
 * meets a word it does not know treats the model as not served.
 */
final case class ModelServerDocument(
    /** The door's address, as services are told it. */
    server: String,
    /** True once Ollama answers and the keeper has completed one pass. */
    ready: Boolean,
    /** When the last pass ended, as an ISO-8601 instant. */
    pass: Option[String] = None,
    /** One line per declared model, and one per model held but no longer declared. */
    models: Vector[ModelStatus] = Vector.empty,
    calls: ModelCalls = ModelCalls(),
    /** Why the server is not ready, or what is wrong with the declaration. */
    detail: Option[String] = None
):

  /** The model of that name, if the server reports one. */
  def model(name: String): Option[ModelStatus] = models.find(_.name == name)

  /** The names of the models served, in the declaration's order. */
  def served: Vector[String] = models.filter(_.state == ModelState.Served).map(_.name)

/** One model as the keeper sees it. */
final case class ModelStatus(
    /** The tag, as declared: `qwen3:0.6b`. */
    name: String,
    /** One of `ModelState.All`. */
    state: String,
    /** The digest Ollama reports for it, once served. */
    digest: Option[String] = None,
    /** What it can do, once fetched: `completion`, `tools`, `thinking`, … */
    capabilities: Vector[String] = Vector.empty,
    /** Why it failed: Ollama's last status line. */
    detail: Option[String] = None,
    /** When it entered its state, as an ISO-8601 instant. */
    since: Option[String] = None
)

/** Calls through the door since the keeper started. */
final case class ModelCalls(
    answered: Long = 0L,
    failed: Long = 0L,
    inFlight: Long = 0L,
    /** In flight beyond what the server runs at once. */
    waiting: Long = 0L
)

/** The states a model is reported in. */
object ModelState:
  /** Declared; not yet held, or held and not yet loaded. */
  val Fetching: String = "fetching"

  /** Fetched, loaded and kept loaded, its digest recorded: the only state a service starts on. */
  val Served: String = "served"

  /** The fetch ended in an error; the line's `detail` is Ollama's. */
  val Failed: String = "failed"

  /** Held, no longer declared, and being removed. */
  val Removing: String = "removing"

  val All: Vector[String] = Vector(Fetching, Served, Failed, Removing)
