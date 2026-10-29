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

const escapeRe = (value) => value.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");

test("server-renders the public-benefit project home page", async () => {
  // 版本號只在 app/page.tsx 定義一次，測試從原始碼取，這樣每次發布都不必改測試。
  // （原本這裡寫死 1.3.10，網站升到 1.3.11 之後這個測試就一直紅著。）
  const source = await readFile(new URL("../app/page.tsx", import.meta.url), "utf8");
  const releaseVersion = source.match(/const releaseVersion = "([^"]+)"/)?.[1];
  const pageTitle = source.match(/\btitle: "([^"]+)"/)?.[1];
  assert.ok(releaseVersion, "app/page.tsx 找不到 releaseVersion");
  assert.ok(pageTitle, "app/page.tsx 找不到 metadata title");

  const response = await render();
  assert.equal(response.status, 200);
  assert.match(response.headers.get("content-type") ?? "", /^text\/html\b/i);

  const html = await response.text();
  assert.match(html, /<html lang="zh-CN">/i);
  assert.match(html, new RegExp(`<title>${escapeRe(pageTitle)}</title>`, "i"));
  assert.match(html, /完全公益/);
  assert.match(html, /免费开源/);
  assert.match(html, /无广告/);
  assert.match(html, /不收集玩家数据/);
  assert.match(html, /玩家名称默认保护，可主动开启翻译/);
  assert.match(html, /服务器域名、IP 地址与端口/);
  assert.match(html, /1\.8\.9/);
  assert.match(html, /1\.12\.2/);
  assert.match(html, /1\.16\.5/);
  assert.match(html, /1\.19\.2/);
  assert.match(html, /1\.21\.11/);
  assert.match(html, /26\.2/);
  assert.match(html, new RegExp(`${escapeRe(releaseVersion)} 正式版`));
  assert.match(html, /自行配置的 API/);
  assert.match(html, /从 GitHub 下载/);
  const version = escapeRe(releaseVersion);
  const releaseDownloads = [
    ...new Set(
      html.match(
        new RegExp(
          `https://github\\.com/TWJohnJohn20116/mc-auto-translation-tool/releases/download/v${version}/[A-Za-z0-9.-]+`,
          "g",
        ),
      ) ?? [],
    ),
  ];
  const downloadNames = releaseDownloads.map((href) => href.split("/").pop());
  // 這裡只守「結構」：每個下載連結的檔名都必須帶本版前綴，而且除了校驗檔之外全是 .jar。
  // 不寫死 31／32——那兩個數字由 downloads/<releaseVersion> 與 publish-release.yml 決定，
  // 寫死在測試裡只會變成每次發布都要多同步一處的第二份真相。
  for (const name of downloadNames) {
    assert.match(
      name,
      /^MCAutoTranslationTool-[\d.]+-[A-Za-z0-9.-]+\.jar$/,
      `下載連結的檔名不符合 MCAutoTranslationTool-<版本>-<目標>.jar：${name}`,
    );
    assert.ok(
      name.startsWith(`MCAutoTranslationTool-${releaseVersion}-`),
      `下載連結的版本前綴不是 ${releaseVersion}：${name}`,
    );
  }
  assert.deepEqual(
    downloadNames.filter((name) => !name.endsWith(".jar")),
    ["SHA256SUMS.txt"],
    "除了 SHA256SUMS.txt 之外不應該出現非 .jar 的下載連結",
  );
  // 三個載入器分組都必須渲染出來：漏掉一組等於整族版本沒有入口。
  for (const group of ["Fabric", "Forge", "NeoForge"]) {
    assert.match(html, new RegExp(`id="download-${group}"`), `下載分組 ${group} 沒有渲染出來`);
  }
  // C2-181 的契約：Fabric 單一 JAR 必須把 Loader 下限寫在文案裡（下限本身可以隨發布調整）。
  assert.match(
    html,
    /Fabric Loader\s*(?:≥|&ge;|&#8805;|>=)\s*\d+\.\d+\.\d+/,
    "Fabric 卡片必須寫明 Fabric Loader 的最低版本",
  );
  // metadata.description 會跟著 releaseVersion 走，漏改就是發布時的文案漂移。
  const pageDescription = source.match(/description:\s*\n?\s*"([^"]+)"/)?.[1];
  assert.ok(pageDescription, "app/page.tsx 找不到 metadata description");
  assert.ok(
    pageDescription.includes(releaseVersion),
    `metadata description 沒有提到目前版本 ${releaseVersion}`,
  );
  // 文案宣稱的 JAR 數量必須等於實際渲染出的 .jar 連結數，而不是把當下的數字寫死在測試裡：
  // 原本這裡是 assert.match(html, /31 个经过校验的 JAR/)，每次發布都要多改一處，而且它守的
  // 不是真契約——文案與下載卡可以各自漂移而測試全綠。
  const claimedJars = source.match(/(\d+) 个经过校验的 JAR/)?.[1];
  assert.ok(claimedJars, "app/page.tsx 找不到「N 个经过校验的 JAR」");
  const jarLinks = downloadNames.filter((name) => name.endsWith(".jar")).length;
  assert.equal(
    Number(claimedJars),
    jarLinks,
    `文案宣稱 ${claimedJars} 顆 JAR，但頁面只渲染出 ${jarLinks} 個 .jar 連結`,
  );
  assert.equal(
    downloadNames.length,
    jarLinks + 1,
    "下載連結除了 JAR 之外應該只有 SHA256SUMS.txt",
  );
  assert.match(html, new RegExp(`MCAutoTranslationTool-${version}-mc1\\.8\\.9-forge\\.jar`));
  assert.match(html, new RegExp(`MCAutoTranslationTool-${version}-fabric-all\\.jar`));
  assert.match(html, new RegExp(`MCAutoTranslationTool-${version}-mc1\\.21\\.9-1\\.21\\.11-forge\\.jar`));
  assert.match(html, new RegExp(`MCAutoTranslationTool-${version}-mc26\\.1-26\\.1\\.2-forge\\.jar`));
  assert.match(html, /原作者：B站「我小张7272635」/);
  assert.match(html, /space\.bilibili\.com\/3546631091783712/);
  assert.match(html, /og-card\.png/);
  assert.match(html, /summary_large_image/);
  assert.doesNotMatch(html, /Your site is taking shape|Building your site/);

  // Link previews must not point at the viewer's own machine. Without a
  // `metadataBase`, vinext falls back to `http://localhost:3000`, which makes
  // every Discord/Twitter/QQ preview render a broken localhost image.
  for (const [, content] of html.matchAll(
    /<meta[^>]+(?:property="og:image"|name="twitter:image")[^>]+content="([^"]+)"/g,
  )) {
    assert.doesNotMatch(content, /localhost|127\.0\.0\.1/i);
    assert.match(content, /^https?:\/\//);
  }
  const socialImages = [
    ...html.matchAll(
      /<meta[^>]+(?:property="og:image"|name="twitter:image")[^>]+content="([^"]+)"/g,
    ),
  ].map((match) => match[1]);
  assert.ok(socialImages.length > 0, "expected social image meta tags");
  for (const content of socialImages) {
    assert.match(content, /\/og-card\.png$/, content);
  }
});

test("keeps privacy and public-benefit claims in source", async () => {
  const [page, layout, css] = await Promise.all([
    readFile(new URL("../app/page.tsx", import.meta.url), "utf8"),
    readFile(new URL("../app/layout.tsx", import.meta.url), "utf8"),
    readFile(new URL("../app/globals.css", import.meta.url), "utf8"),
  ]);

  assert.match(page, /只有需要翻译的自然语言片段会进入/);
  assert.match(page, /玩家名默认保护 · 可在设置中开启翻译/);
  assert.match(page, /不会用“理论兼容”冒充已经支持/);
  assert.match(page, /转载或改编请保留原作者署名 · MIT License/);
  assert.match(layout, /lang="zh-CN"/);
  assert.match(layout, /完全公益、免费开源/);
  assert.match(css, /prefers-reduced-motion:\s*reduce/);
  assert.match(css, /@media \(max-width:\s*560px\)/);
  assert.doesNotMatch(page, /SkeletonPreview|codex-preview/);
});
