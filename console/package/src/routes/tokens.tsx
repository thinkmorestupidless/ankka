/**
 * An organization's deploy tokens. A new token's secret is shown exactly once: the creation
 * redirects (so a reload can never create a second token), carrying the secret across in a sealed
 * cookie that this page reads and clears on its next render.
 */
import { redirect, useLoaderData, type ActionFunctionArgs, type LoaderFunctionArgs, type MetaFunction } from "react-router";
import { act, guard, organizationShell, pageData, text, useConsoleContext } from "../context.ts";
import { ConsoleErrorBoundary } from "../ui/errors.tsx";
import { ConsoleForm, Field, Submit, useConsole, when } from "../ui/console.tsx";
import { Page, SectionTitle } from "../ui/shell.tsx";
import { Refused, useRefusal } from "../ui/refused.tsx";
import { HostActions } from "../extensions/render.tsx";

export const meta: MetaFunction = () => [{ title: "Deploy tokens · ankka" }];

export async function loader({ request, params, context }: LoaderFunctionArgs) {
  const ctx = useConsoleContext(context);
  const id = params.organizationId!;
  return guard(ctx, async () => {
    const [organization, tokens, projects, page] = await Promise.all([ctx.client.getOrganization(id), ctx.client.tokens(id), ctx.client.listProjects(), pageData(ctx)]);
    const created = await ctx.runtime.tokenFlash.take(request, ctx.headers);
    // Shown from the one-time cookie alone: the listing is a projection and may not show the new token yet.
    return { console: organizationShell(page, organization, projects, "tokens", [{ label: "Deploy tokens" }]), organization, tokens, created };
  });
}

export async function action({ request, params, context }: ActionFunctionArgs) {
  const ctx = useConsoleContext(context);
  const id = params.organizationId!;
  const form = await request.formData();
  const intent = text(form, "intent");
  const self = ctx.href(`organizations/${encodeURIComponent(id)}/tokens`);
  return act(ctx, intent, form, async () => {
    if (intent === "create") {
      const days = text(form, "expiresInDays");
      const created = await ctx.client.createToken(id, { label: text(form, "label"), expiresIn: days ? Number(days) * 86_400 : undefined });
      await ctx.runtime.tokenFlash.write(created, ctx.headers);
      return redirect(self);
    }
    if (intent === "revoke") {
      await ctx.client.revokeToken(id, text(form, "tokenId"));
      return redirect(self);
    }
    throw new Response(`unknown operation '${intent}'`, { status: 400 });
  });
}

export default function Tokens() {
  const { organization: o, tokens, created } = useLoaderData<typeof loader>();
  const { shows } = useConsole();
  const refusal = useRefusal("create");
  const inspector = shows("token.create") ? (
    <section aria-labelledby="create-token" className="ac-form">
      <SectionTitle>
        <span id="create-token">Create a deploy token</span>
      </SectionTitle>
      <ConsoleForm intent="create" className="ac-inline">
        <Field label="Label" name="label" required hint="What uses it, such as a repository's CI." defaultValue={refusal?.values.label} autoComplete="off" />
        <Field
          label="Expires after days"
          name="expiresInDays"
          type="number"
          min={0}
          max={365}
          hint="Empty for the platform's default of 90 days; 0 for never; at most 365."
          defaultValue={refusal?.values.expiresInDays}
        />
        <Submit intent="create" primary>
          Create token
        </Submit>
      </ConsoleForm>
      <Refused intent="create" />
      <div className="ac-ops">
        <HostActions operation="token.create" entity={o} />
      </div>
    </section>
  ) : null;
  return (
    <Page inspector={inspector}>
      <h1>Deploy tokens for {o.name}</h1>
      <p className="ac-lede">A deploy token lets a machine, such as a CI job, deploy as a member of this organization. It cannot manage members or tokens.</p>

      {created ? (
        <div className="ac-notice" role="status">
          <p>
            <strong>Copy the token for {created.label} now.</strong> It is shown this once and cannot be retrieved later.
          </p>
          <code className="ac-secret" data-secret>
            {created.secret}
          </code>
        </div>
      ) : null}

      <section className="ac-card" aria-label="Deploy tokens">
        {tokens.length === 0 ? (
          <p className="ac-empty">No deploy tokens.</p>
        ) : (
          <div className="ac-table-wrap">
            <table className="ac-table">
              <caption className="ac-visually-hidden">Deploy tokens</caption>
              <thead>
                <tr>
                  <th scope="col">Label</th>
                  <th scope="col">Id</th>
                  <th scope="col">Created</th>
                  <th scope="col">Expires</th>
                  <th scope="col">Last used</th>
                  <th scope="col">Revoke</th>
                </tr>
              </thead>
              <tbody>
                {tokens.map((t) => (
                  <tr key={t.id} data-token={t.id}>
                    <td>{t.label}</td>
                    <td>{t.id}</td>
                    <td>
                      {when(t.createdAt)}
                      {t.createdBy ? ` by ${t.createdBy}` : ""}
                    </td>
                    <td>{t.expiresAt ? when(t.expiresAt) : "Never"}</td>
                    <td>{t.lastUsed ?? "Never"}</td>
                    <td>
                      {shows("token.revoke") ? (
                        <ConsoleForm intent="revoke">
                          <input type="hidden" name="tokenId" value={t.id} />
                          <Submit intent="revoke" className="ac-button ac-button-small ac-button-danger">
                            Revoke
                          </Submit>
                        </ConsoleForm>
                      ) : null}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        <Refused intent="revoke" />
      </section>
    </Page>
  );
}

export const ErrorBoundary = ConsoleErrorBoundary;
