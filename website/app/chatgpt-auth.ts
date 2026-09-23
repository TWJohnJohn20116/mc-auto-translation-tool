import { headers } from "next/headers";
import { redirect } from "next/navigation";

export type ChatGPTUser = {
  userId: string;
  displayName: string;
  email: string;
  fullName: string | null;
};

const USER_ID_HEADER = "oai-authenticated-user-id";
const USER_EMAIL_HEADER = "oai-authenticated-user-email";
const USER_FULL_NAME_HEADER = "oai-authenticated-user-full-name";
const USER_FULL_NAME_ENCODING_HEADER =
  "oai-authenticated-user-full-name-encoding";
const PERCENT_ENCODED_UTF8 = "percent-encoded-utf-8";
const SIGN_IN_PATH = "/signin-with-chatgpt";
const SIGN_OUT_PATH = "/signout-with-chatgpt";
const CALLBACK_PATH = "/callback";

export async function getChatGPTUser(): Promise<ChatGPTUser | null> {
  const requestHeaders = await headers();
  const userId = requestHeaders.get(USER_ID_HEADER);
  const email = requestHeaders.get(USER_EMAIL_HEADER);
  if (!userId || !email) return null;

  const encodedFullName = requestHeaders.get(USER_FULL_NAME_HEADER);
  const fullName =
    encodedFullName &&
    requestHeaders.get(USER_FULL_NAME_ENCODING_HEADER) === PERCENT_ENCODED_UTF8
      ? safeDecodeURIComponent(encodedFullName)
      : null;

  return {
    userId,
    displayName: fullName ?? email,
    email,
    fullName,
  };
}

export async function requireChatGPTUser(
  returnTo: string,
): Promise<ChatGPTUser> {
  const user = await getChatGPTUser();
  if (user) return user;

  redirect(chatGPTSignInPath(returnTo));
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
    url = new URL(value, "https://app.local");
  } catch {
    return "/";
  }
  if (url.origin !== "https://app.local") return "/";
  // Dot-segment removal happens during parsing, so the *normalized* pathname must
  // be re-validated: `/a/..//evil.com` collapses to `//evil.com`, which a browser
  // resolves as an absolute cross-origin URL when used in a Location header.
  if (url.pathname.startsWith("//") || url.pathname.startsWith("/\\")) return "/";
  if (isReservedAuthPath(url.pathname)) return "/";
  // Percent-encoded separators (`%2F`) survive `new URL`, so a consumer that
  // decodes `return_to` before redirecting would still see a protocol-relative
  // path. Reject any pathname that escapes the origin once decoded.
  if (isProtocolRelativeAfterDecoding(url.pathname)) return "/";

  return `${url.pathname}${url.search}${url.hash}`;
}

function isProtocolRelativeAfterDecoding(pathname: string): boolean {
  // Percent-encoding can be applied more than once, so decode until the value
  // stops changing (bounded to avoid pathological input) and check each step.
  let candidate = pathname;
  for (let pass = 0; pass < 4; pass += 1) {
    if (candidate.startsWith("//") || candidate.startsWith("/\\")) return true;
    const decoded = safeDecodeURIComponent(candidate);
    if (decoded === null || decoded === candidate) return false;
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
