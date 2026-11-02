// A WebSocket client for the tests: connects through the Worker and queues the frames it receives.

import { exports } from "cloudflare:workers";
import { BASE } from "./client";

export interface TestSocket {
  ws: WebSocket;
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  received: any[];
  /** The next frame (of `type`, when given) not yet taken. */
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  next(type?: string, timeoutMs?: number): Promise<any>;
  /** Frames not yet taken, after waiting `ms`. */
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  drain(ms: number): Promise<any[]>;
  send(message: unknown): void;
  closed: Promise<{ code: number; reason: string }>;
}

export async function connect(groupId: string, pass: string, headers: Record<string, string> = {}): Promise<{ status: number; socket: TestSocket | null; response: Response }> {
  const response = await exports.default.fetch(
    new Request(`${BASE}/v1/groups/${groupId}/ws`, { headers: { Upgrade: "websocket", Authorization: `Bearer ${pass}`, ...headers } }),
  );
  const ws = response.webSocket;
  if (ws === null || ws === undefined) return { status: response.status, socket: null, response };
  ws.accept();
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const received: any[] = [];
  let taken = 0;
  let wake: (() => void) | null = null;
  let closedResolve!: (v: { code: number; reason: string }) => void;
  const closed = new Promise<{ code: number; reason: string }>((r) => (closedResolve = r));
  ws.addEventListener("message", (event) => {
    const data = typeof event.data === "string" ? event.data : "";
    received.push(data === "pong" ? "pong" : JSON.parse(data));
    wake?.();
  });
  ws.addEventListener("close", (event) => {
    closedResolve({ code: event.code, reason: event.reason });
    wake?.();
  });
  const socket: TestSocket = {
    ws,
    received,
    async next(type, timeoutMs = 5000) {
      const deadline = Date.now() + timeoutMs;
      for (;;) {
        for (let i = taken; i < received.length; i++) {
          const frame = received[i];
          if (type === undefined || frame === type || frame?.type === type) {
            received.splice(i, 1);
            received.splice(taken, 0, frame);
            taken += 1;
            return frame;
          }
        }
        const left = deadline - Date.now();
        if (left <= 0) throw new Error(`no ${type ?? "frame"} within ${timeoutMs} ms; got ${JSON.stringify(received.slice(taken))}`);
        await new Promise<void>((resolve) => {
          wake = resolve;
          setTimeout(resolve, Math.min(left, 50));
        });
        wake = null;
      }
    },
    async drain(ms) {
      await new Promise((r) => setTimeout(r, ms));
      const out = received.slice(taken);
      taken = received.length;
      return out;
    },
    send(message) {
      ws.send(typeof message === "string" ? message : JSON.stringify(message));
    },
    closed,
  };
  return { status: response.status, socket, response };
}
