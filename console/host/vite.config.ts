import { reactRouter } from "@react-router/dev/vite";
import { defineConfig } from "vite";

// The host consumes ankka-console exactly as an npm consumer does, through its built dist/: the
// framework's route-config loader resolves `routes.ts` imports without this file's resolve options,
// so a source-only condition would work in the app and fail in the route config. `npm run dev` in
// the workspace root keeps dist/ rebuilt. The package is bundled rather than left external, because
// its route modules are part of the app.
export default defineConfig({
  plugins: [reactRouter()],
  ssr: { noExternal: ["ankka-console"] },
});
