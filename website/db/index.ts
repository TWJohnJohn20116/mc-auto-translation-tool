import { env } from "cloudflare:workers";
import { drizzle } from "drizzle-orm/d1";
import * as schema from "./schema";

// Tagged so callers can react to a missing binding without matching on the
// message text; `examples/d1/app/api/notes/route.ts` maps it to HTTP 503.
export const D1_BINDING_MISSING = "D1_BINDING_MISSING";

export function getDb() {
  if (!env.DB) {
    throw Object.assign(
      new Error(
        "Cloudflare D1 binding `DB` is unavailable. Set the `d1` field in .openai/hosting.json to `DB` or let your control plane inject the real binding values before using the database."
      ),
      { code: D1_BINDING_MISSING }
    );
  }

  return drizzle(env.DB, { schema });
}
