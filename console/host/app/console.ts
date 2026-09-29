import type { ConsoleExtensions } from "ankka-console";

/**
 * This installation's console adds nothing to the package's pages. A product built on the package
 * registers its panels and actions here; the same object reaches the middleware (for panels' data)
 * and the layout (for rendering them).
 */
export const extensions: ConsoleExtensions = {};
