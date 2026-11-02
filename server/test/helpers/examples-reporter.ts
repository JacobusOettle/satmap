// Writes Tests/SatMapCoreTests/Fixtures/groups/api-examples.json (SPEC §13.1) from the examples the integration
// tests record in their task meta (test/helpers/examples.ts). Tests run inside workerd and can't write files, so
// the examples travel to this reporter, which runs in Node. Nothing is written unless every test that recorded an
// example passed.

import { writeFileSync } from "node:fs";
import type { Reporter, TestModule } from "vitest/node";

interface Example {
  id: string;
  method: string;
  path: string;
  request: unknown;
  status: number;
  response: unknown;
}

const OUT = new URL("../../../Tests/SatMapCoreTests/Fixtures/groups/api-examples.json", import.meta.url);

export default class ExamplesReporter implements Reporter {
  onTestRunEnd(modules: ReadonlyArray<TestModule>): void {
    const examples = new Map<string, Example>();
    let failed = false;
    for (const module of modules) {
      for (const test of module.children.allTests()) {
        const recorded = (test.meta() as { apiExamples?: Example[] }).apiExamples;
        if (recorded === undefined) continue;
        if (test.result().state !== "passed") failed = true;
        for (const example of recorded) {
          if (examples.has(example.id)) throw new Error(`api example ${example.id} recorded twice`);
          examples.set(example.id, example);
        }
      }
    }
    if (failed || examples.size === 0) return;
    const sorted = [...examples.values()].sort((a, b) => compareIds(a.id, b.id));
    writeFileSync(OUT, `${JSON.stringify({ examples: sorted }, null, 2)}\n`);
  }
}

/** E1-… before E2-…, E10 after E9, then A1…, then WS: by the letter, the number, then the rest of the id. */
function compareIds(a: string, b: string): number {
  const pa = /^([A-Z]+)(\d+)?(.*)$/.exec(a)!;
  const pb = /^([A-Z]+)(\d+)?(.*)$/.exec(b)!;
  const order = (letter: string) => ["E", "A", "W", "WS"].indexOf(letter);
  if (pa[1] !== pb[1]) return order(pa[1]!) - order(pb[1]!);
  const na = Number(pa[2] ?? 0);
  const nb = Number(pb[2] ?? 0);
  if (na !== nb) return na - nb;
  return pa[3]! < pb[3]! ? -1 : pa[3]! > pb[3]! ? 1 : 0;
}
