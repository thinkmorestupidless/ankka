/**
 * The front page: who you are, as the control plane sees you, and the organizations you can see —
 * yours with your role in each, or every one of them for a platform administrator.
 */
import { useLoaderData, type LoaderFunctionArgs, type MetaFunction } from "react-router";
import { guard, pageData, useConsoleContext, withShell } from "../context.ts";
import { ConsoleErrorBoundary } from "../ui/errors.tsx";
import { ConsoleLink } from "../ui/console.tsx";
import { Page } from "../ui/shell.tsx";
import { HostActions, useHostActions } from "../extensions/render.tsx";

export const meta: MetaFunction = () => [{ title: "Organizations · ankka" }];

export async function loader({ context }: LoaderFunctionArgs) {
  const ctx = useConsoleContext(context);
  return guard(ctx, async () => {
    const [whoami, organizations] = await Promise.all([ctx.client.whoami(), ctx.client.listOrganizations()]);
    const shell = {
      area: "organizations" as const,
      crumbs: [{ label: "Organizations" }],
      primary: { label: "Create an organization", to: "organizations/new", operation: "organization.create" as const },
    };
    return { console: withShell(await pageData(ctx), shell), whoami, organizations };
  });
}

const roleWords = { owner: "Owner", member: "Member" } as const;

export default function Front() {
  const { whoami, organizations } = useLoaderData<typeof loader>();
  const hostActions = useHostActions("organization.create");
  return (
    <Page
      inspector={
        hostActions ? (
          <div className="ac-ops">
            <HostActions operation="organization.create" />
          </div>
        ) : null
      }
    >
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
        <div className="ac-card ac-table-wrap">
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
    </Page>
  );
}

export const ErrorBoundary = ConsoleErrorBoundary;
