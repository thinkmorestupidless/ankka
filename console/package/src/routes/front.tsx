/**
 * The front page: who you are, as the control plane sees you, and the organizations you can see —
 * yours with your role in each, or every one of them for a platform administrator — and what the
 * installation is: its version, and its cloud when it names one (feature 044).
 */
import { redirect, useLoaderData, type ActionFunctionArgs, type LoaderFunctionArgs, type MetaFunction } from "react-router";
import { act, guard, pageData, text, useConsoleContext, withShell } from "../context.ts";
import { ConsoleForm, Submit, useConsole, when } from "../ui/console.tsx";
import { Refused } from "../ui/refused.tsx";
import { ConsoleErrorBoundary } from "../ui/errors.tsx";
import { ConsoleLink } from "../ui/console.tsx";
import { Page } from "../ui/shell.tsx";
import { HostActions, useHostActions } from "../extensions/render.tsx";

export const meta: MetaFunction = () => [{ title: "Organizations · ankka" }];

export async function loader({ context }: LoaderFunctionArgs) {
  const ctx = useConsoleContext(context);
  return guard(ctx, async () => {
    const [whoami, organizations, installation, hold] = await Promise.all([
      ctx.client.whoami(),
      ctx.client.listOrganizations(),
      ctx.client.installation(),
      // Whether the control plane is held (feature 041); one that cannot say leaves it out.
      ctx.client.restoreHold().catch(() => undefined),
    ]);
    const shell = {
      area: "organizations" as const,
      crumbs: [{ label: "Organizations" }],
      primary: { label: "Create an organization", to: "organizations/new", operation: "organization.create" as const },
    };
    return { console: withShell(await pageData(ctx), shell), whoami, organizations, installation, hold };
  });
}

export async function action({ request, context }: ActionFunctionArgs) {
  const ctx = useConsoleContext(context);
  const form = await request.formData();
  const intent = text(form, "intent");
  return act(ctx, intent, form, async () => {
    if (intent !== "release") throw new Response(`unknown operation '${intent}'`, { status: 400 });
    await ctx.client.releaseRestoreHold();
    return redirect(ctx.href(""));
  });
}

const roleWords = { owner: "Owner", member: "Member" } as const;

export default function Front() {
  const { whoami, organizations, installation, hold } = useLoaderData<typeof loader>();
  const cloud = installation.cloud ?? null;
  const backups = installation.backups ?? null;
  const hostActions = useHostActions("organization.create");
  const { shows } = useConsole();
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

      {hold?.held ? (
        <section className="ac-card" aria-labelledby="hold-title" data-held="yes">
          <h2 id="hold-title">The control plane is held</h2>
          <p>
            Its database was restored to {hold.targetTime ? when(hold.targetTime) : "an earlier moment"}, and it changes nothing in the cluster until a platform administrator releases
            it.
          </p>
          {hold.services.length > 0 ? (
            <ul className="ac-topics" aria-label="Services that differ">
              {hold.services.map((d) => (
                <li key={`${d.project}/${d.service}`} data-differs={`${d.project}/${d.service}`}>
                  {d.project}/{d.service}: recorded {d.recordedImage ?? "nothing"}, the cluster runs {d.clusterImage ?? "nothing"}
                </li>
              ))}
            </ul>
          ) : null}
          {hold.unknownProjects.length > 0 ? <p>Projects the database does not know: {hold.unknownProjects.join(", ")}.</p> : null}
          {whoami.platformAdmin && shows("installation.release") ? (
            <ConsoleForm intent="release">
              <Submit intent="release">Release the control plane</Submit>
            </ConsoleForm>
          ) : null}
          <Refused intent="release" />
        </section>
      ) : hold?.releasedAt ? (
        <p data-held="released">
          The control plane was released after its database's restore, {when(hold.releasedAt)}
          {hold.releasedBy ? ` by ${hold.releasedBy}` : ""}.
        </p>
      ) : null}

      {backups ? (
        <p className="ac-hint" data-backups={backups.backupTarget}>
          {backups.notBackedUp ??
            `Backups are kept at least ${backups.retentionDays} days${backups.sharesFailureDomain ? ", in the cluster they back up" : ", with a copy outside the cluster"}.`}
        </p>
      ) : null}

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

      <section className="ac-card" aria-labelledby="installation">
        <h2 id="installation">This installation</h2>
        <dl className="ac-facts">
          <dt>Platform version</dt>
          <dd>{installation.platformVersion}</dd>
          <dt>Cloud</dt>
          <dd>{cloud ? `${cloud.provider}, account ${cloud.account}` : "None: everything is served by the installation itself"}</dd>
          {cloud ? (
            <>
              <dt>Location</dt>
              <dd>{cloud.location}</dd>
            </>
          ) : null}
          {cloud?.kmsKey ? (
            <>
              <dt>Wrapping key</dt>
              <dd>{cloud.kmsKey}</dd>
            </>
          ) : null}
        </dl>
      </section>
    </Page>
  );
}

export const ErrorBoundary = ConsoleErrorBoundary;
