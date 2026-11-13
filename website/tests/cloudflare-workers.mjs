// 新版 wrangler／@cloudflare/vite-plugin 會把 `cloudflare:workers` 這種 workerd 內建模組
// 原樣留在 SSR 產物裡（`dist/server/index.js` 的 lazy chunk）。Node 的 ESM loader 只認
// file/data/node 三種協定，`await import("../dist/server/index.js")` 之後第一次 `worker.fetch()`
// 就會以 ERR_UNSUPPORTED_ESM_URL_SCHEME 失敗，讓整個渲染測試失去意義。
//
// 測試本來就不跑 workerd：binding 是透過 `worker.fetch(request, env, ctx)` 的第二個參數注入，
// 模組層的 `env` 只要存在即可（`db/index.ts` 只在 `getDb()` 內讀 `env.DB`，缺 binding 時
// 丟的是程式自己的 D1_BINDING_MISSING）。所以這裡把所有 `cloudflare:` 模組接到 stub。
//
// 用同步版的 `registerHooks`（Node 22.15+，`package.json` 的 engines 已要求 >=22.18），
// 不用已棄用的 `module.register()`；單一檔案即可，不需要額外的 hooks thread 檔案。
import { registerHooks } from "node:module";

const STUB_URL =
  "data:text/javascript," +
  "export const env = {};" +
  "export const connect = () => { throw new Error('cloudflare:sockets is not available under Node'); };" +
  "export default { env, connect };";

registerHooks({
  resolve(specifier, context, nextResolve) {
    if (specifier.startsWith("cloudflare:")) {
      return { url: STUB_URL, format: "module", shortCircuit: true };
    }

    return nextResolve(specifier, context);
  },
});
