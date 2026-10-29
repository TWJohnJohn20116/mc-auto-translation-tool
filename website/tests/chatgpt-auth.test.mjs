import assert from "node:assert/strict";
import * as nodeModule from "node:module";
import test from "node:test";

// `app/chatgpt-auth.ts` imports `next/headers` and `next/navigation`, which are
// only resolvable inside the vinext/Next build. Stub them so the module can be
// imported directly from source and its helpers exercised in isolation. The
// headers stub reads `globalThis.__nextRequestHeaders` so a test can present a
// specific identity, and `redirect` throws like the real one does.
const NEXT_STUBS =
  "data:text/javascript," +
  encodeURIComponent(`
export const headers = async () =>
  new Map(Object.entries(globalThis.__nextRequestHeaders ?? {}));
export function redirect(url) {
  const error = new Error("NEXT_REDIRECT " + url);
  error.digest = "NEXT_REDIRECT";
  throw error;
}
`);

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

// `getChatGPTUser()` is the identity source every future `requireChatGPTUser()`
// caller relies on, so its three branches are pinned here.
function withRequestHeaders(headers, body) {
  return (async () => {
    globalThis.__nextRequestHeaders = headers;
    try {
      return await body();
    } finally {
      delete globalThis.__nextRequestHeaders;
    }
  })();
}

test("returns null when the identity headers are absent", async () => {
  const { getChatGPTUser } = requireAuth();
  const user = await withRequestHeaders({}, () => getChatGPTUser());
  assert.equal(user, null);
  const partial = await withRequestHeaders(
    { "oai-authenticated-user-id": "user-1" },
    () => getChatGPTUser(),
  );
  assert.equal(partial, null, "an id without an email is not a usable identity");
});

test("returns the injected identity", async () => {
  const { getChatGPTUser } = requireAuth();
  const user = await withRequestHeaders(
    {
      "oai-authenticated-user-id": "user-1",
      "oai-authenticated-user-email": "user@example.com",
    },
    () => getChatGPTUser(),
  );
  assert.deepEqual(user, {
    userId: "user-1",
    displayName: "user@example.com",
    email: "user@example.com",
    fullName: null,
  });
});

test("decodes the full name only when the encoding header says so", async () => {
  const { getChatGPTUser } = requireAuth();
  const decoded = await withRequestHeaders(
    {
      "oai-authenticated-user-id": "user-1",
      "oai-authenticated-user-email": "user@example.com",
      "oai-authenticated-user-full-name": "%E5%BC%A0%E4%B8%89",
      "oai-authenticated-user-full-name-encoding": "percent-encoded-utf-8",
    },
    () => getChatGPTUser(),
  );
  assert.equal(decoded?.fullName, "张三");
  assert.equal(decoded?.displayName, "张三");

  const undecoded = await withRequestHeaders(
    {
      "oai-authenticated-user-id": "user-1",
      "oai-authenticated-user-email": "user@example.com",
      "oai-authenticated-user-full-name": "%E5%BC%A0%E4%B8%89",
    },
    () => getChatGPTUser(),
  );
  assert.equal(undecoded?.fullName, null);
  assert.equal(undecoded?.displayName, "user@example.com");

  const malformed = await withRequestHeaders(
    {
      "oai-authenticated-user-id": "user-1",
      "oai-authenticated-user-email": "user@example.com",
      "oai-authenticated-user-full-name": "%E5%BC",
      "oai-authenticated-user-full-name-encoding": "percent-encoded-utf-8",
    },
    () => getChatGPTUser(),
  );
  assert.equal(malformed?.fullName, null, "a broken escape must not throw");
});

// `redirect()` never returns; a stubbed no-op redirect used to let this function
// fall off the end and resolve to `undefined` while typed as `ChatGPTUser`.
test("requireChatGPTUser returns the user, and throws a redirect when absent", async () => {
  const { requireChatGPTUser } = requireAuth();
  const user = await withRequestHeaders(
    {
      "oai-authenticated-user-id": "user-1",
      "oai-authenticated-user-email": "user@example.com",
    },
    () => requireChatGPTUser("/dashboard"),
  );
  assert.equal(user.userId, "user-1");

  await withRequestHeaders({}, async () => {
    await assert.rejects(
      () => requireChatGPTUser("/dashboard"),
      (error) => {
        assert.equal(error.digest, "NEXT_REDIRECT");
        assert.match(error.message, /\/signin-with-chatgpt\?return_to=/);
        return true;
      },
    );
  });
});
