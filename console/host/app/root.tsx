import { Links, Meta, Outlet, Scripts, ScrollRestoration } from "react-router";
import { consoleMiddleware, consoleOptionsFromEnv } from "ankka-console/server";
import "ankka-console/styles.css";
import "./host.css";
import { extensions } from "./console.ts";

export const middleware = [consoleMiddleware(() => consoleOptionsFromEnv(process.env, extensions))];

export function Layout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en">
      <head>
        <meta charSet="utf-8" />
        <meta name="viewport" content="width=device-width, initial-scale=1" />
        <meta name="color-scheme" content="light dark" />
        <Meta />
        <Links />
      </head>
      <body>
        {children}
        <ScrollRestoration />
        <Scripts />
      </body>
    </html>
  );
}

export default function Root() {
  return <Outlet />;
}
