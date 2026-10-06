/**
 * An organization's members and the invitations waiting to be claimed. Everyone in it sees the
 * list; only owners see the controls. A machine — a deploy token — is a member like anyone else,
 * and is shown as the machine it is.
 */
import { redirect, useLoaderData, type ActionFunctionArgs, type LoaderFunctionArgs, type MetaFunction } from "react-router";
import { act, guard, organizationShell, pageData, text, useConsoleContext } from "../context.ts";
import { ConsoleErrorBoundary } from "../ui/errors.tsx";
import { ConsoleForm, Field, Submit, useConsole, when } from "../ui/console.tsx";
import { Page, SectionTitle } from "../ui/shell.tsx";
import { Select } from "../ui/primitives/select.tsx";
import { Refused, useRefusal } from "../ui/refused.tsx";
import type { Role } from "../client/schemas.ts";
import { HostActions } from "../extensions/render.tsx";

export const meta: MetaFunction = () => [{ title: "Members · ankka" }];

export async function loader({ params, context }: LoaderFunctionArgs) {
  const ctx = useConsoleContext(context);
  const id = params.organizationId!;
  return guard(ctx, async () => {
    const [organization, members, projects, page] = await Promise.all([ctx.client.getOrganization(id), ctx.client.members(id), ctx.client.listProjects(), pageData(ctx)]);
    return { console: organizationShell(page, organization, projects, "members", [{ label: "Members" }]), organization, ...members };
  });
}

const role = (form: FormData): Role => (text(form, "role") === "owner" ? "owner" : "member");

export async function action({ request, params, context }: ActionFunctionArgs) {
  const ctx = useConsoleContext(context);
  const id = params.organizationId!;
  const form = await request.formData();
  const intent = text(form, "intent");
  const self = ctx.href(`organizations/${encodeURIComponent(id)}/members`);
  return act(ctx, intent, form, async () => {
    switch (intent) {
      case "invite":
        await ctx.client.invite(id, text(form, "email"), role(form));
        return redirect(self);
      case "role":
        await ctx.client.changeRole(id, text(form, "subject"), role(form));
        return redirect(self);
      case "remove":
        await ctx.client.removeMember(id, text(form, "subject"));
        return redirect(self);
      case "withdraw":
        await ctx.client.withdrawInvitation(id, text(form, "email"));
        return redirect(self);
      default:
        throw new Response(`unknown operation '${intent}'`, { status: 400 });
    }
  });
}

const roleWord = (r: Role) => (r === "owner" ? "Owner" : "Member");

export default function Members() {
  const { organization: o, members, invitations, console: page } = useLoaderData<typeof loader>();
  const { shows } = useConsole();
  const owner = o.role === "owner" || (page.principal?.platformAdmin ?? false);
  const inviteRefusal = useRefusal("invite");
  const inspector =
    owner && shows("member.invite") ? (
      <section aria-labelledby="invite" className="ac-form">
        <SectionTitle>
          <span id="invite">Invite someone</span>
        </SectionTitle>
        <p className="ac-hint">They become a member the first time they sign in with this email address, once the identity provider has verified it.</p>
        <ConsoleForm intent="invite" className="ac-inline">
          <Field label="Email" name="email" type="email" required autoComplete="off" defaultValue={inviteRefusal?.values.email} />
          <Select label="Role" id="invite-role" name="role" defaultValue={inviteRefusal?.values.role ?? "member"}>
            <option value="member">Member</option>
            <option value="owner">Owner</option>
          </Select>
          <Submit intent="invite" primary>
            Invite
          </Submit>
        </ConsoleForm>
        <Refused intent="invite" />
        <div className="ac-ops">
          <HostActions operation="member.invite" entity={o} />
        </div>
      </section>
    ) : null;
  return (
    <Page inspector={inspector}>
      <h1>Members of {o.name}</h1>
      <p className="ac-lede">Owners manage members and deploy tokens; every member can deploy.</p>
      <div className="ac-card ac-table-wrap">
        <table className="ac-table">
          <caption className="ac-visually-hidden">Members</caption>
          <thead>
            <tr>
              <th scope="col">Who</th>
              <th scope="col">Role</th>
              <th scope="col">Since</th>
              <th scope="col">Added by</th>
              {owner ? <th scope="col">Change</th> : null}
            </tr>
          </thead>
          <tbody>
            {members.map((m) => {
              const machine = m.subject.startsWith("token:");
              return (
                <tr key={m.subject} data-member={m.subject}>
                  <td>
                    {machine ? "Deploy token " : null}
                    <strong>{m.display ?? m.email ?? m.subject}</strong>
                    {m.email && m.display ? <div className="ac-hint">{m.email}</div> : null}
                    <div className="ac-hint">{m.subject}</div>
                  </td>
                  <td>{roleWord(m.role)}</td>
                  <td>{when(m.since)}</td>
                  <td>{m.addedBy ?? "—"}</td>
                  {owner ? (
                    <td>
                      {machine ? (
                        <span className="ac-hint">Revoke it on the deploy tokens page</span>
                      ) : (
                        <div className="ac-actions">
                          {shows("member.role") ? (
                            <ConsoleForm intent="role">
                              <input type="hidden" name="subject" value={m.subject} />
                              <input type="hidden" name="role" value={m.role === "owner" ? "member" : "owner"} />
                              <Submit intent="role" className="ac-button ac-button-small">{m.role === "owner" ? "Make member" : "Make owner"}</Submit>
                            </ConsoleForm>
                          ) : null}
                          {shows("member.remove") ? (
                            <ConsoleForm intent="remove">
                              <input type="hidden" name="subject" value={m.subject} />
                              <Submit intent="remove" className="ac-button ac-button-small ac-button-danger">
                                Remove
                              </Submit>
                            </ConsoleForm>
                          ) : null}
                        </div>
                      )}
                    </td>
                  ) : null}
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
      <Refused intent="role" />
      <Refused intent="remove" />

      <section className="ac-card" aria-labelledby="invitations">
      <h2 id="invitations">Invitations</h2>
      {invitations.length === 0 ? (
        <p className="ac-empty">No invitations are waiting.</p>
      ) : (
        <div className="ac-table-wrap">
          <table className="ac-table">
            <caption className="ac-visually-hidden">Invitations waiting to be claimed</caption>
            <thead>
              <tr>
                <th scope="col">Email</th>
                <th scope="col">Role</th>
                <th scope="col">Invited</th>
                {owner ? <th scope="col">Change</th> : null}
              </tr>
            </thead>
            <tbody>
              {invitations.map((i) => (
                <tr key={i.email} data-invitation={i.email}>
                  <td>{i.email}</td>
                  <td>{roleWord(i.role)}</td>
                  <td>
                    {when(i.invitedAt)}
                    {i.invitedBy ? ` by ${i.invitedBy}` : ""}
                  </td>
                  {owner ? (
                    <td>
                      {shows("invitation.withdraw") ? (
                        <ConsoleForm intent="withdraw">
                          <input type="hidden" name="email" value={i.email} />
                          <Submit intent="withdraw" className="ac-button ac-button-small">Withdraw</Submit>
                        </ConsoleForm>
                      ) : null}
                    </td>
                  ) : null}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      <Refused intent="withdraw" />
      </section>
    </Page>
  );
}

export const ErrorBoundary = ConsoleErrorBoundary;
