import assert from "node:assert/strict";
import * as nodeModule from "node:module";
import test from "node:test";

// `app/chatgpt-auth.ts` imports `next/navigation`, which is only resolvable inside
// the vinext/Next build. Stub it so the module can be imported directly from source
// and its helpers exercised in isolation; `redirect` throws like the real one does.
const NEXT_STUBS =
  "data:text/javascript," +
  encodeURIComponent(`
export function redirect(url) {
  const error = new Error("NEXT_REDIRECT " + url);
  error.digest = "NEXT_REDIRECT";
  throw error;
}
`);

// `worker/index.ts` imports the app router entry point. A real render is not needed
// here, so it is replaced by an echo that reports the header names it received; that
// is what lets the strip test observe what the app router actually sees.
const APP_ENTRY_STUBS =
  "data:text/javascript," +
  encodeURIComponent(`
export default {
  async fetch(request) {
    return new Response(JSON.stringify([...request.headers.keys()]), {
      status: 200,
      headers: { "content-type": "application/json" },
    });
  },
};
`);

const STUBBED_SPECIFIERS = new Map([
  ["next/navigation", NEXT_STUBS],
  ["vinext/server/app-router-entry", APP_ENTRY_STUBS],
]);

function stubNextServerModules() {
  if (typeof nodeModule.registerHooks === "function") {
    nodeModule.registerHooks({
      resolve(specifier, context, next) {
        const stub = STUBBED_SPECIFIERS.get(specifier);
        if (stub) return { url: stub, shortCircuit: true };
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
const STUBS = ${JSON.stringify(Object.fromEntries(STUBBED_SPECIFIERS))};
export async function resolve(specifier, context, next) {
  if (Object.hasOwn(STUBS, specifier)) {
    return { url: STUBS[specifier], shortCircuit: true };
  }
  return next(specifier, context);
}
`);
  nodeModule.register(loader, import.meta.url);
}

stubNextServerModules();

// Importing a `.ts` module needs Node's type stripping, which is only unflagged
// from Node 22.18. `package.json` now declares exactly that floor, so a failed
// import is a real environment error: skipping instead would let the whole file
// report success while asserting nothing.
let chatgptAuth = null;
let importError = null;
try {
  chatgptAuth = await import("../app/chatgpt-auth.ts");
} catch (error) {
  importError = error;
}

function requireAuth() {
  assert.ok(
    chatgptAuth,
    `cannot import app/chatgpt-auth.ts on Node ${process.versions.node} (needs >=22.18): ${
      importError?.message ?? "unknown"
    }`,
  );
  return chatgptAuth;
}

// Decode the `return_to` the sanitizer embedded so assertions read the value the
// browser would actually navigate to, not its percent-encoded form.
function returnTo(path) {
  const query = path.slice(path.indexOf("?") + 1);
  return decodeURIComponent(new URLSearchParams(query).get("return_to") ?? "");
}

test("keeps legitimate relative return paths intact", () => {
  const { chatGPTSignInPath, chatGPTSignOutPath } = requireAuth();
  assert.equal(returnTo(chatGPTSignInPath("/dashboard")), "/dashboard");
  assert.equal(returnTo(chatGPTSignInPath("/a/b?x=1#top")), "/a/b?x=1#top");
  assert.equal(returnTo(chatGPTSignInPath("/")), "/");
  assert.equal(returnTo(chatGPTSignOutPath("/settings")), "/settings");
  assert.equal(returnTo(chatGPTSignOutPath()), "/");
});

test("rejects off-origin and protocol-relative return paths", () => {
  const { chatGPTSignInPath, chatGPTSignOutPath } = requireAuth();
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

test("rejects dot-segment bypasses that normalize into a protocol-relative URL", () => {
  const { chatGPTSignInPath, chatGPTSignOutPath } = requireAuth();
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
    // Percent-encoded layers: the decoded form only turns into `//evil.com`
    // after a normalizer runs a second time, which a prefix check never sees.
    "/%2F%2Fevil.com",
    "/a/%252e%252e%252f..//evil.com",
    "/%252e%252e//evil.com",
    "/a/%252e%252e%252f%252e%252e%252f%252fevil.com",
  ]) {
    assert.equal(returnTo(chatGPTSignInPath(value)), "/", value);
    assert.equal(returnTo(chatGPTSignOutPath(value)), "/", value);
  }
});

test("rejects reserved auth paths even after normalization", () => {
  const { chatGPTSignInPath } = requireAuth();
  assert.equal(returnTo(chatGPTSignInPath("/signin-with-chatgpt")), "/");
  assert.equal(returnTo(chatGPTSignInPath("/signout-with-chatgpt")), "/");
  assert.equal(returnTo(chatGPTSignInPath("/callback")), "/");
  // `/a/../callback` normalizes to the reserved `/callback`.
  assert.equal(returnTo(chatGPTSignInPath("/a/../callback")), "/");
});

test("never emits a return_to that starts with a double slash or backslash", () => {
  const { chatGPTSignInPath } = requireAuth();
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

// `worker/index.ts` strips every `oai-authenticated-*` header before the app router
// sees a request, so nothing the app can observe carries an identity. The reader must
// say so rather than hand back `null`, which used to turn every `requireChatGPTUser()`
// caller into an endless sign-in redirect.
const FORGED_IDENTITY = {
  "oai-authenticated-user-id": "user-1",
  "oai-authenticated-user-email": "user@example.com",
  "oai-authenticated-user-full-name": "%E5%BC%A0%E4%B8%89",
  "oai-authenticated-user-full-name-encoding": "percent-encoded-utf-8",
};

test("refuses to resolve an identity", async () => {
  const { getChatGPTUser } = requireAuth();
  await assert.rejects(
    () => getChatGPTUser(),
    /authentication is not configured/i,
  );
});

test("requireChatGPTUser reports the missing authentication instead of redirecting", async () => {
  const { requireChatGPTUser } = requireAuth();
  await assert.rejects(() => requireChatGPTUser("/dashboard"), (error) => {
    assert.notEqual(
      error.digest,
      "NEXT_REDIRECT",
      "沒有可用的身分來源時不得再轉址：登入回來仍然沒有身分，就是無限轉址",
    );
    assert.match(error.message, /authentication is not configured/i);
    return true;
  });
});

// The strip is the other half of that contract: if it regressed, a forged identity
// would reach the app. Pin it by observing the headers the app router receives.
test("worker strips oai-authenticated-* before the app router sees the request", async () => {
  const { default: worker } = await import("../worker/index.ts");
  const response = await worker.fetch(
    new Request("http://localhost/", {
      headers: { accept: "text/html", ...FORGED_IDENTITY },
    }),
    { ASSETS: undefined, DB: undefined },
    { waitUntil() {}, passThroughOnException() {} },
  );
  assert.equal(response.status, 200);
  const seen = await response.json();
  assert.ok(
    !seen.some((name) => name.toLowerCase().startsWith("oai-authenticated-")),
    `worker 沒有剝離身分標頭，應用程式會收到：${seen.join(", ")}`,
  );
  assert.ok(seen.includes("accept"), "worker 不得順手丟掉正常請求標頭");
});
