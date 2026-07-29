import { generateAll } from "./api-generation.mjs";

generateAll();
console.log(
  "Generated public, user, and admin TypeScript clients from the checked-in OpenAPI baselines.",
);
