import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

async function render() {
  const workerUrl = new URL("../dist/server/index.js", import.meta.url);
  workerUrl.searchParams.set("test", `${process.pid}-${Date.now()}`);
  const { default: worker } = await import(workerUrl.href);

  return worker.fetch(
    new Request("http://localhost/", { headers: { accept: "text/html" } }),
    { ASSETS: { fetch: async () => new Response("Not found", { status: 404 }) } },
    { waitUntil() {}, passThroughOnException() {} },
  );
}

test("server-renders the public-benefit project home page", async () => {
  const response = await render();
  assert.equal(response.status, 200);
  assert.match(response.headers.get("content-type") ?? "", /^text\/html\b/i);

  const html = await response.text();
  assert.match(html, /<html lang="zh-CN">/i);
  assert.match(html, /<title>MC 自动翻译工具｜公益、离线、跨版本<\/title>/i);
  assert.match(html, /完全公益/);
  assert.match(html, /免费开源/);
  assert.match(html, /无广告/);
  assert.match(html, /不收集玩家数据/);
  assert.match(html, /玩家名称与本机账号名/);
  assert.match(html, /服务器域名、IP 地址与端口/);
  assert.match(html, /1\.8\.9/);
  assert.match(html, /1\.12\.2/);
  assert.match(html, /1\.21\.11/);
  assert.match(html, /og-card\.png/);
  assert.match(html, /summary_large_image/);
  assert.doesNotMatch(html, /Your site is taking shape|Building your site/);
});

test("keeps privacy and public-benefit claims in source", async () => {
  const [page, layout, css] = await Promise.all([
    readFile(new URL("../app/page.tsx", import.meta.url), "utf8"),
    readFile(new URL("../app/layout.tsx", import.meta.url), "utf8"),
    readFile(new URL("../app/globals.css", import.meta.url), "utf8"),
  ]);

  assert.match(page, /只有需要翻译的自然语言片段会进入/);
  assert.match(page, /玩家名与服务器地址保持原样/);
  assert.match(page, /不会用“理论兼容”冒充已经支持/);
  assert.match(page, /MIT License · 永久免费/);
  assert.match(layout, /lang="zh-CN"/);
  assert.match(layout, /完全公益、免费开源/);
  assert.match(css, /prefers-reduced-motion:\s*reduce/);
  assert.match(css, /@media \(max-width:\s*560px\)/);
  assert.doesNotMatch(page, /SkeletonPreview|codex-preview/);
});
