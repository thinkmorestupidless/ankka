/**
 * An organization's machines (feature 040): programs outside the installation that hold a grant —
 * an affiliate network reading a topic, say — each with a client id and a secret it trades for a
 * token. A new machine's secret is shown exactly once, as a deploy token's is: the registration
 * redirects (so a reload can never register twice), carrying the secret across in a sealed cookie
 * that this page reads and clears on its next render.
 */
import { redirect, useLoaderData, type ActionFunctionArgs, type LoaderFunctionArgs, type MetaFunction } from "react-router";
import { act, guard, organizationShell, pageData, text, useConsoleContext } from "../context.ts";
import { targetText } from "../ui/grants.ts";
import { ConsoleErrorBoundary } from "../ui/errors.tsx";
import { ConsoleForm, Field, Submit, useConsole, when } from "../ui/console.tsx";
import { Page, SectionTitle } from "../ui/shell.tsx";
import { Refused, useRefusal } from "../ui/refused.tsx";
import { HostActions } from "../extensions/render.tsx";

export const meta: MetaFunction = () => [{ title: "Machines · ankka" }];

export async function loader({ request, params, context }: LoaderFunctionArgs) {
  const ctx = useConsoleContext(context);
  const id = params.organizationId!;
  return guard(ctx, async () => {
    const [organization, machines, grants, projects, page] = await Promise.all([
      ctx.client.getOrganization(id),
      ctx.client.machines(id),
      ctx.client.organizationGrants(id),
      ctx.client.listProjects(),
      pageData(ctx),
    ]);
    const registered = await ctx.runtime.machineFlash.take(request, ctx.headers);
    // Shown from the one-time cookie alone: the listing is a projection and may not show the new machine yet.
    return {
      console: organizationShell(page, organization, projects, "machines", [{ label: "Machines" }]),
      organization,
      machines,
      grants,
      registered,
    };
  });
}

export async function action({ request, params, context }: ActionFunctionArgs) {
  const ctx = useConsoleContext(context);
  const id = params.organizationId!;
  const form = await request.formData();
  const intent = text(form, "intent");
  const self = ctx.href(`organizations/${encodeURIComponent(id)}/machines`);
  return act(ctx, intent, form, async () => {
    if (intent === "register") {
      const registered = await ctx.client.registerMachine(id, text(form, "machineName"));
      await ctx.runtime.machineFlash.write(registered, ctx.headers);
      return redirect(self);
    }
    if (intent === "delete") {
      await ctx.client.deleteMachine(id, text(form, "machineName"));
      return redirect(self);
    }
    if (intent === "byte-rates") {
      await ctx.client.setMachineByteRates(id, text(form, "rateMachine"), {
        produceBytesPerSecond: Number(text(form, "produceBytesPerSecond")),
        consumeBytesPerSecond: Number(text(form, "consumeBytesPerSecond")),
        requestPercentage: Number(text(form, "requestPercentage")),
      });
      return redirect(self);
    }
    throw new Response(`unknown operation '${intent}'`, { status: 400 });
  });
}

export default function Machines() {
  const { organization: o, machines, grants, registered } = useLoaderData<typeof loader>();
  const { shows, shell } = useConsole();
  const manages = shell.organization?.manages ?? false;
  const registerRefusal = useRefusal("register");
  const ratesRefusal = useRefusal("byte-rates");
  const inspector = manages ? (
    <>
      {shows("machine.register") ? (
        <section aria-labelledby="register-machine" className="ac-form">
          <SectionTitle>
            <span id="register-machine">Register a machine</span>
          </SectionTitle>
          <ConsoleForm intent="register" className="ac-inline">
            <Field
              label="Machine name"
              name="machineName"
              required
              placeholder="affiliate-network"
              hint="Lowercase letters, digits and '-'. A grant names it as machine:<organization>/<name>."
              defaultValue={registerRefusal?.values.machineName}
              autoComplete="off"
            />
            <Submit intent="register" primary>
              Register machine
            </Submit>
          </ConsoleForm>
          <Refused intent="register" />
          <div className="ac-ops">
            <HostActions operation="machine.register" entity={o} />
          </div>
        </section>
      ) : null}
      {shows("machine.byte-rates") && machines.length > 0 ? (
        <section aria-labelledby="byte-rates-title" className="ac-form">
          <SectionTitle>
            <span id="byte-rates-title">Byte rates</span>
          </SectionTitle>
          <details className="ac-more" open={ratesRefusal !== undefined || undefined}>
            <summary>Limit a machine on the broker</summary>
            <ConsoleForm intent="byte-rates" className="ac-form">
              <div className="ac-field">
                <label htmlFor="rateMachine">Machine</label>
                <select id="rateMachine" name="rateMachine" defaultValue={ratesRefusal?.values.rateMachine ?? machines[0]?.name}>
                  {machines.map((m) => (
                    <option key={m.name} value={m.name}>
                      {m.name}
                    </option>
                  ))}
                </select>
              </div>
              <Field
                label="Produce bytes a second"
                name="produceBytesPerSecond"
                type="number"
                required
                min={1}
                defaultValue={ratesRefusal?.values.produceBytesPerSecond}
              />
              <Field
                label="Consume bytes a second"
                name="consumeBytesPerSecond"
                type="number"
                required
                min={1}
                defaultValue={ratesRefusal?.values.consumeBytesPerSecond}
              />
              <Field
                label="Request percentage"
                name="requestPercentage"
                type="number"
                required
                min={1}
                max={100}
                hint="The share of a broker thread's time its requests may take, 1 to 100."
                defaultValue={ratesRefusal?.values.requestPercentage}
              />
              <Refused intent="byte-rates" />
              <div>
                <Submit intent="byte-rates">Set byte rates</Submit>
              </div>
            </ConsoleForm>
          </details>
        </section>
      ) : null}
    </>
  ) : null;
  return (
    <Page inspector={inspector}>
      <h1>Machines of {o.name}</h1>
      <p className="ac-lede">
        A machine is a program outside the installation that another project can grant a route, a method or a topic. It signs in with its client id and secret; owners
        register, limit and delete machines, and every member can see them.
      </p>

      {registered ? (
        <div className="ac-notice" role="status">
          <p>
            <strong>Copy the client secret for {registered.name} now.</strong> It is shown this once and cannot be retrieved later.
          </p>
          <dl className="ac-facts">
            <dt>Client id</dt>
            <dd>
              <code>{registered.clientId}</code>
            </dd>
            <dt>Token address</dt>
            <dd>
              <code>{registered.tokenUrl}</code>
            </dd>
            {registered.brokerBootstrap ? (
              <>
                <dt>Broker</dt>
                <dd>
                  <code>{registered.brokerBootstrap}</code>
                </dd>
              </>
            ) : null}
          </dl>
          <code className="ac-secret" data-secret>
            {registered.clientSecret}
          </code>
        </div>
      ) : null}

      <section className="ac-card" aria-label="Machines">
        {machines.length === 0 ? (
          <p className="ac-empty">No machines registered.</p>
        ) : (
          <div className="ac-table-wrap">
            <table className="ac-table">
              <caption className="ac-visually-hidden">Machines</caption>
              <thead>
                <tr>
                  <th scope="col">Name</th>
                  <th scope="col">Client id</th>
                  <th scope="col">Registered</th>
                  <th scope="col">Byte rates</th>
                  <th scope="col">
                    <span className="ac-visually-hidden">Actions</span>
                  </th>
                </tr>
              </thead>
              <tbody>
                {machines.map((m) => (
                  <tr key={m.name} data-machine={m.name}>
                    <td>{m.name}</td>
                    <td>
                      <code>{m.clientId}</code>
                    </td>
                    <td>
                      {m.registeredAt ? when(m.registeredAt) : "—"}
                      {m.registeredBy ? ` by ${m.registeredBy}` : ""}
                    </td>
                    <td data-byte-rates>
                      {m.byteRates
                        ? `produce ${m.byteRates.produceBytesPerSecond} B/s, consume ${m.byteRates.consumeBytesPerSecond} B/s, ${m.byteRates.requestPercentage}% of requests`
                        : "The installation's default"}
                    </td>
                    <td>
                      {manages && shows("machine.delete") ? (
                        <ConsoleForm intent="delete">
                          <input type="hidden" name="machineName" value={m.name} />
                          <Submit intent="delete" className="ac-button ac-button-small ac-button-danger" label={`Delete ${m.name}`}>
                            Delete
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
        <Refused intent="delete" />
      </section>

      <section className="ac-card" aria-labelledby="held">
        <h2 id="held">Grants held</h2>
        {grants.length === 0 ? (
          <p className="ac-empty">Nothing is granted to this organization's machines or services by another project.</p>
        ) : (
          <div className="ac-table-wrap">
            <table className="ac-table" aria-describedby="held">
              <thead>
                <tr>
                  <th scope="col">From</th>
                  <th scope="col">Grantee</th>
                  <th scope="col">Target</th>
                  <th scope="col">State</th>
                </tr>
              </thead>
              <tbody>
                {grants.map((g) => (
                  <tr key={g.id} data-held={g.id}>
                    <td>
                      {g.grantingProject} <span className="ac-hint">of {g.grantingOrganization}</span>
                    </td>
                    <td>
                      <code>{g.grantee}</code>
                    </td>
                    <td>{targetText(g.target)}</td>
                    <td>{g.state}</td>
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
