/**
 * Finishes a sign-in. The callback is accepted only with the state this browser was given, the
 * identity token is checked for issuer, audience, nonce, signature and expiry, and only the refresh
 * token is kept — sealed in a cookie script cannot read.
 */
import { data, redirect, type LoaderFunctionArgs, type MetaFunction } from "react-router";
import { failureResponse, useConsoleContext, type ConsoleFailure } from "../context.ts";
import { IdentityProviderUnavailable } from "../auth/oidc.ts";
import { ConsoleErrorBoundary } from "../ui/errors.tsx";

export const meta: MetaFunction = () => [{ title: "Signing in · ankka" }];

const startAgain = (reason: string) => data<ConsoleFailure>({ kind: "refused", status: 400, reason }, { status: 400 });

export async function loader({ request, context }: LoaderFunctionArgs) {
  const ctx = useConsoleContext(context);
  const rt = ctx.runtime;
  if (!rt.issuer || !rt.sessionTokens) throw new Response("This console signs people in elsewhere.", { status: 404 });

  const pending = await rt.pendingLogin.take(request, ctx.headers);
  if (!pending) throw startAgain("This sign-in was not started here, or took longer than ten minutes. Sign in again.");

  const incoming = new URL(request.url);
  if (incoming.searchParams.get("error")) {
    throw startAgain(`The identity provider did not sign you in: ${incoming.searchParams.get("error_description") ?? incoming.searchParams.get("error")}.`);
  }
  // The address the identity provider was told to return to, whatever this request's own URL looks
  // like behind the gateway.
  const callback = new URL(ctx.href("auth/callback") + incoming.search, rt.options.publicOrigin);
  try {
    const issuer = await rt.issuer();
    const tokens = await issuer.finishSignIn(callback, pending);
    await rt.store.write({ refreshToken: tokens.refreshToken, issuedAt: Math.floor(Date.now() / 1000) }, ctx.headers);
    rt.sessionTokens.remember(tokens);
    return redirect(pending.returnTo);
  } catch (e) {
    if (e instanceof IdentityProviderUnavailable) throw failureResponse(ctx, e);
    throw startAgain("This sign-in could not be completed. Sign in again.");
  }
}

export default function Callback() {
  return null;
}

export const ErrorBoundary = ConsoleErrorBoundary;
