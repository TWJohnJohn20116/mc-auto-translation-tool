/** Cloudflare Worker entry point for the vinext-starter template. */
import handler from "vinext/server/app-router-entry";

interface Env {
  ASSETS: Fetcher;
  DB: D1Database;
}

interface ExecutionContext {
  waitUntil(promise: Promise<unknown>): void;
  passThroughOnException(): void;
}

// Image optimization is handled by `vinext/server/app-router-entry`, which
// intercepts both `/_next/image` (the path `next/image` actually emits) and the
// legacy `/_vinext/image` alias. No `images` binding is provisioned in
// `vite.config.ts` (`localBindingConfig` declares only D1/R2), so a bespoke
// `env.IMAGES` interception here would throw on every request; leave the
// built-in passthrough in place.

const worker = {
  async fetch(request: Request, env: Env, ctx: ExecutionContext): Promise<Response> {
    return handler.fetch(request, env, ctx);
  },
};

export default worker;
