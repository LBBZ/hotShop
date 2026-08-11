import { spawnSync } from "node:child_process";
import {
  cpSync,
  existsSync,
  mkdirSync,
  readdirSync,
  readFileSync,
  rmSync,
  statSync,
  writeFileSync,
} from "node:fs";
import { createHash } from "node:crypto";
import { dirname, join, relative, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const webRoot = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const repositoryRoot = resolve(webRoot, "..");
const baselineRoot = join(repositoryRoot, "docs", "api", "openapi-baseline");
const defaultOutputRoot = join(webRoot, "src", "api", "generated");
const generatedDomains = ["public", "user", "admin"];

function executableName() {
  return process.platform === "win32"
    ? "openapi-generator-cli.cmd"
    : "openapi-generator-cli";
}

function removeNonTypeScriptFiles(directory) {
  for (const entry of readdirSync(directory, { withFileTypes: true })) {
    const entryPath = join(directory, entry.name);
    if (entry.isDirectory()) {
      removeNonTypeScriptFiles(entryPath);
      if (readdirSync(entryPath).length === 0) {
        rmSync(entryPath, { recursive: true, force: true });
      }
    } else if (!entry.name.endsWith(".ts")) {
      rmSync(entryPath, { force: true });
    }
  }
}

export function normalizeGeneratedTypeScript(content) {
  const withoutTrailingWhitespace = content.replace(/[ \t]+(?=\r?$)/gm, "");
  return `${withoutTrailingWhitespace.replace(/(?:\r?\n)*$/, "")}\n`;
}

export function normalizeGeneratedTypeScriptFiles(directory) {
  for (const entry of readdirSync(directory, { withFileTypes: true })) {
    const entryPath = join(directory, entry.name);
    if (entry.isDirectory()) {
      normalizeGeneratedTypeScriptFiles(entryPath);
    } else if (entry.name.endsWith(".ts")) {
      const content = readFileSync(entryPath, "utf8");
      const normalized = normalizeGeneratedTypeScript(content);
      if (normalized !== content) {
        writeFileSync(entryPath, normalized, "utf8");
      }
    }
  }
}

function generateDomain(domain, outputRoot) {
  const input = relative(
    webRoot,
    join(baselineRoot, `${domain}.json`),
  ).replaceAll("\\", "/");
  const output = join(outputRoot, domain);
  rmSync(output, { recursive: true, force: true });
  mkdirSync(output, { recursive: true });

  const generatorArguments = [
    "generate",
    "-g",
    "typescript-fetch",
    "-i",
    input,
    "-o",
    output,
    "--additional-properties",
    "supportsES6=true,typescriptThreePlus=true,useSingleRequestParameter=true,withInterfaces=true",
    "--global-property",
    "apiDocs=false,apiTests=false,modelDocs=false,modelTests=false",
  ];
  const useDocker = process.env.HOTSHOP_OPENAPI_GENERATOR_DOCKER === "1";
  const result = useDocker
    ? spawnSync(
        "docker",
        [
          "run",
          "--rm",
          "--mount",
          `type=bind,source=${repositoryRoot},target=/workspace,readonly`,
          "--mount",
          `type=bind,source=${output},target=/output`,
          "openapitools/openapi-generator-cli:v7.14.0",
          ...generatorArguments.map((argument) => {
            if (argument === input) {
              return `/workspace/docs/api/openapi-baseline/${domain}.json`;
            }
            return argument === output ? "/output" : argument;
          }),
        ],
        { cwd: webRoot, encoding: "utf8", stdio: "inherit" },
      )
    : spawnSync(executableName(), generatorArguments, {
        cwd: webRoot,
        encoding: "utf8",
        shell: process.platform === "win32",
        stdio: "inherit",
      });

  if (result.status !== 0) {
    throw new Error(`OpenAPI generation failed for ${domain}`);
  }

  removeNonTypeScriptFiles(output);
  normalizeGeneratedTypeScriptFiles(output);
}

export function generateAdmin(outputRoot = defaultOutputRoot) {
  mkdirSync(outputRoot, { recursive: true });
  generateDomain("admin", outputRoot);
}

export function generateAll(outputRoot = defaultOutputRoot) {
  mkdirSync(outputRoot, { recursive: true });
  for (const domain of generatedDomains) {
    generateDomain(domain, outputRoot);
  }
}

function filesBelow(root) {
  if (!existsSync(root)) {
    return [];
  }

  const files = [];
  const visit = (directory) => {
    for (const entry of readdirSync(directory, { withFileTypes: true })) {
      const entryPath = join(directory, entry.name);
      if (entry.isDirectory()) {
        visit(entryPath);
      } else {
        files.push(relative(root, entryPath).replaceAll("\\", "/"));
      }
    }
  };
  visit(root);
  return files.sort();
}

function digest(path) {
  return createHash("sha256").update(readFileSync(path)).digest("hex");
}

export function compareTrees(expectedRoot, actualRoot) {
  const expected = filesBelow(expectedRoot);
  const actual = filesBelow(actualRoot);
  const names = new Set([...expected, ...actual]);
  const differences = [];

  for (const name of [...names].sort()) {
    const expectedPath = join(expectedRoot, name);
    const actualPath = join(actualRoot, name);
    if (!existsSync(expectedPath)) {
      differences.push(`unexpected generated file: ${name}`);
    } else if (!existsSync(actualPath)) {
      differences.push(`missing generated file: ${name}`);
    } else if (
      statSync(expectedPath).size !== statSync(actualPath).size ||
      digest(expectedPath) !== digest(actualPath)
    ) {
      differences.push(`generated file differs: ${name}`);
    }
  }

  return differences;
}

export function copyGeneratedTo(targetRoot) {
  rmSync(targetRoot, { recursive: true, force: true });
  cpSync(defaultOutputRoot, targetRoot, { recursive: true });
}

export { defaultOutputRoot, generatedDomains, webRoot };
