import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

import {
  compareTrees,
  defaultOutputRoot,
  generateAdmin,
} from "./api-generation.mjs";

const temporaryRoot = mkdtempSync(join(tmpdir(), "hotshop-admin-api-drift-"));

try {
  generateAdmin(temporaryRoot);
  const differences = compareTrees(
    join(defaultOutputRoot, "admin"),
    join(temporaryRoot, "admin"),
  );

  if (differences.length > 0) {
    console.error("Generated Admin API client drift detected:");
    for (const difference of differences) {
      console.error(`- ${difference}`);
    }
    console.error(
      "Run `pnpm api:generate:admin` and review the generated Admin client changes.",
    );
    process.exitCode = 1;
  } else {
    console.log("Generated Admin API client matches its OpenAPI baseline.");
  }
} finally {
  rmSync(temporaryRoot, { recursive: true, force: true });
}
