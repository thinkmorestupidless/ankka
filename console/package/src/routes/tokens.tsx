/**
 * An organization's deploy tokens. A new token's secret is shown exactly once: the creation
 * redirects (so a reload can never create a second token), carrying the secret across in a sealed
 * cookie that this page reads and clears on its next render.
 */
import { redirect, useLoaderData, type ActionFunctionArgs, type LoaderFunctionArgs, type MetaFunction } from "react-router";
import { act, guard, pageData, text, useConsoleContext } from "../context.ts";
import { ConsoleErrorBoundary } from "../ui/errors.tsx";
import { Breadcrumbs, ConsoleForm, Field, Submit, useConsole, when } from "../ui/console.tsx";
import { Refused, useRefusal } from "../ui/refused.tsx";

export const meta: MetaFunction = () => [{ title: "Deploy tokens · ankka" }];

export async function loader({ request, params, context }: LoaderFunctionArgs) {
  const ctx = useConsoleContext(context);
  const id = params.organizationId!;
  return guard(ctx, async () => {
    const [organization, tokens, page] = await Promise.all([ctx.client.getOrganization(id), ctx.client.tokens(id), pageData(ctx)]);
    const created = await ctx.runtime.tokenFlash.take(request, ctx.headers);
    return { console: page, organization, tokens, created: created?.subject && tokens.some((t) => t.id === created.id) ? created : null };
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
  return (
    <section className="ac-page">
      <Breadcrumbs trail={[{ label: "Organizations", to: "" }, { label: o.name, to: `organizations/${encodeURIComponent(o.id)}` }, { label: "Deploy tokens" }]} />
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
                        <Submit intent="revoke" danger>
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

      {shows("token.create") ? (
        <>
          <h2>Create a deploy token</h2>
          <ConsoleForm intent="create" className="ac-inline">
            <Field label="Label" name="label" required hint="What uses it, such as a repository's CI." defaultValue={refusal?.values.label} autoComplete="off" />
            <Field label="Expires after days" name="expiresInDays" type="number" min={1} hint="Empty for never." defaultValue={refusal?.values.expiresInDays} />
            <Submit intent="create">Create token</Submit>
          </ConsoleForm>
          <Refused intent="create" />
        </>
      ) : null}
    </section>
  );
}

export const ErrorBoundary = ConsoleErrorBoundary;
