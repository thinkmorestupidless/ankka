/**
 * The front page: who you are, as the control plane sees you, and the organizations you can see —
 * yours with your role in each, or every one of them for a platform administrator.
 */
import { useLoaderData, type LoaderFunctionArgs, type MetaFunction } from "react-router";
import { guard, pageData, useConsoleContext } from "../context.ts";
import { ConsoleErrorBoundary } from "../ui/errors.tsx";
import { ConsoleLink, useConsole } from "../ui/console.tsx";
import { HostActions } from "../extensions/render.tsx";

export const meta: MetaFunction = () => [{ title: "Organizations · ankka" }];

export async function loader({ context }: LoaderFunctionArgs) {
  const ctx = useConsoleContext(context);
  return guard(ctx, async () => {
    const [whoami, organizations] = await Promise.all([ctx.client.whoami(), ctx.client.listOrganizations()]);
    return { console: await pageData(ctx), whoami, organizations };
  });
}

const roleWords = { owner: "Owner", member: "Member" } as const;

export default function Front() {
  const { whoami, organizations } = useLoaderData<typeof loader>();
  const { shows } = useConsole();
  return (
    <section className="ac-page">
      <h1>Organizations</h1>
      <p className="ac-lede">
        Signed in as <strong>{whoami.name ?? whoami.email ?? whoami.subject}</strong>
        {whoami.email && whoami.name ? ` (${whoami.email})` : null}
        {whoami.platformAdmin ? ", a platform administrator: you see every organization in this installation." : "."}
      </p>

      {organizations.length === 0 ? (
        <p className="ac-empty">
          You are not a member of any organization yet. Create one, or ask an owner to invite {whoami.email ?? "you"}.
        </p>
      ) : (
        <div className="ac-table-wrap">
          <table className="ac-table">
            <caption className="ac-visually-hidden">Organizations you can see</caption>
            <thead>
              <tr>
                <th scope="col">Name</th>
                <th scope="col">Id</th>
                <th scope="col">Your role</th>
                <th scope="col" className="ac-num">
                  Projects
                </th>
                <th scope="col">State</th>
              </tr>
            </thead>
            <tbody>
              {organizations.map((o) => (
                <tr key={o.id}>
                  <td>
                    <ConsoleLink to={`organizations/${encodeURIComponent(o.id)}`}>{o.name}</ConsoleLink>
                  </td>
                  <td>{o.id}</td>
                  <td>{o.role ? roleWords[o.role] : "Administrator (not a member)"}</td>
                  <td className="ac-num">{o.projects}</td>
                  <td>{o.disabled ? "Disabled" : "Active"}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      <div className="ac-actions">
        {shows("organization.create") ? (
          <ConsoleLink to="organizations/new" className="ac-button">
            Create an organization
          </ConsoleLink>
        ) : null}
        <HostActions operation="organization.create" />
      </div>
    </section>
  );
}

export const ErrorBoundary = ConsoleErrorBoundary;
