// Records request/response examples for Tests/SatMapCoreTests/Fixtures/groups/api-examples.json (SPEC §13.1). The
// examples travel in the test's task meta to test/helpers/examples-reporter.ts, which writes the file.

import { api, type ApiResult } from "./client";

export interface ApiExample {
  id: string;
  method: string;
  path: string;
  request: unknown;
  status: number;
  response: unknown;
}

declare module "vitest" {
  interface TaskMeta {
    apiExamples?: ApiExample[];
  }
}

export class Recorder {
  readonly examples: ApiExample[] = [];

  constructor(task: { meta: { apiExamples?: ApiExample[] } }) {
    task.meta.apiExamples = this.examples;
  }

  add(example: ApiExample): void {
    if (this.examples.some((e) => e.id === example.id)) throw new Error(`example ${example.id} twice`);
    this.examples.push(example);
  }

  /** Sends a request through the Worker and records it. `request` overrides what is recorded as the body. */
  async http(
    id: string,
    method: string,
    path: string,
    opts: { pass?: string; body?: unknown; headers?: Record<string, string>; ip?: string; rawBody?: BodyInit; record?: unknown } = {},
  ): Promise<ApiResult> {
    const res = await api(method, path, opts);
    this.add({
      id,
      method,
      path,
      request: opts.record !== undefined ? opts.record : opts.body === undefined ? null : opts.body,
      status: res.status,
      response: res.body,
    });
    return res;
  }
}
