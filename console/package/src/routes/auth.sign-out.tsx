/**
 * Signs out: the session cookie is cleared, this instance forgets the access token, and the
 * identity provider's session is ended server to server, so the next visit asks for a sign-in rather
 * than passing silently through single sign-on. Only a POST signs anyone out.
 */
import { redirect, useLoaderData, type ActionFunctionArgs, type LoaderFunctionArgs, type MetaFunction } from "react-router";
import { pageData, useConsoleContext, withShell } from "../context.ts";
import { ConsoleErrorBoundary } from "../ui/errors.tsx";
import { ConsoleForm, ConsoleLink, Submit } from "../ui/console.tsx";
import { Page } from "../ui/shell.tsx";
import { button } from "../ui/primitives/button.ts";

export const meta: MetaFunction = () => [{ title: "Sign out · ankka" }];

export async function loader({ request, context }: LoaderFunctionArgs) {
  const ctx = useConsoleContext(context);
  const signedIn = (await ctx.runtime.store.read(request)) !== null;
  const page = await pageData(ctx)
    .then((p) => withShell(p, { area: "organizations", crumbs: [{ label: "Organizations", to: "" }, { label: "Sign out" }] }))
    .catch(() => ({ mount: ctx.mount, principal: null, hidden: [] }));
  return { console: page, signedIn };
}

export async function action({ request, context }: ActionFunctionArgs) {
  const ctx = useConsoleContext(context);
  const rt = ctx.runtime;
  const session = await rt.store.read(request);
  await rt.store.clear(ctx.headers);
  if (session) {
    rt.sessionTokens?.forget(session.refreshToken);
    // A session that cannot be ended now ends on its own at the realm's idle timeout; the browser is
    // signed out of the console either way.
    await rt
      .issuer?.()
      .then((issuer) => issuer.endSession(session.refreshToken))
      .catch((e) => rt.log({ at: new Date().toISOString(), event: "sign-out", error: e instanceof Error ? e.message : String(e) }));
  }
  return redirect(ctx.href("auth/sign-out"));
}

export default function SignOut() {
  const { signedIn } = useLoaderData<typeof loader>();
  return (
    <Page>
      {signedIn ? (
        <>
          <h1>Sign out</h1>
          <ConsoleForm to="auth/sign-out" className="ac-card">
            <p>Sign out of this console and of the identity provider.</p>
            <div>
              <Submit primary>Sign out</Submit>
            </div>
          </ConsoleForm>
        </>
      ) : (
        <>
          <h1>You are signed out</h1>
          <p>Your session in this console and in the identity provider has ended.</p>
          <p>
            <ConsoleLink to="auth/sign-in" className={button({ variant: "primary" })}>
              Sign in
            </ConsoleLink>
          </p>
        </>
      )}
    </Page>
  );
}

export const ErrorBoundary = ConsoleErrorBoundary;
