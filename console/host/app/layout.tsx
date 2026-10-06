import { Outlet } from "react-router";
import { Backdrop, Bar, ConsoleProvider, Listing, Rail, Shell } from "ankka-console";
import { extensions } from "./console.ts";

/** The installation's console: every part of the shell, around every page of the package. */
export default function Layout() {
  return (
    <ConsoleProvider extensions={extensions}>
      <Shell>
        <Backdrop />
        <Rail />
        <Bar />
        <Listing />
        <Outlet />
      </Shell>
    </ConsoleProvider>
  );
}
