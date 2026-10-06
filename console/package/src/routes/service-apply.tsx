/**
 * Applies a descriptor: pasted, read from a file, or started from the one a service applied at an
 * earlier generation (`?name=cart&generation=1`). The console checks only that the text is JSON and
 * names a service; every rule about what a descriptor may say is the control plane's, and its
 * refusal is shown problem by problem beside the text.
 */
import { redirect, useLoaderData, type ActionFunctionArgs, type LoaderFunctionArgs, type MetaFunction } from "react-router";
import { act, guard, pageData, projectShell, useConsoleContext, type ActionRefusal } from "../context.ts";
import { ConsoleErrorBoundary } from "../ui/errors.tsx";
import { ConsoleForm, Submit } from "../ui/console.tsx";
import { Page } from "../ui/shell.tsx";
import { Refused, useRefusal } from "../ui/refused.tsx";
import { data } from "react-router";

export const meta: MetaFunction = () => [{ title: "Apply a descriptor · ankka" }];

export async function loader({ request, params, context }: LoaderFunctionArgs) {
  const ctx = useConsoleContext(context);
  return guard(ctx, async () => {
    const [project, services, page] = await Promise.all([ctx.client.getProject(params.projectId!), ctx.client.listServices(params.projectId!), pageData(ctx)]);
    const organization = await ctx.client.getOrganization(project.organizationId);
    const query = new URL(request.url).searchParams;
    const name = query.get("name");
    const generation = query.get("generation");
    const from =
      name && generation ? JSON.stringify(await ctx.client.descriptor(project.id, name, Number(generation)), null, 2) : undefined;
    const tail = name
      ? [{ label: name, to: `projects/${encodeURIComponent(project.id)}/services/${encodeURIComponent(name)}` }, { label: "Apply a descriptor" }]
      : [{ label: "Apply a descriptor" }];
    return { console: projectShell(page, organization, project, services, tail, undefined, name ?? undefined), project, organization, name, generation, from };
  });
}

const notApplied = (reason: string, descriptor: string) =>
  data<ActionRefusal>({ intent: "apply", status: 400, reason, problems: [reason], values: { descriptor } }, { status: 400 });

export async function action({ request, params, context }: ActionFunctionArgs) {
  const ctx = useConsoleContext(context);
  const projectId = params.projectId!;
  const form = await request.formData();
  const file = form.get("file");
  const pasted = String(form.get("descriptor") ?? "");
  const descriptor = pasted.trim() !== "" ? pasted : file instanceof File && file.size > 0 ? await file.text() : "";
  if (descriptor.trim() === "") return notApplied("Paste a descriptor or choose a service.json file.", descriptor);
  let name: unknown;
  try {
    name = (JSON.parse(descriptor) as { name?: unknown }).name;
  } catch (e) {
    return notApplied(`The descriptor is not valid JSON: ${e instanceof Error ? e.message : String(e)}`, descriptor);
  }
  if (typeof name !== "string" || name === "") return notApplied('The descriptor has no "name": it names the service it deploys.', descriptor);
  const values = new FormData();
  values.set("descriptor", descriptor);
  return act(ctx, "apply", values, async () => {
    await ctx.client.applyService(projectId, name as string, descriptor);
    return redirect(ctx.href(`projects/${encodeURIComponent(projectId)}/services/${encodeURIComponent(name as string)}`));
  });
}

const example = `{
  "name": "cart",
  "service": {
    "image": "ghcr.io/example/cart:1.0.0"
  }
}`;

export default function ApplyDescriptor() {
  const { name, generation, from } = useLoaderData<typeof loader>();
  const refusal = useRefusal("apply");
  return (
    <Page>
      <h1>Apply a descriptor{name ? ` to ${name}` : ""}</h1>
      <p className="ac-lede">
        A descriptor is a service's <code>service.json</code>. Applying it creates the service, or updates it if one with that name exists.
      </p>
      {from ? <p className="ac-notice">This is the descriptor {name} applied at generation {generation}. Applying it, changed or not, is a new generation.</p> : null}
      <ConsoleForm intent="apply" encType="multipart/form-data" className="ac-card ac-form">
        <div className="ac-field">
          <label htmlFor="descriptor">Descriptor</label>
          <textarea
            id="descriptor"
            name="descriptor"
            spellCheck={false}
            placeholder={example}
            defaultValue={refusal?.values.descriptor ?? from}
            aria-describedby="descriptor-hint"
            aria-invalid={refusal ? true : undefined}
          />
          <p className="ac-hint" id="descriptor-hint">
            Paste it here, or choose the file below instead.
          </p>
        </div>
        <div className="ac-field">
          <label htmlFor="file">Or a service.json file</label>
          <input id="file" name="file" type="file" accept="application/json,.json" />
        </div>
        <Refused intent="apply" />
        <div>
          <Submit intent="apply" primary>
            Apply
          </Submit>
        </div>
      </ConsoleForm>
    </Page>
  );
}

export const ErrorBoundary = ConsoleErrorBoundary;
