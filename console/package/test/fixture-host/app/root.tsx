import { Links, Meta, Outlet, Scripts } from "react-router";
import { consoleMiddleware } from "ankka-console/server";
import "ankka-console/styles.css";
import "./fixture.css";
import { extensions } from "./extensions.tsx";
import { MemorySessionStore } from "./session.ts";

const env = process.env;

// docs:start middleware
export const middleware = [
  consoleMiddleware(() => ({
    controlPlane: { url: env.FIXTURE_CONTROL_PLANE_URL! },
    auth: { clientId: "ankka-console", clientSecret: "dev", allowInsecure: true },
    publicOrigin: env.FIXTURE_PUBLIC_ORIGIN!,
    mount: "/x",
    // The host keeps sessions its own way; the package's sign-in writes through it.
    session: new MemorySessionStore(),
    sessionSecret: "fixture-secret-for-one-time-values",
    extensions,
  })),
];
// docs:end middleware

export function Layout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en">
      <head>
        <meta charSet="utf-8" />
        <Meta />
        <Links />
      </head>
      <body>
        {children}
        <Scripts />
      </body>
    </html>
  );
}

export default function Root() {
  return <Outlet />;
}
