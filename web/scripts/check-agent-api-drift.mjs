import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

import {
  compareTrees,
  defaultOutputRoot,
  generateDomainForCheck,
} from "./api-generation.mjs";

const temporaryRoot = mkdtempSync(join(tmpdir(), "hotshop-agent-api-drift-"));

try {
  generateDomainForCheck("agent", temporaryRoot);
  const differences = compareTrees(
    join(defaultOutputRoot, "agent"),
    join(temporaryRoot, "agent"),
  );
  if (differences.length > 0) {
    console.error("Generated Agent API client drift detected:");
    for (const difference of differences) console.error(`- ${difference}`);
    process.exitCode = 1;
  } else {
    console.log(
      "Generated Agent API client matches its runtime OpenAPI baseline.",
    );
  }
} finally {
  rmSync(temporaryRoot, { recursive: true, force: true });
}
