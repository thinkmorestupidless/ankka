/**
 * A project: its services, kept current while the page is open, and its registry credential.
 */
import { redirect, useLoaderData, type ActionFunctionArgs, type LoaderFunctionArgs, type MetaFunction } from "react-router";
import { act, guard, pageData, text, useConsoleContext } from "../context.ts";
import { ConsoleErrorBoundary } from "../ui/errors.tsx";
import { Breadcrumbs, ConsoleForm, ConsoleLink, Field, Submit, useConsole, when } from "../ui/console.tsx";
import { Refused, useRefusal } from "../ui/refused.tsx";
import { Lifecycle } from "../ui/status.tsx";
import { useProjectStream } from "../ui/use-stream.ts";
import { HostActions, loadPanels, Panels } from "../extensions/render.tsx";

export const meta: MetaFunction<typeof loader> = ({ loaderData }) => [{ title: `${loaderData?.project.name ?? "Project"} · ankka` }];

export async function loader({ params, context }: LoaderFunctionArgs) {
  const ctx = useConsoleContext(context);
  const id = params.projectId!;
  return guard(ctx, async () => {
    const [project, services, page] = await Promise.all([ctx.client.getProject(id), ctx.client.listServices(id), pageData(ctx)]);
    const organization = await ctx.client.getOrganization(project.organizationId);
    return { console: page, project, organization, services, panels: await loadPanels(ctx, "project", project) };
  });
}

export async function action({ request, params, context }: ActionFunctionArgs) {
  const ctx = useConsoleContext(context);
  const id = params.projectId!;
  const form = await request.formData();
  const intent = text(form, "intent");
  const self = ctx.href(`projects/${encodeURIComponent(id)}`);
  return act(ctx, intent, form, async () => {
    switch (intent) {
      case "rename":
        await ctx.client.renameProject(id, text(form, "name"));
        return redirect(self);
      case "delete": {
        const project = await ctx.client.getProject(id);
        await ctx.client.deleteProject(id);
        return redirect(ctx.href(`organizations/${encodeURIComponent(project.organizationId)}`));
      }
      case "registry-set":
        // The password crosses once, to the control plane, and is never shown again by anything.
        await ctx.client.setRegistry(id, { server: text(form, "server"), username: text(form, "username"), password: String(form.get("password") ?? "") });
        return redirect(self);
      case "registry-clear":
        await ctx.client.clearRegistry(id);
        return redirect(self);
      default:
        throw new Response(`unknown operation '${intent}'`, { status: 400 });
    }
  });
}

export default function Project() {
  const { project: p, organization: o, services: initial, panels } = useLoaderData<typeof loader>();
  const { services, state } = useProjectStream(p.id, initial);
  const { shows } = useConsole();
  const renameRefusal = useRefusal("rename");
  const deleteRefusal = useRefusal("delete");
  const registryRefusal = useRefusal("registry-set");
  const path = `projects/${encodeURIComponent(p.id)}`;
  return (
    <section className="ac-page">
      <Breadcrumbs trail={[{ label: "Organizations", to: "" }, { label: o.name, to: `organizations/${encodeURIComponent(o.id)}` }, { label: p.name }]} />
      <h1>{p.name}</h1>
      <dl className="ac-facts">
        <dt>Id</dt>
        <dd>{p.id}</dd>
        <dt>Organization</dt>
        <dd>{o.name}</dd>
      </dl>

      <h2 id="services">Services</h2>
      {state === "live" ? <p className="ac-live">Updating as the platform reports</p> : null}
      {services.length === 0 ? (
        <p className="ac-empty">No services yet. Apply a descriptor to deploy one.</p>
      ) : (
        <div className="ac-table-wrap">
          <table className="ac-table" aria-describedby="services">
            <thead>
              <tr>
                <th scope="col">Service</th>
                <th scope="col">State</th>
                <th scope="col" className="ac-num">
                  Instances
                </th>
                <th scope="col">Image</th>
                <th scope="col" className="ac-num">
                  Generation
                </th>
                <th scope="col">Address</th>
              </tr>
            </thead>
            <tbody>
              {services.map((s) => (
                <tr key={s.name} data-service={s.name}>
                  <td>
                    <ConsoleLink to={`${path}/services/${encodeURIComponent(s.name)}`}>{s.name}</ConsoleLink>
                  </td>
                  <td>
                    <Lifecycle lifecycle={s.lifecycle} confirmed={s.confirmed} />
                  </td>
                  <td className="ac-num">
                    {s.readyInstances} of {s.desiredInstances}
                  </td>
                  <td>{s.image}</td>
                  <td className="ac-num">{s.generation}</td>
                  <td>{s.hostname ? <a href={s.hostname}>{s.hostname.replace(/^https:\/\//, "")}</a> : s.exposed ? "Exposed, no address yet" : "Not exposed"}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      <div className="ac-actions">
        {shows("service.apply") ? (
          <ConsoleLink to={`${path}/services/apply`} className="ac-button">
            Apply a descriptor
          </ConsoleLink>
        ) : null}
        <HostActions operation="service.apply" entity={p} />
      </div>

      <h2>Registry</h2>
      {p.registry ? (
        <p>
          Private images are pulled from <strong>{p.registry.server}</strong> as <strong>{p.registry.username}</strong>, set {when(p.registry.setAt)}
          {p.registry.setBy ? ` by ${p.registry.setBy}` : ""}.
        </p>
      ) : (
        <p>Services in this project pull only public images. Set a credential to pull private ones.</p>
      )}
      {shows("registry.set") ? (
        <details className="ac-more" open={registryRefusal !== undefined || undefined}>
          <summary>{p.registry ? "Replace the registry credential" : "Set a registry credential"}</summary>
          <ConsoleForm intent="registry-set" className="ac-form">
            <Field label="Registry server" name="server" required placeholder="ghcr.io" defaultValue={registryRefusal?.values.server ?? p.registry?.server} />
            <Field label="Username" name="username" required autoComplete="off" defaultValue={registryRefusal?.values.username ?? p.registry?.username} />
            <Field label="Password or token" name="password" type="password" required autoComplete="new-password" hint="Sent once to the platform, which keeps it only in the cluster. It is never shown again." />
            <Refused intent="registry-set" />
            <div>
              <Submit intent="registry-set">Save credential</Submit>
            </div>
          </ConsoleForm>
        </details>
      ) : null}
      {p.registry && shows("registry.clear") ? (
        <ConsoleForm intent="registry-clear">
          <Submit intent="registry-clear">Clear credential</Submit>
          <Refused intent="registry-clear" />
        </ConsoleForm>
      ) : null}

      <Panels kind="project" entity={p} loaded={panels} />

      {shows("project.rename") || shows("project.delete") ? (
        <details className="ac-more" open={renameRefusal !== undefined || deleteRefusal !== undefined || undefined}>
          <summary>Rename or delete</summary>
          {shows("project.rename") ? (
            <ConsoleForm intent="rename" className="ac-inline">
              <Field label="New name" name="name" required defaultValue={renameRefusal?.values.name ?? p.name} />
              <Submit intent="rename">Rename</Submit>
            </ConsoleForm>
          ) : null}
          <Refused intent="rename" />
          {shows("project.delete") ? (
            <div className="ac-danger-zone">
              <p>Deleting {p.name} is permanent, and its id can never be used again. It must have no services.</p>
              <ConsoleForm intent="delete">
                <Submit intent="delete" danger>
                  Delete project
                </Submit>
              </ConsoleForm>
              <Refused intent="delete" />
            </div>
          ) : null}
        </details>
      ) : null}
    </section>
  );
}

export const ErrorBoundary = ConsoleErrorBoundary;
