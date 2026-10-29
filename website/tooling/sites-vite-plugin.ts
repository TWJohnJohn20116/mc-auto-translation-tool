import { access, cp, mkdir, readFile, rm } from "node:fs/promises";
import { resolve } from "node:path";
import type { Plugin } from "vite";

async function exists(path: string): Promise<boolean> {
  try {
    await access(path);
    return true;
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code === "ENOENT") {
      return false;
    }
    throw error;
  }
}

async function countMigrations(root: string): Promise<number> {
  try {
    const raw = await readFile(
      resolve(root, "drizzle", "meta", "_journal.json"),
      "utf8",
    );
    const parsed = JSON.parse(raw) as { entries?: unknown };
    return Array.isArray(parsed.entries) ? parsed.entries.length : 0;
  } catch {
    return 0;
  }
}

// `db/schema.ts` ships as `export {}` until the site actually needs a database,
// so an empty migration set is only a defect once that file declares tables.
async function declaresTables(root: string): Promise<boolean> {
  try {
    const raw = await readFile(resolve(root, "db", "schema.ts"), "utf8");
    const body = raw
      .replace(/\/\*[\s\S]*?\*\//g, "")
      .replace(/\/\/[^\n]*/g, "")
      .replace(/\s+/g, "");
    return body !== "" && body !== "export{}" && body !== "export{};";
  } catch {
    return false;
  }
}

// Packages Sites metadata and migrations after Vite finishes compiling.
// This source deliberately lives outside build/, which Vinext clears.
export function sites(): Plugin {
  let root = process.cwd();

  return {
    name: "sites",
    apply: "build",
    configResolved(config) {
      root = config.root;
    },
    async closeBundle() {
      const outputDirectory = resolve(root, "dist", ".openai");
      const hostingConfig = resolve(root, ".openai", "hosting.json");
      const drizzleSource = resolve(root, "drizzle");

      await rm(outputDirectory, { recursive: true, force: true });
      await mkdir(outputDirectory, { recursive: true });

      if (await exists(hostingConfig)) {
        await cp(hostingConfig, resolve(outputDirectory, "hosting.json"));
      }

      // Shipping an empty `drizzle/` deploys cleanly and then fails on the first
      // request that touches the database, so the mismatch is a build error here.
      const migrations = (await exists(drizzleSource))
        ? await countMigrations(root)
        : 0;
      if (migrations === 0 && (await declaresTables(root))) {
        throw new Error(
          "db/schema.ts declares tables but drizzle/meta/_journal.json contains no migrations. " +
            "Run `pnpm run db:generate` and commit the generated SQL before building.",
        );
      }

      if (await exists(drizzleSource)) {
        await cp(drizzleSource, resolve(outputDirectory, "drizzle"), {
          recursive: true,
        });
        console.log(`[sites] packaged ${migrations} Drizzle migration(s)`);
      }
    },
  };
}
