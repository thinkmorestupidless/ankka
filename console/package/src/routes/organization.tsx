/**
 * An organization: its projects, and what its owners and the platform administrator may do to it.
 */
import { redirect, useLoaderData, type ActionFunctionArgs, type LoaderFunctionArgs, type MetaFunction } from "react-router";
import { act, guard, pageData, text, useConsoleContext } from "../context.ts";
import { ConsoleErrorBoundary } from "../ui/errors.tsx";
import { Breadcrumbs, ConsoleForm, ConsoleLink, Field, Submit, useConsole } from "../ui/console.tsx";
import { Refused, useRefusal } from "../ui/refused.tsx";
import { HostActions, loadPanels, Panels } from "../extensions/render.tsx";
import type { Quota } from "../client/schemas.ts";

export const meta: MetaFunction<typeof loader> = ({ loaderData }) => [{ title: `${loaderData?.organization.name ?? "Organization"} · ankka` }];

export async function loader({ params, context }: LoaderFunctionArgs) {
  const ctx = useConsoleContext(context);
  const id = params.organizationId!;
  return guard(ctx, async () => {
    const [organization, projects, page] = await Promise.all([ctx.client.getOrganization(id), ctx.client.listProjects(), pageData(ctx)]);
    // An organization whose owners have all left can be given one by an administrator.
    const ownerless = page.principal?.platformAdmin ? !(await ctx.client.members(id)).members.some((m) => m.role === "owner") : false;
    return {
      console: page,
      organization,
      projects: projects.filter((p) => p.organizationId === id),
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
      default:
        throw new Response(`unknown operation '${intent}'`, { status: 400 });
    }
  });
}

const limit = (n: number | undefined, what: string) => (n === undefined ? `any number of ${what}` : `${n} ${what}`);

export default function Organization() {
  const { organization: o, projects, ownerless, panels, console: page } = useLoaderData<typeof loader>();
  const { shows } = useConsole();
  const admin = page.principal?.platformAdmin ?? false;
  const owner = o.role === "owner" || admin;
  const renameRefusal = useRefusal("rename");
  const deleteRefusal = useRefusal("delete");
  const adminRefused = [useRefusal("disable"), useRefusal("enable"), useRefusal("quota-set"), useRefusal("quota-clear"), useRefusal("repair")].some(Boolean);
  const path = `organizations/${encodeURIComponent(o.id)}`;
  return (
    <section className="ac-page">
      <Breadcrumbs trail={[{ label: "Organizations", to: "" }, { label: o.name }]} />
      <h1>{o.name}</h1>
      <dl className="ac-facts">
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

      <nav aria-label="Organization" className="ac-actions">
        <ConsoleLink to={`${path}/members`}>Members</ConsoleLink>
        {o.role === "owner" || admin ? <ConsoleLink to={`${path}/tokens`}>Deploy tokens</ConsoleLink> : null}
      </nav>

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
      <div className="ac-actions">
        {shows("project.create") ? (
          <ConsoleLink to={`${path}/projects/new`} className="ac-button">
            Create a project
          </ConsoleLink>
        ) : null}
        <HostActions operation="project.create" entity={o} />
      </div>

      <Panels kind="organization" entity={o} loaded={panels} />

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

      {admin ? (
        <details className="ac-more" open={adminRefused || undefined}>
          <summary>Platform administration</summary>
          <div className="ac-actions">
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
    </section>
  );
}

export const ErrorBoundary = ConsoleErrorBoundary;
