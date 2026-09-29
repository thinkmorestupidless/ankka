/**
 * Starts a sign-in: a PKCE verifier, a state and a nonce sealed into a short-lived cookie, and the
 * browser sent to the identity provider. The page itself is only ever seen when the identity
 * provider cannot be reached.
 */
import { redirect, type LoaderFunctionArgs, type MetaFunction } from "react-router";
import { failureResponse, safeReturnTo, useConsoleContext } from "../context.ts";
import { ConsoleErrorBoundary } from "../ui/errors.tsx";

export const meta: MetaFunction = () => [{ title: "Sign in · ankka" }];

export async function loader({ request, context }: LoaderFunctionArgs) {
  const ctx = useConsoleContext(context);
  const rt = ctx.runtime;
  if (!rt.issuer) throw new Response("This console signs people in elsewhere.", { status: 404 });
  const returnTo = safeReturnTo(rt, new URL(request.url).searchParams.get("returnTo"));
  try {
    const issuer = await rt.issuer();
    const began = await issuer.beginSignIn(new URL(ctx.href("auth/callback"), rt.options.publicOrigin).toString());
    await rt.pendingLogin.write({ state: began.state, nonce: began.nonce, verifier: began.verifier, returnTo }, ctx.headers);
    return redirect(began.url.toString());
  } catch (e) {
    throw failureResponse(ctx, e);
  }
}

export default function SignIn() {
  return null;
}

export const ErrorBoundary = ConsoleErrorBoundary;
