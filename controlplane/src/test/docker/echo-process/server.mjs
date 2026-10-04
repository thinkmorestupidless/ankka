// A stand-in for a web-hosted service's process, for the cluster features (feature 021).
//
// It listens on PORT and answers with what it was given, so a test can read what the proxy passed
// on. Every answer names the pod in X-Instance. Nothing of the platform's is in it: it is the image
// a developer would bring.
import { createServer } from "node:http";

const port = Number(process.env.PORT ?? 8080);
const build = process.env.BUILD ?? "unknown";
const instance = process.env.HOSTNAME ?? "unknown";
const services = process.env.ANKKA_SERVICES_URL;
const silent = process.env.LISTEN === "none";

function answer(res, status, type, body) {
  res.writeHead(status, { "Content-Type": type, "X-Instance": instance });
  res.end(body);
}

const server = createServer(async (req, res) => {
  const url = new URL(req.url, "http://process");
  if (req.method === "GET" && url.pathname === "/") {
    answer(res, 200, "text/html", `<!doctype html><title>echo</title><p>build ${build}</p>`);
  } else if (url.pathname === "/echo") {
    answer(res, 200, "application/json", JSON.stringify({ method: req.method, path: req.url, headers: req.headers }));
  } else if (url.pathname === "/stream") {
    res.writeHead(200, { "Content-Type": "text/event-stream", "X-Instance": instance });
    for (let part = 1; part <= 3; part++) {
      res.write(`data: part ${part}\n\n`);
      if (part < 3) await new Promise((r) => setTimeout(r, 1000));
    }
    res.end();
  } else if (url.pathname === "/stall") {
    // Never answered.
  } else if (url.pathname === "/call") {
    try {
      const called = await fetch(`${services}${url.searchParams.get("path") ?? "/"}`);
      answer(res, 200, "application/json", JSON.stringify({ status: called.status, body: await called.text() }));
    } catch (e) {
      answer(res, 502, "application/json", JSON.stringify({ error: String(e) }));
    }
  } else if (req.method === "POST" && url.pathname === "/stop-listening") {
    answer(res, 200, "text/plain", "stopping");
    // Stop accepting, and stay alive: the process runs, and nothing is listening.
    server.close();
    setInterval(() => {}, 60_000);
  } else {
    answer(res, 404, "text/plain", "not found");
  }
});

if (silent) {
  console.log("listening for nothing");
  setInterval(() => {}, 60_000);
} else {
  server.listen(port, () => console.log("listening"));
}
