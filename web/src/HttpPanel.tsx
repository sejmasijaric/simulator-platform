import { useState } from 'react';
import type { NetDefinition, Parameter, Route } from './types';
import { modulesOf, moduleOfRoute, render } from './net';
import { useFrame } from './hooks';

interface Exchange {
  method: string;
  path: string;
  started: number;
  ended?: number;
  status?: number;
  statusText?: string;
  contentType?: string | null;
  body?: string;
  error?: string;
}

/** The address an application uses: the simulator itself, also while the UI runs on the Vite dev server. */
function simulatorHost(): string {
  return window.location.port === '5173' ? `${window.location.hostname}:8080` : window.location.host;
}

function pretty(body: string | undefined): string {
  if (!body) return '';
  try {
    return JSON.stringify(JSON.parse(body), null, 2);
  } catch {
    return body;
  }
}

/**
 * The layout's own HTTP API, as an application sees it. Requests go to the real endpoints of the
 * simulator, so the answer (status, body, how long it takes) is exactly what an application gets.
 */
export function HttpPanel({ net }: { net: NetDefinition }) {
  const [selected, setSelected] = useState<string | null>(null);
  const [values, setValues] = useState<Record<string, Record<string, string>>>({});
  const [exchanges, setExchanges] = useState<Record<string, Exchange>>({});
  const [openGroups, setOpenGroups] = useState<string[]>([]);
  useFrame(Object.values(exchanges).some((e) => e.ended === undefined));

  const route = net.http.routes.find((r) => r.name === selected);

  if (route) {
    const current = values[route.name] ?? Object.fromEntries(route.parameters.map((p) => [p.name, initialValue(p)]));
    const setValue = (name: string, value: string) => setValues({ ...values, [route.name]: { ...current, [name]: value } });
    const query = route.parameters.filter((p) => (current[p.name] ?? '') !== '')
      .map((p) => `${encodeURIComponent(p.name)}=${encodeURIComponent(current[p.name])}`).join('&');
    const path = route.path + (query ? `?${query}` : '');
    const method = route.method.toUpperCase();
    const exchange = exchanges[route.name];

    const send = async () => {
      const started = performance.now();
      setExchanges((all) => ({ ...all, [route.name]: { method, path, started } }));
      let done: Exchange;
      try {
        const response = await fetch(path, { method });
        const body = await response.text();
        done = { method, path, started, ended: performance.now(), status: response.status, statusText: response.statusText,
          contentType: response.headers.get('Content-Type'), body };
      } catch (e) {
        done = { method, path, started, ended: performance.now(), error: (e as Error).message };
      }
      setExchanges((all) => ({ ...all, [route.name]: done }));
    };

    return (
      <div className="tab-body http">
        <button className="ghost back" onClick={() => setSelected(null)}>← All endpoints</button>
        <div className="endpoint-title">
          <span className="method">{method}</span> <span className="mono">{route.path}</span>
        </div>
        <p className="small muted" style={{ margin: '4px 0 12px' }}>
          {route.kind === 'COMMAND'
            ? <>Command. The simulator puts a request token into <span className="mono">{route.command}</span> and answers once <span className="mono">{route.returnsAfter.join(' or ')}</span> has finished for it, like the real machine answers when it is done.</>
            : route.kind === 'SETTING'
              ? <>Setting. Changes <span className="mono">{[...Object.keys(route.sets).map((name) => render(name, current) ?? name), ...route.resets].join(', ')}</span> right away
                  (clamped to −512…512 like the controller). Steps driven by that motor take longer or shorter from now on.</>
              : <>Query. Reads <span className="mono">{route.reads}</span> right now; nothing moves.</>}
        </p>

        {route.parameters.length > 0 && (
          <div className="params">
            {route.parameters.map((p) => (
              <label key={p.name} className="param">
                <span className="mono small">{p.name}{p.required ? <span className="req">*</span> : ''}</span>
                <input list={`values-${route.name}-${p.name}`} value={current[p.name] ?? ''} placeholder={p.defaultValue ?? ''}
                       onChange={(e) => setValue(p.name, e.target.value)} />
                <datalist id={`values-${route.name}-${p.name}`}>
                  {p.values.map((v) => <option key={v} value={v} />)}
                </datalist>
                <span className="hint-text">
                  {p.values.length > 0 ? `one of ${p.values.join(', ')}` : p.integer ? 'a whole number' : 'any text'}
                  {p.defaultValue !== null ? ` · default ${p.defaultValue}` : ''}
                </span>
              </label>
            ))}
            <div className="tiny muted">Try a wrong or missing value to see the factory's error answer.</div>
          </div>
        )}

        <div className="block-title" style={{ marginTop: 14 }}>Request</div>
        <pre className="wire">{`${method} ${path} HTTP/1.1\nHost: ${simulatorHost()}\nAccept: application/json`}</pre>
        <div className="tiny muted">Body: none ({method} request, parameters go in the URL)</div>
        <div className="row" style={{ marginTop: 10 }}>
          <button className="primary" onClick={send} disabled={exchange !== undefined && exchange.ended === undefined}>Send</button>
          <button className="ghost small" onClick={() => navigator.clipboard?.writeText(`curl -i "http://${simulatorHost()}${path}"`)}>Copy as curl</button>
        </div>

        {exchange && <ResponseView exchange={exchange} />}
      </div>
    );
  }

  const groups = new Map<string, Route[]>();
  for (const r of net.http.routes) {
    const key = moduleOfRoute(net, r) ?? 'other';
    groups.set(key, [...(groups.get(key) ?? []), r]);
  }
  const order = [...modulesOf(net), 'other'].filter((m) => groups.has(m));

  return (
    <div className="tab-body http">
      <p className="small muted" style={{ marginTop: 0 }}>
        The factory's own HTTP API, served by the simulator on <span className="mono">{simulatorHost()}</span>.
        Requests sent here behave exactly like your application's and go to the most recently started running simulation.
      </p>
      {order.map((module) => (
        <details key={module} className="group" open={openGroups.includes(module)}
                 onToggle={(e) => {
                   const isOpen = (e.currentTarget as HTMLDetailsElement).open;
                   setOpenGroups((current) => isOpen ? [...new Set([...current, module])] : current.filter((m) => m !== module));
                 }}>
          <summary className="group-title">
            {module === 'other' ? 'Other' : module.toUpperCase()}
            <span className="muted"> · {groups.get(module)!.length} endpoint{groups.get(module)!.length === 1 ? '' : 's'}</span>
          </summary>
          {groups.get(module)!.map((r) => {
            const exchange = exchanges[r.name];
            return (
              <button key={r.name} className="endpoint" onClick={() => setSelected(r.name)}>
                <span className="method">{r.method.toUpperCase()}</span>
                <span className="mono path">{r.path}</span>
                {exchange && <StatusBadge exchange={exchange} />}
                <span className={`kind-tag ${r.kind.toLowerCase()}`}>{r.kind.toLowerCase()}</span>
              </button>
            );
          })}
        </details>
      ))}
    </div>
  );
}

function initialValue(parameter: Parameter): string {
  if (parameter.defaultValue !== null) return parameter.defaultValue;
  return parameter.values.length > 0 ? parameter.values[0] : '';
}

function StatusBadge({ exchange }: { exchange: Exchange }) {
  if (exchange.ended === undefined) return <span className="status pending">…</span>;
  if (exchange.status === undefined) return <span className="status s5">error</span>;
  return <span className={`status s${String(exchange.status)[0]}`}>{exchange.status}</span>;
}

function ResponseView({ exchange }: { exchange: Exchange }) {
  const elapsed = ((exchange.ended ?? performance.now()) - exchange.started) / 1000;
  return (
    <>
      <div className="block-title" style={{ marginTop: 18 }}>
        Response <span className="muted tiny">· {elapsed.toFixed(1)} s</span>
      </div>
      {exchange.ended === undefined && (
        <div className="waiting">
          <span className="spinner" /> Waiting for the factory… The answer comes when the machine has finished.
          If time is paused, press Continue.
        </div>
      )}
      {exchange.error && <div className="notice">The request did not reach the simulator: {exchange.error}</div>}
      {exchange.status !== undefined && (
        <>
          <div className="row" style={{ marginBottom: 6 }}>
            <StatusBadge exchange={exchange} />
            <span className="small">{exchange.statusText || describe(exchange.status)}</span>
          </div>
          <pre className="wire">{`HTTP/1.1 ${exchange.status} ${exchange.statusText || describe(exchange.status)}${exchange.contentType ? `\nContent-Type: ${exchange.contentType}` : ''}`}</pre>
          <div className="tiny muted" style={{ margin: '8px 0 4px' }}>Body</div>
          {exchange.body ? <pre className="wire body">{pretty(exchange.body)}</pre> : <div className="tiny muted">empty</div>}
        </>
      )}
    </>
  );
}

function describe(status: number): string {
  return ({ 200: 'OK', 400: 'Bad Request', 404: 'Not Found', 500: 'Internal Server Error', 503: 'Service Unavailable', 504: 'Gateway Timeout' } as Record<number, string>)[status] ?? '';
}
