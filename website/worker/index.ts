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

// Identity headers are stripped here rather than trusted: otherwise a caller can
// forge a user id and an email with two request headers. `app/chatgpt-auth.ts`
// therefore refuses to read an identity at all (it throws AUTH_NOT_CONFIGURED)
// instead of reporting `null` for every request — these two files describe one
// decision, so change them together.
const IDENTITY_HEADER_PREFIX = "oai-authenticated-";

// The site's only interactive element is a download button, so it must not be
// frameable, and it must not leak the full referrer to the destinations it links.
const SECURITY_HEADERS: Record<string, string> = {
  "content-security-policy": "frame-ancestors 'none'",
  "referrer-policy": "strict-origin-when-cross-origin",
  "strict-transport-security": "max-age=31536000",
  "x-content-type-options": "nosniff",
  "x-frame-options": "DENY",
};

function withoutIdentityHeaders(request: Request): Request {
  const forwarded = new Headers();
  let found = false;
  for (const [name, value] of request.headers) {
    if (name.toLowerCase().startsWith(IDENTITY_HEADER_PREFIX)) {
      found = true;
      continue;
    }
    forwarded.append(name, value);
  }
  // Incoming request headers are immutable, so the sanitized copy is a new
  // Request; browsers never send these headers, so the common path is untouched.
  return found ? new Request(request, { headers: forwarded }) : request;
}

function withSecurityHeaders(response: Response): Response {
  const hardened = new Response(response.body, response);
  for (const [name, value] of Object.entries(SECURITY_HEADERS)) {
    hardened.headers.set(name, value);
  }
  return hardened;
}

const worker = {
  async fetch(request: Request, env: Env, ctx: ExecutionContext): Promise<Response> {
    // Almost every byte this site serves is static, so a failed render should
    // degrade to the asset store instead of a Workers 500 page.
    ctx.passThroughOnException();

    try {
      const response = await handler.fetch(withoutIdentityHeaders(request), env, ctx);
      return withSecurityHeaders(response);
    } catch (error) {
      console.error("[worker] request failed:", error);
      if (env.ASSETS) {
        return withSecurityHeaders(await env.ASSETS.fetch(request));
      }
      return withSecurityHeaders(
        new Response("Service temporarily unavailable", {
          status: 503,
          headers: { "content-type": "text/plain; charset=utf-8" },
        }),
      );
    }
  },
};

export default worker;
