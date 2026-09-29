import { Outlet } from "react-router";
import { ConsoleForm, ConsoleLink, ConsoleProvider, useConsole } from "ankka-console";
import { extensions } from "./console.ts";

function Bar() {
  const { principal } = useConsole();
  return (
    <header className="ac-bar">
      <ConsoleLink to="" className="ac-wordmark">
        ankka
      </ConsoleLink>
      <span className="ac-wordmark-sub">console</span>
      {principal ? (
        <div className="ac-bar-person">
          <span>{principal.name ?? principal.email ?? principal.subject}</span>
          <ConsoleForm to="auth/sign-out">
            <button type="submit" className="ac-button ac-button-quiet">
              Sign out
            </button>
          </ConsoleForm>
        </div>
      ) : null}
    </header>
  );
}

export default function Layout() {
  return (
    <ConsoleProvider extensions={extensions}>
      <div className="ac-root">
        <Bar />
        <main className="ac-main">
          <Outlet />
        </main>
      </div>
    </ConsoleProvider>
  );
}
