/**
 * An organization: its projects, the grants other organizations' projects offer it (feature 040), and
 * what its owners and the platform administrator may do to it.
 */
import { redirect, useLoaderData, type ActionFunctionArgs, type LoaderFunctionArgs, type MetaFunction } from "react-router";
import { act, guard, organizationShell, pageData, text, useConsoleContext } from "../context.ts";
import { ConsoleErrorBoundary } from "../ui/errors.tsx";
import { ConsoleForm, ConsoleLink, Field, Submit, useConsole, when } from "../ui/console.tsx";
import { Page } from "../ui/shell.tsx";
import { Refused, useRefusal } from "../ui/refused.tsx";
import { HostActions, loadPanels, Panels } from "../extensions/render.tsx";
import { targetText } from "../ui/grants.ts";
import type { Quota, ReceivedGrantDetail } from "../client/schemas.ts";

export const meta: MetaFunction<typeof loader> = ({ loaderData }) => [{ title: `${loaderData?.organization.name ?? "Organization"} · ankka` }];

export async function loader({ params, context }: LoaderFunctionArgs) {
  const ctx = useConsoleContext(context);
  const id = params.organizationId!;
  return guard(ctx, async () => {
    const [organization, projects, offered, page] = await Promise.all([
      ctx.client.getOrganization(id),
      ctx.client.listProjects(),
      ctx.client.organizationGrants(id),
      pageData(ctx),
    ]);
    // An organization whose owners have all left can be given one by an administrator.
    const ownerless = page.principal?.platformAdmin ? !(await ctx.client.members(id)).members.some((m) => m.role === "owner") : false;
    const own = projects.filter((p) => p.organizationId === id);
    return {
      console: organizationShell(page, organization, own, "organizations", [], {
        label: "Create a project",
        to: `organizations/${encodeURIComponent(id)}/projects/new`,
        operation: "project.create",
      }),
      organization,
      projects: own,
      offered,
      ownerless,
      panels: await loadPanels(ctx, "organization", organization),
    };
  });
}

const quotaValue = (form: FormData, name: string): number | undefined => {
  const v = text(form, name);
  return v === "" ? undefined : Number(v);
};

export async function action({ request, params, context }: ActionFunctionArgs) {
  const ctx = useConsoleContext(context);
  const id = params.organizationId!;
  const form = await request.formData();
  const intent = text(form, "intent");
  const self = ctx.href(`organizations/${encodeURIComponent(id)}`);
  return act(ctx, intent, form, async () => {
    switch (intent) {
      case "rename":
        await ctx.client.renameOrganization(id, text(form, "name"));
        return redirect(self);
      case "delete":
        await ctx.client.deleteOrganization(id);
        return redirect(ctx.href(""));
      case "disable":
        await ctx.client.disableOrganization(id);
        return redirect(self);
      case "enable":
        await ctx.client.enableOrganization(id);
        return redirect(self);
      case "quota-set": {
        const quota: Quota = { projects: quotaValue(form, "projects"), services: quotaValue(form, "services"), instances: quotaValue(form, "instances") };
        await ctx.client.setQuota(id, quota);
        return redirect(self);
      }
      case "quota-clear":
        await ctx.client.clearQuota(id);
        return redirect(self);
      case "repair":
        await ctx.client.repair(id, text(form, "subject"), "owner");
        return redirect(self);
      case "grant-accept":
        await ctx.client.answerGrant(id, text(form, "grantId"), "accept");
        return redirect(self);
      case "grant-decline":
        await ctx.client.answerGrant(id, text(form, "grantId"), "decline");
        return redirect(self);
      case "grant-relinquish":
        await ctx.client.answerGrant(id, text(form, "grantId"), "relinquish");
        return redirect(self);
      default:
        throw new Response(`unknown operation '${intent}'`, { status: 400 });
    }
  });
}

const limit = (n: number | undefined, what: string) => (n === undefined ? `any number of ${what}` : `${n} ${what}`);

/** A grant in one phrase for a control's accessible name: what it opens, to whom, from where. */
const grantName = (g: ReceivedGrantDetail) => `${targetText(g.target)} to ${g.grantee} from ${g.grantingProject}`;

export default function Organization() {
  const { organization: o, projects, offered, ownerless, panels, console: page } = useLoaderData<typeof loader>();
  const { shows } = useConsole();
  const admin = page.principal?.platformAdmin ?? false;
  const owner = o.role === "owner" || admin;
  const renameRefusal = useRefusal("rename");
  const deleteRefusal = useRefusal("delete");
  const adminRefused = [useRefusal("disable"), useRefusal("enable"), useRefusal("quota-set"), useRefusal("quota-clear"), useRefusal("repair")].some(Boolean);
  const path = `organizations/${encodeURIComponent(o.id)}`;
  const inspector = (
    <>
      <HostActions operation="project.create" entity={o} />
      {admin ? (
        <details className="ac-more" open={adminRefused || undefined}>
          <summary>Platform administration</summary>
          <div className="ac-ops">
            {o.disabled
              ? shows("organization.enable") && (
                  <ConsoleForm intent="enable">
                    <Submit intent="enable">Enable organization</Submit>
                  </ConsoleForm>
                )
              : shows("organization.disable") && (
                  <ConsoleForm intent="disable">
                    <Submit intent="disable" danger>
                      Disable organization
                    </Submit>
                  </ConsoleForm>
                )}
          </div>
          <Refused intent="disable" />
          <Refused intent="enable" />
          {shows("organization.quota.set") ? (
            <ConsoleForm intent="quota-set" className="ac-inline" aria-label="Quota">
              <Field label="Projects" name="projects" type="number" min={0} defaultValue={o.quota?.projects} hint="Empty for no limit" />
              <Field label="Services" name="services" type="number" min={0} defaultValue={o.quota?.services} />
              <Field label="Instances" name="instances" type="number" min={0} defaultValue={o.quota?.instances} />
              <Submit intent="quota-set">Set quota</Submit>
            </ConsoleForm>
          ) : null}
          <Refused intent="quota-set" />
          {o.quota && shows("organization.quota.clear") ? (
            <ConsoleForm intent="quota-clear">
              <Submit intent="quota-clear">Clear quota</Submit>
            </ConsoleForm>
          ) : null}
          {ownerless && shows("member.repair") ? (
            <ConsoleForm intent="repair" className="ac-inline">
              <Field label="Subject to make owner" name="subject" required hint="This organization has no owner. Name the subject of the person to make one." />
              <Submit intent="repair">Add owner</Submit>
            </ConsoleForm>
          ) : null}
          <Refused intent="repair" />
        </details>
      ) : null}
      {owner && (shows("organization.rename") || shows("organization.delete")) ? (
        <details className="ac-more" open={renameRefusal !== undefined || deleteRefusal !== undefined || undefined}>
          <summary>Rename or delete</summary>
          {shows("organization.rename") ? (
            <ConsoleForm intent="rename" className="ac-inline">
              <Field label="New name" name="name" required defaultValue={renameRefusal?.values.name ?? o.name} />
              <Submit intent="rename">Rename</Submit>
            </ConsoleForm>
          ) : null}
          <Refused intent="rename" />
          {shows("organization.delete") ? (
            <div className="ac-danger-zone">
              <p>Deleting {o.name} is permanent, and its id can never be used again. It must have no projects.</p>
              <ConsoleForm intent="delete">
                <Submit intent="delete" danger>
                  Delete organization
                </Submit>
              </ConsoleForm>
              <Refused intent="delete" />
            </div>
          ) : null}
          <HostActions operation="organization.delete" entity={o} />
        </details>
      ) : null}
    </>
  );
  return (
    <Page inspector={inspector}>
      <h1>{o.name}</h1>
      <dl className="ac-card ac-facts">
        <dt>Id</dt>
        <dd>{o.id}</dd>
        <dt>Your role</dt>
        <dd>{o.role === "owner" ? "Owner" : o.role === "member" ? "Member" : "Administrator (not a member)"}</dd>
        <dt>State</dt>
        <dd>{o.disabled ? "Disabled: its services are suspended and every change is refused" : "Active"}</dd>
        <dt>Holds</dt>
        <dd>
          {o.usage.projects} projects, {o.usage.services} services, {o.usage.instances} instances
        </dd>
        {o.quota ? (
          <>
            <dt>Quota</dt>
            <dd>
              {limit(o.quota.projects, "projects")}, {limit(o.quota.services, "services")}, {limit(o.quota.instances, "instances")}
            </dd>
          </>
        ) : null}
      </dl>

      <section className="ac-card">
        <h2>Projects</h2>
        {projects.length === 0 ? (
          <p className="ac-empty">No projects yet. A project groups services and gives them a namespace of their own.</p>
        ) : (
          <div className="ac-table-wrap">
            <table className="ac-table">
              <caption className="ac-visually-hidden">Projects in {o.name}</caption>
              <thead>
                <tr>
                  <th scope="col">Name</th>
                  <th scope="col">Id</th>
                  <th scope="col" className="ac-num">
                    Services
                  </th>
                  <th scope="col">Registry</th>
                </tr>
              </thead>
              <tbody>
                {projects.map((p) => (
                  <tr key={p.id}>
                    <td>
                      <ConsoleLink to={`projects/${encodeURIComponent(p.id)}`}>{p.name}</ConsoleLink>
                    </td>
                    <td>{p.id}</td>
                    <td className="ac-num">{p.services}</td>
                    <td>{p.registry ? p.registry.server : "Public images only"}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>

      <section className="ac-card" aria-labelledby="offered">
        <h2 id="offered">Offered grants</h2>
        {offered.length === 0 ? (
          <p className="ac-empty">
            No other organization&apos;s project offers this organization&apos;s machines or services anything. A grant from another organization waits here until an owner
            accepts it.
          </p>
        ) : (
          <div className="ac-table-wrap">
            <table className="ac-table" aria-describedby="offered">
              <thead>
                <tr>
                  <th scope="col">From</th>
                  <th scope="col">Grantee</th>
                  <th scope="col">Target</th>
                  <th scope="col">State</th>
                  <th scope="col">Last change</th>
                  <th scope="col">
                    <span className="ac-visually-hidden">Actions</span>
                  </th>
                </tr>
              </thead>
              <tbody>
                {offered.map((g) => {
                  const last = g.changes[g.changes.length - 1];
                  return (
                    <tr key={g.id} data-offered={g.id} data-state={g.state}>
                      <td>
                        {g.grantingProject} <span className="ac-hint">of {g.grantingOrganization}</span>
                      </td>
                      <td>
                        <code>{g.grantee}</code>
                      </td>
                      <td>{targetText(g.target)}</td>
                      <td>{g.state}</td>
                      <td>
                        {last ? (
                          <>
                            {last.change}
                            {last.at ? ` ${when(last.at)}` : ""}
                            {last.by ? ` by ${last.by}` : ""}
                          </>
                        ) : (
                          "—"
                        )}
                      </td>
                      <td>
                        {owner && g.state === "pending" && shows("grant.answer") ? (
                          <div className="ac-ops">
                            <ConsoleForm intent="grant-accept" className="ac-inline">
                              <input type="hidden" name="grantId" value={g.id} />
                              <Submit intent="grant-accept" label={`Accept ${grantName(g)}`}>
                                Accept
                              </Submit>
                            </ConsoleForm>
                            <ConsoleForm intent="grant-decline" className="ac-inline">
                              <input type="hidden" name="grantId" value={g.id} />
                              <Submit intent="grant-decline" label={`Decline ${grantName(g)}`}>
                                Decline
                              </Submit>
                            </ConsoleForm>
                          </div>
                        ) : null}
                        {owner && g.state === "accepted" && shows("grant.relinquish") ? (
                          <ConsoleForm intent="grant-relinquish" className="ac-inline">
                            <input type="hidden" name="grantId" value={g.id} />
                            <Submit intent="grant-relinquish" danger label={`Relinquish ${grantName(g)}`}>
                              Relinquish
                            </Submit>
                          </ConsoleForm>
                        ) : null}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
        <Refused intent="grant-accept" />
        <Refused intent="grant-decline" />
        <Refused intent="grant-relinquish" />
      </section>

      <Panels kind="organization" entity={o} loaded={panels} />
    </Page>
  );
}

export const ErrorBoundary = ConsoleErrorBoundary;
