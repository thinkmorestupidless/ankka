/**
 * Signs out: the session cookie is cleared, this instance forgets the access token, and the
 * identity provider's session is ended server to server, so the next visit asks for a sign-in rather
 * than passing silently through single sign-on. Only a POST signs anyone out.
 */
import { redirect, useLoaderData, type ActionFunctionArgs, type LoaderFunctionArgs, type MetaFunction } from "react-router";
import { pageData, useConsoleContext } from "../context.ts";
import { ConsoleErrorBoundary } from "../ui/errors.tsx";
import { ConsoleForm, ConsoleLink, Submit } from "../ui/console.tsx";

export const meta: MetaFunction = () => [{ title: "Sign out · ankka" }];

export async function loader({ request, context }: LoaderFunctionArgs) {
  const ctx = useConsoleContext(context);
  const signedIn = (await ctx.runtime.store.read(request)) !== null;
  return { console: await pageData(ctx).catch(() => ({ mount: ctx.mount, principal: null, hidden: [] })), signedIn };
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
    <section className="ac-page">
      {signedIn ? (
        <>
          <h1>Sign out</h1>
          <ConsoleForm to="auth/sign-out">
            <Submit>Sign out</Submit>
          </ConsoleForm>
        </>
      ) : (
        <>
          <h1>You are signed out</h1>
          <p>Your session in this console and in the identity provider has ended.</p>
          <p>
            <ConsoleLink to="auth/sign-in" className="ac-button">
              Sign in
            </ConsoleLink>
          </p>
        </>
      )}
    </section>
  );
}

export const ErrorBoundary = ConsoleErrorBoundary;
