import { runConsoleServer } from "ankka-console/server";

await runConsoleServer({
  build: () => import(new URL("./build/server/index.js", import.meta.url).href),
  clientDir: new URL("./build/client/", import.meta.url),
});
