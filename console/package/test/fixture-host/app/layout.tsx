// docs:start layout
import { Outlet } from "react-router";
import { ConsoleProvider } from "ankka-console";
import { extensions } from "./extensions.tsx";

export default function ProductLayout() {
  return (
    <ConsoleProvider extensions={extensions}>
      <div className="product ac-root">
        <header data-product-chrome>A product built on ankka</header>
        <Outlet />
      </div>
    </ConsoleProvider>
  );
}
// docs:end layout
