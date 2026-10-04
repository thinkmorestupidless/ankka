/**
 * Creates an organization. Its id is permanent and never reused; the person creating it becomes its
 * first owner. On success the page goes to the organization itself, read by id, because the listing
 * is a projection and may not show it yet.
 */
import { redirect, type ActionFunctionArgs, type LoaderFunctionArgs, type MetaFunction } from "react-router";
import { act, guard, pageData, text, useConsoleContext, withShell } from "../context.ts";
import { ConsoleErrorBoundary } from "../ui/errors.tsx";
import { ConsoleForm, Field, Submit } from "../ui/console.tsx";
import { Page } from "../ui/shell.tsx";
import { Refused, useRefusal } from "../ui/refused.tsx";

export const meta: MetaFunction = () => [{ title: "Create an organization · ankka" }];

export async function loader({ context }: LoaderFunctionArgs) {
  const ctx = useConsoleContext(context);
  return guard(ctx, async () => ({
    console: withShell(await pageData(ctx), { area: "organizations", crumbs: [{ label: "Organizations", to: "" }, { label: "New organization" }] }),
  }));
}

export async function action({ request, context }: ActionFunctionArgs) {
  const ctx = useConsoleContext(context);
  const form = await request.formData();
  const id = text(form, "id");
  return act(ctx, "create", form, async () => {
    await ctx.client.createOrganization(id, { name: text(form, "name") });
    return redirect(ctx.href(`organizations/${encodeURIComponent(id)}`));
  });
}

export default function NewOrganization() {
  const refusal = useRefusal("create");
  return (
    <Page>
      <h1>Create an organization</h1>
      <p className="ac-lede">You become its first owner. Invite others once it exists.</p>
      <ConsoleForm to="organizations/new" intent="create" className="ac-card ac-form">
        <Field
          label="Id"
          name="id"
          required
          pattern="[a-z]([\-a-z0-9]{0,61}[a-z0-9])?"
          hint="Lowercase letters, digits and hyphens, starting with a letter. It cannot be changed, and once deleted it cannot be used again."
          defaultValue={refusal?.values.id}
          autoComplete="off"
        />
        <Field label="Name" name="name" required hint="What people see. You can rename it later." defaultValue={refusal?.values.name} />
        <Refused intent="create" />
        <div>
          <Submit intent="create" primary>
            Create organization
          </Submit>
        </div>
      </ConsoleForm>
    </Page>
  );
}

export const ErrorBoundary = ConsoleErrorBoundary;
