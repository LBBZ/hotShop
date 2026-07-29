import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

import {
  compareTrees,
  defaultOutputRoot,
  generateAll,
  generatedDomains,
} from "./api-generation.mjs";

const temporaryRoot = mkdtempSync(join(tmpdir(), "hotshop-api-drift-"));

try {
  generateAll(temporaryRoot);
  const differences = generatedDomains.flatMap((domain) =>
    compareTrees(join(defaultOutputRoot, domain), join(temporaryRoot, domain)),
  );
  if (differences.length > 0) {
    console.error("Generated API client drift detected:");
    for (const difference of differences) {
      console.error(`- ${difference}`);
    }
    console.error("Run `pnpm api:generate` and review the generated changes.");
    process.exitCode = 1;
  } else {
    console.log("Generated API clients match all three OpenAPI baselines.");
  }
} finally {
  rmSync(temporaryRoot, { recursive: true, force: true });
}
