/**
 * A service's history: every change its members made, newest first, each with the generation it
 * produced and who made it, and for each that recorded a descriptor the image it ran and a digest
 * of it. A row whose descriptor differs from the service's own offers a rollback to it. A section
 * of the service, beside its overview, topology and logs.
 */
import { redirect, useLoaderData, type ActionFunctionArgs, type LoaderFunctionArgs, type MetaFunction } from "react-router";
import { act, applyPrimary, guard, pageData, projectShell, text, useConsoleContext } from "../context.ts";
import { ConsoleErrorBoundary } from "../ui/errors.tsx";
import { ConsoleForm, ConsoleLink, Submit, useConsole, when } from "../ui/console.tsx";
import { Refused } from "../ui/refused.tsx";
import { HostActions } from "../extensions/render.tsx";
import { Page, ServiceSections } from "../ui/shell.tsx";

export const meta: MetaFunction<typeof loader> = ({ loaderData }) => [{ title: `History of ${loaderData?.name ?? "a service"} · ankka` }];

export async function loader({ params, context }: LoaderFunctionArgs) {
  const ctx = useConsoleContext(context);
  const { projectId, name } = params as { projectId: string; name: string };
  return guard(ctx, async () => {
    const [history, project, services, page] = await Promise.all([
      ctx.client.history(projectId, name),
      ctx.client.getProject(projectId),
      ctx.client.listServices(projectId),
      pageData(ctx),
    ]);
    const organization = await ctx.client.getOrganization(project.organizationId);
    return {
      console: projectShell(page, organization, project, services, [{ label: name, to: `projects/${encodeURIComponent(projectId)}/services/${encodeURIComponent(name)}` }, { label: "History" }], applyPrimary(projectId, name), name),
      name,
      projectId,
      history: [...history].reverse(),
    };
  });
}

export async function action({ request, params, context }: ActionFunctionArgs) {
  const ctx = useConsoleContext(context);
  const { projectId, name } = params as { projectId: string; name: string };
  const form = await request.formData();
  const intent = text(form, "intent");
  return act(ctx, intent, form, async () => {
    if (intent !== "rollback") throw new Response(`unknown operation '${intent}'`, { status: 400 });
    await ctx.client.rollback(projectId, name, Number(text(form, "generation")));
    return redirect(ctx.href(`projects/${encodeURIComponent(projectId)}/services/${encodeURIComponent(name)}/history`));
  });
}

/**
 * The generations a rollback can be offered at: each whose entry recorded a descriptor different
 * from the one the service has now. The newest entry that recorded one is the service's own, since
 * only an apply or a rollback changes it. An entry recorded before digests were kept offers
 * nothing, though the CLI can still roll back to it; the control plane decides either way.
 */
function rollbackTargets(history: { generation: number; digest?: string }[]): Set<number> {
  const recorded = history.filter((h) => h.digest !== undefined);
  if (recorded.length === 0) return new Set();
  const current = recorded.reduce((a, b) => (b.generation > a.generation ? b : a)).digest;
  return new Set(recorded.filter((h) => h.digest !== current).map((h) => h.generation));
}

function RollBack({ generation, image, entity }: { generation: number; image?: string; entity: unknown }) {
  const { shows } = useConsole();
  return (
    <>
      {shows("service.rollback") ? (
        <details className="ac-more">
          <summary>Roll back</summary>
          <p>
            Apply generation {generation}'s descriptor again{image ? ` (image ${image})` : ""} as a new generation.
          </p>
          <ConsoleForm intent="rollback">
            <input type="hidden" name="generation" value={generation} />
            <Submit intent="rollback">Roll back to generation {generation}</Submit>
          </ConsoleForm>
        </details>
      ) : null}
      <HostActions operation="service.rollback" entity={entity} />
    </>
  );
}

const words = {
  applied: "Applied",
  "rolled-back": "Rolled back",
  restarted: "Restarted",
  paused: "Paused",
  resumed: "Resumed",
  exposed: "Exposed",
  unexposed: "Unexposed",
  deleted: "Deleted",
} as Record<string, string>;

export default function ServiceHistory() {
  const { name, projectId, history } = useLoaderData<typeof loader>();
  const offered = rollbackTargets(history);
  return (
    <Page>
      <div className="ac-head">
        <h1>History of {name}</h1>
        <ServiceSections projectId={projectId} name={name} current="history" />
      </div>
      <section className="ac-card" aria-labelledby="history">
        <h2 id="history">History</h2>
        {history.length === 0 ? (
          <p className="ac-empty">Nothing recorded yet.</p>
        ) : (
          <div className="ac-table-wrap">
            <table className="ac-table">
              <thead>
                <tr>
                  <th scope="col">What</th>
                  <th scope="col" className="ac-num">
                    Generation
                  </th>
                  <th scope="col">Image</th>
                  <th scope="col">Digest</th>
                  <th scope="col">Who</th>
                  <th scope="col">When</th>
                  <th scope="col" aria-label="Roll back" />
                </tr>
              </thead>
              <tbody>
                {history.map((h, i) => (
                  <tr key={i}>
                    <td>{h.rolledBackTo !== undefined ? `Rolled back to generation ${h.rolledBackTo}` : (words[h.kind] ?? h.kind)}</td>
                    <td className="ac-num">{h.generation}</td>
                    <td>{h.image ?? "—"}</td>
                    <td>
                      {h.digest ? (
                        <ConsoleLink
                          to={`projects/${encodeURIComponent(projectId)}/services/apply?name=${encodeURIComponent(name)}&generation=${h.generation}`}
                          aria-label={`Generation ${h.generation}'s descriptor`}
                        >
                          <code title={h.digest}>{h.digest.slice(0, 12)}</code>
                        </ConsoleLink>
                      ) : (
                        "—"
                      )}
                    </td>
                    <td>
                      {h.actor ? (h.actor.display ?? h.actor.subject) : "—"}
                      {h.actor?.administrative ? " (as administrator)" : ""}
                    </td>
                    <td>{when(h.at)}</td>
                    <td>{offered.has(h.generation) ? <RollBack generation={h.generation} image={h.image} entity={{ name, projectId, entry: h }} /> : null}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        <Refused intent="rollback" />
      </section>
    </Page>
  );
}

export const ErrorBoundary = ConsoleErrorBoundary;
