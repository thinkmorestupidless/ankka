/**
 * An erasure request (feature 042): its data subject, where it stands, its hold and who changed it,
 * why the last attempt to apply it failed, and each service's completion. A held request's not-before
 * date can be changed, the request withdrawn, or its hold overridden by an owner with a reason; an
 * applied one gives its erasure certificate to download. Reached by id from the project's page.
 */
import { redirect, useLoaderData, type ActionFunctionArgs, type LoaderFunctionArgs, type MetaFunction } from "react-router";
import { act, guard, pageData, projectShell, text, useConsoleContext } from "../context.ts";
import { ConsoleErrorBoundary } from "../ui/errors.tsx";
import { ConsoleForm, Field, Submit, useConsole, when } from "../ui/console.tsx";
import { Page, SectionTitle } from "../ui/shell.tsx";
import { Refused, useRefusal } from "../ui/refused.tsx";
import { HostActions } from "../extensions/render.tsx";
import { button } from "../ui/primitives/button.ts";
import { dateOf, notBeforeOf } from "../ui/erasures.ts";

export const meta: MetaFunction<typeof loader> = ({ loaderData }) => [{ title: `Erasure request for ${loaderData?.request.subject ?? "a data subject"} · ankka` }];

export async function loader({ params, context }: LoaderFunctionArgs) {
  const ctx = useConsoleContext(context);
  const { projectId, requestId } = params as { projectId: string; requestId: string };
  return guard(ctx, async () => {
    const [request, project, services, page] = await Promise.all([
      ctx.client.getErasure(projectId, requestId),
      ctx.client.getProject(projectId),
      ctx.client.listServices(projectId),
      pageData(ctx),
    ]);
    const organization = await ctx.client.getOrganization(project.organizationId);
    return {
      console: projectShell(page, organization, project, services, [{ label: "Erasure request" }]),
      projectId,
      request,
    };
  });
}

export async function action({ request, params, context }: ActionFunctionArgs) {
  const ctx = useConsoleContext(context);
  const { projectId, requestId } = params as { projectId: string; requestId: string };
  const form = await request.formData();
  const intent = text(form, "intent");
  const self = ctx.href(`projects/${encodeURIComponent(projectId)}/erasures/${encodeURIComponent(requestId)}`);
  return act(ctx, intent, form, async () => {
    switch (intent) {
      case "erasure-replace":
        await ctx.client.replaceErasure(projectId, requestId, {
          notBefore: notBeforeOf(text(form, "erasureNotBefore")),
          reason: text(form, "erasureReason") || undefined,
        });
        return redirect(self);
      case "erasure-withdraw":
        await ctx.client.withdrawErasure(projectId, requestId);
        return redirect(self);
      case "erasure-override":
        // The reason is sent as given; an empty one is the control plane's to refuse, in its words.
        await ctx.client.overrideErasureHold(projectId, requestId, text(form, "overrideReason"));
        return redirect(self);
      default:
        throw new Response(`unknown operation '${intent}'`, { status: 400 });
    }
  });
}

export default function Erasure() {
  const { projectId, request: r } = useLoaderData<typeof loader>();
  const { shows, href } = useConsole();
  const replaceRefusal = useRefusal("erasure-replace");
  const overrideRefusal = useRefusal("erasure-override");
  const held = r.state === "held";
  const path = `projects/${encodeURIComponent(projectId)}/erasures/${encodeURIComponent(r.id)}`;
  const inspector = held ? (
    <>
      {shows("erasure.override") ? (
        <section className="ac-form" aria-labelledby="erasure-override-title">
          <SectionTitle>
            <span id="erasure-override-title">Override hold</span>
          </SectionTitle>
          <ConsoleForm intent="erasure-override" className="ac-form">
            <Field
              label="Reason for the override"
              name="overrideReason"
              required
              autoComplete="off"
              defaultValue={overrideRefusal?.values.overrideReason}
              hint="Owners only. The request is applied at once, and the reason is on its erasure certificate."
            />
            <Refused intent="erasure-override" />
            <div>
              <Submit intent="erasure-override" danger>
                Override hold
              </Submit>
            </div>
          </ConsoleForm>
          <div className="ac-ops">
            <HostActions operation="erasure.override" entity={r} />
          </div>
        </section>
      ) : null}
      {shows("erasure.replace") ? (
        <details className="ac-more" open={replaceRefusal !== undefined || undefined}>
          <summary>Change the hold</summary>
          <ConsoleForm intent="erasure-replace" className="ac-form">
            <Field
              label="Not-before date"
              name="erasureNotBefore"
              type="date"
              defaultValue={replaceRefusal?.values.erasureNotBefore ?? dateOf(r.notBefore)}
              hint="The start of that day, UTC. It replaces the date and the reason the request has."
            />
            <Field label="Reason" name="erasureReason" autoComplete="off" defaultValue={replaceRefusal?.values.erasureReason ?? r.reason} />
            <Refused intent="erasure-replace" />
            <div>
              <Submit intent="erasure-replace">Change the hold</Submit>
            </div>
          </ConsoleForm>
        </details>
      ) : null}
      {shows("erasure.withdraw") ? (
        <ConsoleForm intent="erasure-withdraw" className="ac-inline">
          <Submit intent="erasure-withdraw">Withdraw</Submit>
          <Refused intent="erasure-withdraw" />
        </ConsoleForm>
      ) : null}
    </>
  ) : undefined;
  return (
    <Page inspector={inspector}>
      <h1>
        Erasure request for <code>{r.subject}</code>
      </h1>
      <dl className="ac-card ac-facts" data-erasure={r.id} data-state={r.state}>
        <dt>Id</dt>
        <dd>
          <code>{r.id}</code>
        </dd>
        <dt>Data subject</dt>
        <dd>
          <code>{r.subject}</code>
        </dd>
        <dt>State</dt>
        <dd data-erasure-state>{r.state}</dd>
        <dt>Asked</dt>
        <dd data-asked-by={r.askedBy}>
          {r.askedAt ? when(r.askedAt) : "—"}
          {r.askedBy ? ` by ${r.askedBy}` : ""}
        </dd>
        <dt>Not-before date</dt>
        <dd data-not-before>
          {r.notBefore ? when(r.notBefore) : "none: applied when asked"}
          {r.reason ? ` — ${r.reason}` : ""}
          {r.lapsedAt ? ` (the hold lapsed ${when(r.lapsedAt)})` : ""}
        </dd>
        {r.holdOverride ? (
          <>
            <dt>Hold overridden</dt>
            <dd data-override>
              by {r.holdOverride.owner}
              {r.holdOverride.at ? `, ${when(r.holdOverride.at)}` : ""}: {r.holdOverride.reason}
            </dd>
          </>
        ) : null}
        {r.correlation ? (
          <>
            <dt>Correlation</dt>
            <dd>
              <code>{r.correlation}</code>
            </dd>
          </>
        ) : null}
        {r.destroyedAt ? (
          <>
            <dt>Applied</dt>
            <dd>{when(r.destroyedAt)}: the data subject&apos;s key is destroyed</dd>
          </>
        ) : null}
        {r.lastFailure ? (
          <>
            <dt>Not applied yet</dt>
            <dd data-last-failure role="status">
              {r.lastFailure.reason}
              {r.lastFailure.at ? `, since ${when(r.lastFailure.at)}` : ""}. The platform tries again.
            </dd>
          </>
        ) : null}
        {r.finalAt ? (
          <>
            <dt>Final</dt>
            <dd>{when(r.finalAt)}</dd>
          </>
        ) : null}
      </dl>

      <section className="ac-card" aria-labelledby="completion">
        <h2 id="completion">Completion</h2>
        {r.completions.length === 0 ? (
          <p className="ac-empty">{r.state === "applied" ? "No service has reported its completion yet." : "Each service reports its completion once the request is applied."}</p>
        ) : (
          <div className="ac-table-wrap">
            <table className="ac-table" aria-describedby="completion">
              <thead>
                <tr>
                  <th scope="col">Service</th>
                  <th scope="col">Completed</th>
                  <th scope="col">Rows redacted</th>
                  <th scope="col">Tokens dropped</th>
                  <th scope="col">Sessions ended</th>
                  <th scope="col">Erasure handler</th>
                </tr>
              </thead>
              <tbody>
                {r.completions.map((c) => (
                  <tr key={c.service} data-completion={c.service}>
                    <td>{c.service}</td>
                    <td>{c.completedAt ? when(c.completedAt) : "not yet"}</td>
                    <td>{c.rowsRedacted}</td>
                    <td>{c.tokensDropped}</td>
                    <td>{c.sessionsEnded}</td>
                    <td>
                      {c.handler
                        ? `${c.handler.objectsErased} objects erased, ${c.handler.finality}${c.handler.error ? `; failed: ${c.handler.error}` : ""}`
                        : "—"}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        {r.state === "applied" ? (
          <p>
            <a className={button()} href={href(`${path}/certificate`)} download={`erasure-certificate-${r.id}.json`}>
              Download the erasure certificate
            </a>
          </p>
        ) : null}
      </section>
    </Page>
  );
}

export const ErrorBoundary = ConsoleErrorBoundary;
