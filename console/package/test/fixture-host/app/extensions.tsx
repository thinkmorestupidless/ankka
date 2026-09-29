// docs:start extensions
import type { ConsoleExtensions } from "ankka-console";

/**
 * What this host adds to the package's pages. The same object goes to the middleware (for each
 * panel's `load`, which runs on the server) and to the layout's provider (for rendering).
 */
export const extensions: ConsoleExtensions = {
  panels: {
    organization: [
      {
        id: "plan",
        title: "Plan",
        // Runs on the server with the page's own data; a failure here is shown in the panel's place.
        load: async (_context, organization) => {
          if (organization.id.startsWith("broken")) throw new Error("the billing service did not answer");
          return { plan: "Team", organization: organization.id };
        },
        Component: ({ data }) => <p data-plan>{(data as { plan: string } | undefined)?.plan ?? "No plan"}</p>,
      },
    ],
  },
  actions: {
    "organization.create": [{ id: "checkout", label: "Start a subscription", href: () => "/x/billing" }],
  },
  hidden: ["organization.delete"],
};
// docs:end extensions
