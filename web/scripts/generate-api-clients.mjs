import { generateAll } from "./api-generation.mjs";

generateAll();
console.log(
  "Generated public, user, admin, and Agent TypeScript clients from the checked-in OpenAPI baselines.",
);
