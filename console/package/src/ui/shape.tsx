/**
 * A deployed service's shape: what the platform runs for it, drawn as parts joined in the direction
 * traffic and data flow. Its hostname when it is exposed, the service itself with its own controls
 * on a tab, then what it reaches: its instances and its database, or, for a web-hosted service,
 * its instances and each mount, a path on its hostname routed to another service. Everything comes
 * from the service's status, so it is on the page without scripts and changes as the platform
 * reports with them.
 */
import type { ReactNode } from "react";
import { Box, Database, FileText, Globe, Layers, Pause, Play, RotateCw, Route } from "lucide-react";
import type { ServiceStatus } from "../client/schemas.ts";
import { ConsoleLink, OperationForm, useConsole } from "./console.tsx";
import { Lifecycle } from "./status.tsx";
import { hostedAs } from "./hosting.ts";

const icon = { strokeWidth: 1.25, "aria-hidden": true } as const;

/** The joins from one part to `to` parts side by side below it, each arriving at its part's centre. */
function Join({ to }: { to: number }) {
  if (to <= 1)
    return (
      <svg className="ac-edge" viewBox="0 0 100 48" preserveAspectRatio="none" aria-hidden="true">
        <path d="M50 0 V48" />
      </svg>
    );
  const xs = Array.from({ length: to }, (_x, i) => ((2 * i + 1) / (2 * to)) * 100);
  return (
    <svg className="ac-edge ac-edge-split" viewBox="0 0 100 48" preserveAspectRatio="none" aria-hidden="true">
      {xs.map((x) => (
        <path key={x} d={`M50 0 C50 28 ${x} 20 ${x} 48`} />
      ))}
    </svg>
  );
}

const mountTrouble: Record<string, string> = {
  "no service": "No service of that name",
  "serves no HTTP": "Serves no HTTP",
  paused: "Paused",
};

export function Shape({ service: s }: { service: ServiceStatus }) {
  const { shows } = useConsole();
  const path = `projects/${encodeURIComponent(s.projectId)}/services/${encodeURIComponent(s.name)}`;
  const address = s.hostname?.replace(/^https?:\/\//, "");
  const web = s.hosting === "web";
  const project = `projects/${encodeURIComponent(s.projectId)}/services/`;
  const children: ReactNode[] = [
    <div key="instances" className="ac-part ac-part-up" data-part="instances">
      <span className="ac-part-icon">
        <Layers {...icon} />
      </span>
      <span className="ac-part-title">Instances</span>
      <span className="ac-part-sub">
        {s.readyInstances} of {s.desiredInstances} ready
      </span>
    </div>,
    ...(web
      ? s.mounts.map((m) => (
          <div key={m.path} className="ac-part ac-part-up" data-part="mount" data-mount={m.path}>
            <span className="ac-part-icon">
              <Route {...icon} />
            </span>
            <span className="ac-part-title" title={m.service}>
              {m.state === "no service" ? m.service : <ConsoleLink to={project + encodeURIComponent(m.service)}>{m.service}</ConsoleLink>}
            </span>
            <span className="ac-part-sub" title={m.path}>
              {m.path}
            </span>
            {m.state && m.state !== "ok" ? (
              <span className="ac-part-inset">
                <span className="ac-status ac-status-trouble">
                  <span className="ac-status-mark" aria-hidden="true">
                    ▲
                  </span>
                  {mountTrouble[m.state] ?? m.state}
                </span>
              </span>
            ) : null}
          </div>
        ))
      : [
          <div key="database" className="ac-part ac-part-up" data-part="database">
            <span className="ac-part-icon">
              <Database {...icon} />
            </span>
            <span className="ac-part-title" title={s.database}>
              {s.database ?? "Not reported yet"}
            </span>
            <span className="ac-part-sub">{s.database ? "Kept if the service is deleted" : "The cluster has not reported it"}</span>
          </div>,
        ]),
  ];
  return (
    <figure className="ac-shape" aria-label={`What the platform runs for ${s.name}`}>
      <div className="ac-tree">
        {s.exposed ? (
          <>
            <div className="ac-row">
              <div className="ac-part ac-part-down" data-part="hostname">
                <span className="ac-part-icon">
                  <Globe {...icon} />
                </span>
                <span className="ac-part-title" title={address}>
                  {address ?? "No address yet"}
                </span>
                <span className="ac-part-sub">{address ? "On the installation's gateway" : "Exposed; no address reported yet"}</span>
              </div>
            </div>
            <Join to={1} />
          </>
        ) : null}
        <div className="ac-row">
          <div className={`ac-part ac-part-self ac-part-tabbed ac-part-down${s.exposed ? " ac-part-up" : ""}`} data-part="service">
            <span className="ac-part-tab" aria-label={`Controls of ${s.name}`} role="group">
              {shows("service.logs") ? (
                <ConsoleLink to={`${path}/logs`} className="ac-tab-control" aria-label="Logs" title="Logs">
                  <FileText aria-hidden="true" />
                </ConsoleLink>
              ) : null}
              {s.paused
                ? shows("service.resume") && (
                    <OperationForm intent="resume" className="ac-tab-control" label="Resume">
                      <Play aria-hidden="true" />
                    </OperationForm>
                  )
                : shows("service.pause") && (
                    <OperationForm intent="pause" className="ac-tab-control" label="Pause">
                      <Pause aria-hidden="true" />
                    </OperationForm>
                  )}
              {shows("service.restart") ? (
                <OperationForm intent="restart" className="ac-tab-control" label="Restart">
                  <RotateCw aria-hidden="true" />
                </OperationForm>
              ) : null}
            </span>
            <span className="ac-part-icon">
              <Box {...icon} />
            </span>
            <span className="ac-part-title">{s.name}</span>
            <span className="ac-part-sub" title={s.image}>
              Generation {s.generation}, {s.image}
            </span>
            <span className="ac-part-inset">
              <span>{hostedAs(s)}</span>
              <Lifecycle lifecycle={s.lifecycle} confirmed={s.confirmed} />
            </span>
          </div>
        </div>
        {children.length > 0 ? (
          <>
            <Join to={children.length} />
            <div className="ac-row ac-row-many" style={{ gridTemplateColumns: `repeat(${children.length}, minmax(0, 1fr))` }}>
              {children}
            </div>
          </>
        ) : null}
      </div>
    </figure>
  );
}
