/**
 * A service's history: every change its members made, newest first, each with the generation it
 * produced and who made it. A section of the service, beside its overview, topology and logs.
 */
import { useLoaderData, type LoaderFunctionArgs, type MetaFunction } from "react-router";
import { applyPrimary, guard, pageData, projectShell, useConsoleContext } from "../context.ts";
import { ConsoleErrorBoundary } from "../ui/errors.tsx";
import { when } from "../ui/console.tsx";
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

const words = {
  applied: "Applied",
  restarted: "Restarted",
  paused: "Paused",
  resumed: "Resumed",
  exposed: "Exposed",
  unexposed: "Unexposed",
  deleted: "Deleted",
} as Record<string, string>;

export default function ServiceHistory() {
  const { name, projectId, history } = useLoaderData<typeof loader>();
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
                  <th scope="col">Who</th>
                  <th scope="col">When</th>
                </tr>
              </thead>
              <tbody>
                {history.map((h, i) => (
                  <tr key={i}>
                    <td>{words[h.kind] ?? h.kind}</td>
                    <td className="ac-num">{h.generation}</td>
                    <td>
                      {h.actor ? (h.actor.display ?? h.actor.subject) : "—"}
                      {h.actor?.administrative ? " (as administrator)" : ""}
                    </td>
                    <td>{when(h.at)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>
    </Page>
  );
}

export const ErrorBoundary = ConsoleErrorBoundary;
