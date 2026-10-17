# Release notes draft: installation models (feature 053)

## Added

- **The installation's model server.** A new `models` component runs Ollama in `models-system`
  behind a keeper that admits only the installation's services, over mutual TLS. A platform
  administrator declares the models in the `models-declaration` ConfigMap; the keeper fetches,
  loads and removes them to match. `ankka installation` lists the server and each declared model.
- **Services wait for, or are refused, an installation model.** A descriptor that names a model
  provider with the preset `installation` starts only once that model is served. It fails with a
  reason when the model is not declared, when it failed to fetch, when the cluster has no model
  server, or when the descriptor also gives an address or a key.
- **Local platform.** `ANKKA_LOCAL_MODELS=on ./kustomization/deploy-local.sh`, or
  `just deploy-models`, adds the model server with one small model that runs on a processor.

Agents cannot call an installation model in this release: resolving the preset in the agent and the
sidecar is feature 052's work. The limitations page says so.

## Before you upgrade

1. **Adding the component restarts every running service once.** Each platform container is given
   the model server's address, which changes every pod template. The restart is a rolling update
   that refuses no request. Installations that do not list the component see no change.
2. **A new image, `ankka-model-keeper`,** is published with every release beside the others. An
   overlay that lists the component renames it in its `images:` block at the release it runs.
3. **Ollama's image is about 7 GB unpacked**, pulled by the node the model server runs on, before
   any model is fetched. The declared models are fetched from Ollama's registry on first start.
   Size the node's disk and the model server's volume for both, and allow the first rollout several
   minutes.
