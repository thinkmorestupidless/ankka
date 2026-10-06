// docs:start layout
import { Outlet } from "react-router";
import { Backdrop, Bar, ConsoleProvider, Shell } from "ankka-console";
import { extensions } from "./extensions.tsx";

/**
 * A product's layout: the console's backdrop and bar around every page, its own pages and the
 * package's alike. It has no rail, listing or inspector of its own; the package's pages bring their
 * inspector with them. It chooses the light theme.
 */
export default function ProductLayout() {
  return (
    <ConsoleProvider extensions={extensions}>
      <Shell className="product" theme="light">
        <Backdrop />
        <Bar wordmark={<span data-product-chrome>A product built on ankka</span>} />
        <Outlet />
      </Shell>
    </ConsoleProvider>
  );
}
// docs:end layout
