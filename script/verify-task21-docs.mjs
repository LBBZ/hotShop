// Historical entrypoint retained for links in delivery evidence.
// Use `node script/check-docs.mjs` for the maintained, dependency-free check.
import { runCheck } from "./check-docs.mjs";

console.warn(
  "verify-task21-docs.mjs is deprecated; use script/check-docs.mjs.",
);
const result = await runCheck({
  output: "target/task21/document-check",
  // Supplying the original tools-directory argument retains Mermaid rendering.
  mermaidTools: process.argv[2],
});
if (result.failures.length) process.exitCode = 1;
