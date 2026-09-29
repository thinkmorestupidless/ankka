/** Creates a project in an organization, then goes to it, read by id. */
import { redirect, useLoaderData, type ActionFunctionArgs, type LoaderFunctionArgs, type MetaFunction } from "react-router";
import { act, guard, pageData, text, useConsoleContext } from "../context.ts";
import { ConsoleErrorBoundary } from "../ui/errors.tsx";
import { Breadcrumbs, ConsoleForm, Field, Submit } from "../ui/console.tsx";
import { Refused, useRefusal } from "../ui/refused.tsx";

export const meta: MetaFunction = () => [{ title: "Create a project · ankka" }];

export async function loader({ params, context }: LoaderFunctionArgs) {
  const ctx = useConsoleContext(context);
  return guard(ctx, async () => ({ console: await pageData(ctx), organization: await ctx.client.getOrganization(params.organizationId!) }));
}

export async function action({ request, params, context }: ActionFunctionArgs) {
  const ctx = useConsoleContext(context);
  const form = await request.formData();
  const id = text(form, "id");
  return act(ctx, "create", form, async () => {
    await ctx.client.createProject(id, text(form, "name"), params.organizationId!);
    return redirect(ctx.href(`projects/${encodeURIComponent(id)}`));
  });
}

export default function NewProject() {
  const { organization: o } = useLoaderData<typeof loader>();
  const refusal = useRefusal("create");
  return (
    <section className="ac-page">
      <Breadcrumbs trail={[{ label: "Organizations", to: "" }, { label: o.name, to: `organizations/${encodeURIComponent(o.id)}` }, { label: "New project" }]} />
      <h1>Create a project in {o.name}</h1>
      <p className="ac-lede">A project gets a namespace of its own; each of its services gets its own database.</p>
      <ConsoleForm intent="create" className="ac-form">
        <Field
          label="Id"
          name="id"
          required
          pattern="[a-z]([\-a-z0-9]{0,61}[a-z0-9])?"
          hint="Lowercase letters, digits and hyphens, starting with a letter. Part of every exposed service's address, and never reusable once deleted."
          defaultValue={refusal?.values.id}
          autoComplete="off"
        />
        <Field label="Name" name="name" required defaultValue={refusal?.values.name} />
        <Refused intent="create" />
        <div>
          <Submit intent="create">Create project</Submit>
        </div>
      </ConsoleForm>
    </section>
  );
}

export const ErrorBoundary = ConsoleErrorBoundary;
