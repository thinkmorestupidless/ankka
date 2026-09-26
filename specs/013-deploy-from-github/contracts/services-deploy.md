# Contract: `ankka services deploy`

**Feature**: [../spec.md](../spec.md) | **Plan**: [../plan.md](../plan.md) | **Research**: R8

Akka's verb and Akka's positional image; everything else from the descriptor.

```text
Usage: ankka services deploy [--file <string>] [--url <string>] [--token <string>] [--project <string>] [--output <string>] <service> <image>

Deploy a service: the descriptor's settings with this image.

Options and flags:
    --help
        Display this help text.
    --file <string>, -f <string>
        Descriptor file, or '-' for stdin. Defaults to service.json.
    --url <string>
        Control plane base URL. Defaults to the configured value.
    --token <string>
        Bearer token. Prefer ANKKA_TOKEN or the config file.
    --project <string>, -p <string>
        Project id. Defaults to the configured project.
    --output <string>, -o <string>
        Output format: table or json.
```

## Behaviour

1. Read the descriptor from `--file` (default `service.json`, relative to the working directory; `-`
   is stdin through `Console.in`, as `apply` reads it).
2. Refuse, exit `1`, when `descriptor.name != service`: `error: service.json names 'orders', not
   'cart'`. This is US3's "the build and the descriptor disagree" guard: a workflow that deploys the
   wrong file stops here.
3. Refuse, exit `1`, when `image` is empty or contains whitespace.
4. `ServiceDescriptor.withImage(image)` — a pure copy in `controlplane-api` — then
   `descriptor.problems`, reported all at once as `apply` reports them.
5. `PUT /services/{project}/{service}` with the modified descriptor: exactly `apply`'s request.
6. Output: `apply`'s (`Output.service`), in table or JSON.

The file on disk is never modified. There is no `--push`; the help's one-line description of the
command says "the image must already be where the cluster can pull it", because ankka runs no registry.

## `ServiceDescriptor.withImage`

```scala
extension (descriptor: ServiceDescriptor)
  def withImage(image: String): ServiceDescriptor =
    descriptor.copy(service = descriptor.service.copy(image = image))
```

In `controlplane-api` so a future control plane route could offer the same operation without the CLI
re-deriving it. Unit tested: only `service.image` changes; `problems` on the result is `problems` on
the original plus any the new image introduces.

## Relationship to `apply`

`apply -f` is unchanged and remains the declarative path. `deploy` is `apply` with one field from the
command line, which is what Akka's two commands amount to when the descriptor holds everything but the
per-build image. The generated project's `service.json` keeps `"<name>:latest"` so that
`kind load docker-image` and `apply` keep working on a laptop exactly as documented.

## Tests that pin this

- `DescriptorSuite` (or a new `DeploySuite` in `controlplane-api`): `withImage`.
- `CliEndToEndSuite`: `services deploy` against the real control plane — the applied service reports
  the command-line image, the file is unchanged, a name mismatch exits `1` before any request.
- `CliReferenceSuite`: the generated help above matches the page.
