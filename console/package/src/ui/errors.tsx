/**
 * What a page shows instead of itself when it cannot be shown: access lost, a refusal, something
 * unavailable. Every package route exports `ErrorBoundary` from here.
 */
import { isRouteErrorResponse, useRouteError } from "react-router";
import type { ConsoleFailure } from "../context.ts";
import { ConsoleLink } from "./console.tsx";
import { Page } from "./shell.tsx";
import { button } from "./primitives/button.ts";

/** A refusal from the control plane, shown beside the thing refused, in the control plane's words. */
export function Refusal({ reason, problems }: { reason?: string; problems?: string[] }) {
  if (!reason && (!problems || problems.length === 0)) return null;
  return (
    <div className="ac-refusal" role="alert">
      {problems && problems.length > 1 ? (
        <>
          <p>The control plane refused this:</p>
          <ul>
            {problems.map((p) => (
              <li key={p}>{p}</li>
            ))}
          </ul>
        </>
      ) : (
        <p>{problems?.[0] ?? reason}</p>
      )}
    </div>
  );
}

export function ConsoleErrorBoundary() {
  const error = useRouteError();
  if (isRouteErrorResponse(error) && error.data && typeof error.data === "object" && "kind" in error.data) {
    const failure = error.data as ConsoleFailure;
    if (failure.kind === "lost") {
      return (
        <Page>
          <section className="ac-card ac-failure">
            <h1>You no longer have access to this</h1>
            <p>It was deleted, or you are no longer a member of the organization it belongs to.</p>
            <p>
              <ConsoleLink to="">Go to your organizations</ConsoleLink>
            </p>
          </section>
        </Page>
      );
    }
    if (failure.kind === "unavailable") {
      return (
        <Page>
          <section className="ac-card ac-failure">
            <h1>The {failure.what} is not answering</h1>
            <p>Your session is kept. Try again in a few seconds.</p>
            <p className="ac-hint">{failure.reason}</p>
            <p>
              <a className={button()} href="">
                Try again
              </a>
            </p>
          </section>
        </Page>
      );
    }
    return (
      <Page>
        <section className="ac-card ac-failure">
          <h1>This was refused</h1>
          <Refusal reason={failure.reason} />
          <p>
            <ConsoleLink to="">Go to your organizations</ConsoleLink>
          </p>
        </section>
      </Page>
    );
  }
  if (isRouteErrorResponse(error) && error.status === 404) {
    return (
      <Page>
        <section className="ac-card ac-failure">
          <h1>There is no page here</h1>
          <p>
            <ConsoleLink to="">Go to your organizations</ConsoleLink>
          </p>
        </section>
      </Page>
    );
  }
  return (
    <Page>
      <section className="ac-card ac-failure">
        <h1>The console could not show this page</h1>
        <p>Reload to try again. If it keeps happening, the console's own log says why.</p>
      </section>
    </Page>
  );
}
