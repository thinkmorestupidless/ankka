/**
 * `GET <mount>/projects/:projectId/erasures/:requestId/certificate`: an applied erasure request's
 * erasure certificate, as a JSON file to keep. Read as the person; refused as the control plane
 * refuses it (409 before the request is applied).
 */
import type { LoaderFunctionArgs } from "react-router";
import { guard, useConsoleContext } from "../context.ts";

export async function loader({ params, context }: LoaderFunctionArgs) {
  const ctx = useConsoleContext(context);
  const { projectId, requestId } = params as { projectId: string; requestId: string };
  return guard(ctx, async () => {
    const certificate = await ctx.client.erasureCertificate(projectId, requestId);
    const file = `erasure-certificate-${requestId.replace(/[^A-Za-z0-9_-]/g, "")}.json`;
    return new Response(JSON.stringify(certificate, null, 2) + "\n", {
      headers: { "content-type": "application/json; charset=utf-8", "content-disposition": `attachment; filename="${file}"`, "cache-control": "no-store" },
    });
  });
}
