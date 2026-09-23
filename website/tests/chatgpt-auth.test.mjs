import assert from "node:assert/strict";
import * as nodeModule from "node:module";
import test from "node:test";

// `app/chatgpt-auth.ts` imports `next/headers` and `next/navigation`, which are
// only resolvable inside the vinext/Next build. Stub them so the module can be
// imported directly from source and its pure helpers exercised in isolation.
const NEXT_STUBS =
  "data:text/javascript,export const headers=async()=>new Map();export const redirect=()=>{};";

function stubNextServerModules() {
  if (typeof nodeModule.registerHooks === "function") {
    nodeModule.registerHooks({
      resolve(specifier, context, next) {
        if (specifier === "next/headers" || specifier === "next/navigation") {
          return { url: NEXT_STUBS, shortCircuit: true };
        }
        return next(specifier, context);
      },
    });
    return;
  }

  // Node < 22.15 has no synchronous `registerHooks`; fall back to a data-URL
  // loader passed to the older asynchronous `register` API.
  const loader =
    "data:text/javascript," +
    encodeURIComponent(`
export async function resolve(specifier, context, next) {
  if (specifier === "next/headers" || specifier === "next/navigation") {
    return { url: ${JSON.stringify(NEXT_STUBS)}, shortCircuit: true };
  }
  return next(specifier, context);
}
`);
  nodeModule.register(loader, import.meta.url);
}

stubNextServerModules();

// Importing a `.ts` module needs Node's type stripping, which is only unflagged
// from Node 22.18; on the older end of the supported range, skip rather than
// fail the suite.
let chatgptAuth = null;
let importError = null;
try {
  chatgptAuth = await import("../app/chatgpt-auth.ts");
} catch (error) {
  importError = error;
}

// Decode the `return_to` the sanitizer embedded so assertions read the value the
// browser would actually navigate to, not its percent-encoded form.
function returnTo(path) {
  const query = path.slice(path.indexOf("?") + 1);
  return decodeURIComponent(new URLSearchParams(query).get("return_to") ?? "");
}

function skipWhenUnavailable(t) {
  if (chatgptAuth) return false;
  t.skip(`cannot import app/chatgpt-auth.ts: ${importError?.message ?? "unknown"}`);
  return true;
}

test("keeps legitimate relative return paths intact", (t) => {
  if (skipWhenUnavailable(t)) return;
  const { chatGPTSignInPath, chatGPTSignOutPath } = chatgptAuth;
  assert.equal(returnTo(chatGPTSignInPath("/dashboard")), "/dashboard");
  assert.equal(returnTo(chatGPTSignInPath("/a/b?x=1#top")), "/a/b?x=1#top");
  assert.equal(returnTo(chatGPTSignInPath("/")), "/");
  assert.equal(returnTo(chatGPTSignOutPath("/settings")), "/settings");
  assert.equal(returnTo(chatGPTSignOutPath()), "/");
});

test("rejects off-origin and protocol-relative return paths", (t) => {
  if (skipWhenUnavailable(t)) return;
  const { chatGPTSignInPath, chatGPTSignOutPath } = chatgptAuth;
  for (const value of [
    "//evil.com",
    "///evil.com",
    "https://evil.com",
    "http://evil.com/path",
    "\\evil.com",
    "/\\evil.com",
    "javascript:alert(1)",
    "",
    "relative/path",
  ]) {
    assert.equal(returnTo(chatGPTSignInPath(value)), "/", value);
    assert.equal(returnTo(chatGPTSignOutPath(value)), "/", value);
  }
});

test("rejects dot-segment bypasses that normalize into a protocol-relative URL", (t) => {
  if (skipWhenUnavailable(t)) return;
  const { chatGPTSignInPath, chatGPTSignOutPath } = chatgptAuth;
  // These pass the raw `//` check at the top of the sanitizer but collapse to a
  // leading `//` (or `/\`) once `new URL` removes the dot segments. A browser
  // resolves the resulting `Location: //evil.com` as `https://evil.com`.
  for (const value of [
    "/a/..//evil.com",
    "/a/../\\evil.com",
    "/signin-with-chatgpt/..//\\evil.com",
    "/..//evil.com",
    "/a/..//evil.com/path",
    "/a/..//\\evil.com",
    "/a/b/../../..//evil.com",
  ]) {
    assert.equal(returnTo(chatGPTSignInPath(value)), "/", value);
    assert.equal(returnTo(chatGPTSignOutPath(value)), "/", value);
  }
});

test("rejects reserved auth paths even after normalization", (t) => {
  if (skipWhenUnavailable(t)) return;
  const { chatGPTSignInPath } = chatgptAuth;
  assert.equal(returnTo(chatGPTSignInPath("/signin-with-chatgpt")), "/");
  assert.equal(returnTo(chatGPTSignInPath("/signout-with-chatgpt")), "/");
  assert.equal(returnTo(chatGPTSignInPath("/callback")), "/");
  // `/a/../callback` normalizes to the reserved `/callback`.
  assert.equal(returnTo(chatGPTSignInPath("/a/../callback")), "/");
});

test("never emits a return_to that starts with a double slash or backslash", (t) => {
  if (skipWhenUnavailable(t)) return;
  const { chatGPTSignInPath } = chatgptAuth;
  const hostile = [
    "/a/..//evil.com",
    "/a/../\\evil.com",
    "/signin-with-chatgpt/..//\\evil.com",
    "/..//evil.com",
    "/%2F%2Fevil.com",
    "/\tevil.com",
  ];
  for (const value of hostile) {
    const safe = returnTo(chatGPTSignInPath(value));
    assert.ok(!safe.startsWith("//"), `${value} => ${safe}`);
    assert.ok(!safe.startsWith("/\\"), `${value} => ${safe}`);
    assert.ok(!safe.includes("\\"), `${value} => ${safe}`);
  }
});
