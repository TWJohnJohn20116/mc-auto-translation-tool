import { redirect } from "next/navigation";

export type ChatGPTUser = {
  userId: string;
  displayName: string;
  email: string;
  fullName: string | null;
};

const SIGN_IN_PATH = "/signin-with-chatgpt";
const SIGN_OUT_PATH = "/signout-with-chatgpt";
const CALLBACK_PATH = "/callback";
// Synthetic origin used to normalize `return_to` candidates. It is not a real
// site: parsing with it only serves to run the URL normalizer.
const RETURN_PATH_ORIGIN = "https://app.local";

/**
 * Identity is not configured on this deployment, so this reports it instead of
 * pretending to read one.
 *
 * `worker/index.ts` strips every `oai-authenticated-*` header before the app router
 * sees a request — without that strip, a caller could forge a user id and an email
 * with two request headers. Those headers used to be exactly what this reader
 * parsed, so the two halves cancelled out: every request produced `null`, and
 * `requireChatGPTUser()` answered with a sign-in redirect that could never complete
 * (an endless redirect the moment a route called it). Until a real session — or a
 * signed header the worker can verify — replaces the current scheme, fail loudly.
 *
 * Keep this paragraph and the one above `IDENTITY_HEADER_PREFIX` in
 * `worker/index.ts` describing the same decision: they are one contract.
 */
const AUTH_NOT_CONFIGURED =
  "authentication is not configured: worker/index.ts strips every " +
  "oai-authenticated-* header, so no request carries a platform-injected identity";

export async function getChatGPTUser(): Promise<ChatGPTUser | null> {
  throw new Error(AUTH_NOT_CONFIGURED);
}

export async function requireChatGPTUser(
  returnTo: string,
): Promise<ChatGPTUser> {
  const user = await getChatGPTUser();
  if (user) return user;

  return redirect(chatGPTSignInPath(returnTo));
}

export function chatGPTSignInPath(returnTo: string): string {
  const safeReturnTo = safeRelativeReturnPath(returnTo);
  return `${SIGN_IN_PATH}?return_to=${encodeURIComponent(safeReturnTo)}`;
}

export function chatGPTSignOutPath(returnTo = "/"): string {
  const safeReturnTo = safeRelativeReturnPath(returnTo);
  return `${SIGN_OUT_PATH}?return_to=${encodeURIComponent(safeReturnTo)}`;
}

function safeRelativeReturnPath(value: string): string {
  // Reject absolute and protocol-relative inputs, plus any backslash: browsers
  // treat `\` as a path separator, so `/a/..\evil.com` would otherwise be
  // normalized into a protocol-relative URL that resolves off-origin.
  if (
    !value.startsWith("/") ||
    value.startsWith("//") ||
    value.includes("\\")
  ) {
    return "/";
  }

  let url: URL;
  try {
    url = new URL(value, RETURN_PATH_ORIGIN);
  } catch {
    return "/";
  }
  if (url.origin !== RETURN_PATH_ORIGIN) return "/";
  // Dot-segment removal happens during parsing, so the *normalized* pathname must
  // be re-validated: `/a/..//evil.com` collapses to `//evil.com`, which a browser
  // resolves as an absolute cross-origin URL when used in a Location header.
  if (url.pathname.startsWith("//") || url.pathname.startsWith("/\\")) return "/";
  if (isReservedAuthPath(url.pathname)) return "/";
  // Percent-encoded separators (`%2F`) survive `new URL`, so a consumer that
  // decodes `return_to` before redirecting would still see a protocol-relative
  // path. Decode step by step and re-normalize every step, because a consumer may
  // normalize *after* decoding: a `startsWith` check on the raw string misses
  // inputs such as `/a/%252e%252e%252f..//evil.com`, whose dot segments only
  // collapse into `//evil.com` once the extra layer is decoded.
  if (escapesOriginAfterDecoding(url.pathname)) return "/";

  return `${url.pathname}${url.search}${url.hash}`;
}

function escapesOriginAfterDecoding(pathname: string): boolean {
  // Percent-encoding can be applied more than once, so decode until the value
  // stops changing (bounded to avoid pathological input) and validate each step.
  let candidate = pathname;
  for (let pass = 0; pass < 4; pass += 1) {
    let normalized: URL;
    try {
      normalized = new URL(candidate, RETURN_PATH_ORIGIN);
    } catch {
      return true;
    }
    if (normalized.origin !== RETURN_PATH_ORIGIN) return true;
    if (normalized.pathname.startsWith("//") || normalized.pathname.startsWith("/\\")) {
      return true;
    }
    const decoded = safeDecodeURIComponent(candidate);
    if (decoded === null) return true;
    if (decoded === candidate) return false;
    candidate = decoded;
  }
  return false;
}

function isReservedAuthPath(pathname: string): boolean {
  return (
    pathname === SIGN_IN_PATH ||
    pathname === SIGN_OUT_PATH ||
    pathname === CALLBACK_PATH
  );
}

function safeDecodeURIComponent(value: string): string | null {
  try {
    return decodeURIComponent(value);
  } catch {
    return null;
  }
}
