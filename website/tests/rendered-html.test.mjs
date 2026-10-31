import assert from "node:assert/strict";
import { readFile, readdir, stat } from "node:fs/promises";
import test from "node:test";

async function render() {
  const workerUrl = new URL("../dist/server/index.js", import.meta.url);
  workerUrl.searchParams.set("test", `${process.pid}-${Date.now()}`);
  const { default: worker } = await import(workerUrl.href);

  return worker.fetch(
    new Request("http://localhost/", { headers: { accept: "text/html" } }),
    { ASSETS: assetStore(), DB: undefined },
    { waitUntil() {}, passThroughOnException() {} },
  );
}

// Serves `public/` from disk instead of answering 404 to everything, so a missing
// or renamed asset fails the suite rather than being hidden by the stub.
function assetStore() {
  return {
    async fetch(request) {
      const { pathname } = new URL(request.url);
      try {
        const body = await readFile(new URL(`../public${pathname}`, import.meta.url));
        return new Response(body, { status: 200 });
      } catch {
        return new Response("Not found", { status: 404 });
      }
    },
  };
}

const escapeRe = (value) => value.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");

test("server-renders the public-benefit project home page", async () => {
  // 版本號只在 app/page.tsx 定義一次，測試從原始碼取，這樣每次發布都不必改測試。
  // （原本這裡寫死 1.3.10，網站升到 1.3.11 之後這個測試就一直紅著。）
  const source = await readFile(new URL("../app/page.tsx", import.meta.url), "utf8");
  const releaseVersion = source.match(/const releaseVersion = "([^"]+)"/)?.[1];
  assert.ok(releaseVersion, "app/page.tsx 找不到 releaseVersion");
  // 標題與描述都是模板字串，把 ${releaseVersion} 還原成實際版本號再比對；
  // 這樣「分頁標題停在舊版」會在這裡紅，而不是等到發布後才被人發現。
  const resolveVersion = (value) =>
    (value ?? "").replace(/\$\{releaseVersion\}/g, releaseVersion);
  const pageTitle = resolveVersion(
    source.match(/\btitle:\s*[`"]([^`"]+)[`"]/)?.[1],
  );
  assert.ok(pageTitle, "app/page.tsx 找不到 metadata title");
  assert.ok(
    pageTitle.includes(releaseVersion),
    `metadata title 沒有跟著 releaseVersion 走：${pageTitle}`,
  );

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
  for (const name of downloadNames.filter((entry) => entry.endsWith(".jar"))) {
    assert.match(
      name,
      new RegExp(`^MCAutoTranslationTool-${version}-[A-Za-z0-9.-]+\\.jar$`),
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
  const pageDescription = resolveVersion(
    source.match(/description:\s*\n?\s*[`"]([\s\S]*?)[`"],?\s*\n\s*\};/)?.[1],
  );
  assert.ok(pageDescription, "app/page.tsx 找不到 metadata description");
  assert.ok(
    pageDescription.includes(releaseVersion),
    `metadata description 沒有提到目前版本 ${releaseVersion}`,
  );
  // 文案宣稱的可安裝 JAR 數量不再由這裡單獨比對：同一個數字在四份 README 與六份 docs 用四種措辭
  // 手抄（見下面的 INSTALLABLE_CLAIMS），只守 page.tsx 的一種措辭等於只守了其中一處。
  const jarLinks = downloadNames.filter((name) => name.endsWith(".jar")).length;
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

  // Link previews must not point at an unrelated origin. `metadataBase` only
  // comes from `NEXT_PUBLIC_SITE_URL`; a repository-shaped fallback would resolve
  // `/og-card.png` to `<host>/og-card.png` (the base path is discarded), which is
  // an image no server hosts.
  //
  // 這條斷言必須雙向明確。只驗「結尾是 /og-card.png」時，正確的絕對 URL 與指向錯誤網域的
  // URL 都通過，測試卻分不出來。所以：有設 `NEXT_PUBLIC_SITE_URL` 就要求同來源的絕對 URL；
  // 沒設時 Next 會拿請求來源（本測試是 http://localhost:3000）補全相對路徑，因此要求
  // pathname 正好是 `/og-card.png` 且來源只能是那個 loopback（該模式在 website/README.md
  // 的部署章節有說明）。
  const socialImages = [
    ...html.matchAll(
      /<meta[^>]+(?:property="og:image"|name="twitter:image")[^>]+content="([^"]+)"/g,
    ),
  ].map((match) => match[1]);
  assert.ok(socialImages.length > 0, "expected social image meta tags");
  const siteOrigin = process.env.NEXT_PUBLIC_SITE_URL
    ? new URL(process.env.NEXT_PUBLIC_SITE_URL).origin
    : null;
  for (const content of socialImages) {
    assert.doesNotMatch(
      content,
      /^https?:\/\/(?:[a-z0-9-]+\.)*github\.com\//i,
      `社交預覽圖不能解析到 GitHub 根目錄：${content}`,
    );
    if (siteOrigin) {
      assert.ok(
        content.startsWith(`${siteOrigin}/`),
        `NEXT_PUBLIC_SITE_URL=${siteOrigin} 時社交預覽圖必須是同來源的絕對 URL：${content}`,
      );
      assert.equal(new URL(content).pathname, "/og-card.png", content);
    } else {
      assert.equal(new URL(content).pathname, "/og-card.png", content);
      assert.equal(
        new URL(content).origin,
        "http://localhost:3000",
        `未設定 NEXT_PUBLIC_SITE_URL 時社交預覽圖不得帶上其他網域（Next 會用請求來源補全）：${content}`,
      );
    }
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
  // 社交預覽圖的來源只有 `NEXT_PUBLIC_SITE_URL` 一個決策點：若哪天有人為了「總是有圖」
  // 加回備用網域，預覽圖就會指向不存在的圖，這幾條斷言是那個決策點的守衛。
  assert.match(layout, /resolveMetadataBase\(process\.env\.NEXT_PUBLIC_SITE_URL\)/);
  assert.doesNotMatch(
    layout,
    /\|\|\s*["'`]https?:\/\//,
    "metadataBase 不得有硬寫的備用網域：`/og-card.png` 會把 base path 丟掉",
  );
  // Open Graph 與 Twitter 兩張卡片必須共用同一個圖檔路徑，否則只會有一邊被更新。
  assert.match(layout, /const OG_CARD_PATH = "\/og-card\.png"/);
  assert.match(layout, /images: \[OG_CARD_PATH\]/);
});

// The page and its metadata reference absolute asset paths; nothing else checks
// that those files exist, so a deleted `public/` entry would ship silently.
test("every absolute asset the page references exists in public/", async () => {
  const html = await (await render()).text();
  const referenced = new Set();
  // 設了 `NEXT_PUBLIC_SITE_URL` 之後，同一批資產會渲染成絕對 URL（`metadataBase`
  // 會把以 `/` 開頭的路徑接上去），所以兩種形式都要抓，並一律以 pathname 對應
  // `public/`：只認相對形式的話，絕對形式的部署下這條測試會一個資產都抓不到而空過。
  for (const [, raw] of html.matchAll(
    /(?:href|src|content)="((?:https?:\/\/[^"]+|\/)[A-Za-z0-9._/-]*\.(?:png|svg|ico|webp|avif|jpe?g))"/g,
  )) {
    referenced.add(new URL(raw, "http://localhost").pathname);
  }
  assert.ok(
    referenced.size > 0,
    "expected the rendered page to reference at least one absolute asset path",
  );
  for (const href of referenced) {
    const file = new URL(`../public${href}`, import.meta.url);
    let missing = false;
    try {
      await readFile(file);
    } catch {
      missing = true;
    }
    assert.ok(
      !missing,
      `public${href} is referenced by the rendered page but does not exist`,
    );
  }
});

// The page renders its download links from `releaseFile()`／`releaseJar()`, but the
// same 31 filenames are also hand-copied into the repository README and the three
// translated READMEs (31 names each). Nothing compared the two sets, so a single
// family rename (say `26.1-26.1.2` → `26.1.2`) could 404 four documents while the
// whole build stayed green: the only existing gate checked the version label.
// 這些檔案在倉庫根與 `docs/`，相對 `website/tests/` 要走兩層出去。
const DOCUMENTED_READMES = [
  "../../README.md",
  "../../docs/Zh-cn/README.md",
  "../../docs/Zh-tw/README.md",
  "../../docs/en/README.md",
];

test("keeps every documented download name in sync with the rendered page", async (t) => {
  const pageSource = await readFile(
    new URL("../app/page.tsx", import.meta.url),
    "utf8",
  );
  const releaseVersion = pageSource.match(/const releaseVersion = "([^"]+)"/)?.[1];
  assert.ok(releaseVersion, "app/page.tsx 找不到 releaseVersion");

  const html = await (await render()).text();
  const version = escapeRe(releaseVersion);
  const rendered = new Set(
    (
      html.match(
        new RegExp(`download/v${version}/[A-Za-z0-9.-]+\\.jar`, "g"),
      ) ?? []
    ).map((href) => href.split("/").pop()),
  );
  assert.ok(rendered.size > 0, "頁面沒有渲染出任何 .jar 下載連結");

  for (const relative of DOCUMENTED_READMES) {
    const text = await readFile(new URL(relative, import.meta.url), "utf8");
    const documented = new Set(text.match(/MCAutoTranslationTool-[^)"\s]+\.jar/g) ?? []);
    assert.deepEqual(
      [...documented].sort(),
      [...rendered].sort(),
      `${relative} 的下載檔名與頁面渲染的不一致：多出來的會 404，少掉的是文件沒跟上`,
    );
    // 版本標籤同樣是手抄的，一處留在舊版本就是整段連結 404。
    const linked = new Set(
      (text.match(/releases\/download\/v([^/]+)\//g) ?? []).map((url) =>
        url.replace(/^releases\/download\/v/, "").replace(/\/$/, ""),
      ),
    );
    assert.deepEqual(
      [...linked],
      [releaseVersion],
      `${relative} 的下載連結必須全部指向 v${releaseVersion}（目前：${[...linked].join(", ")}）`,
    );
  }

  // `downloads/<版本>/release-assets.txt` 是 31 個可安裝檔名的權威清單：它由
  // `scripts/prepare_release_assets.py` 與可安裝 JAR 同一批產生，並由 `prepare-release.yml`
  // 寫回 `downloads/<版本>/` 隨 PR 提交。存在時就以它為準再比一次；只有在 `downloads/<版本>`
  // 這個目錄本身還不存在（版本號 bump 後、prepare-release 還沒跑）時才略過。
  let manifest = null;
  try {
    manifest = await readFile(
      new URL(`../../downloads/${releaseVersion}/release-assets.txt`, import.meta.url),
      "utf8",
    );
  } catch {
    manifest = null;
  }
  if (manifest === null) {
    // 只有「版本號 bump 後 `downloads/<新版本>` 還沒產生」這種窗口才該略過；目錄已經在、清單卻
    // 不在，就是清單沒提交（G10 的產生者與寫回步驟都在，缺的是這一份檔案本身）。
    let downloadDirectoryExists = true;
    try {
      await readdir(new URL(`../../downloads/${releaseVersion}`, import.meta.url));
    } catch {
      downloadDirectoryExists = false;
    }
    assert.ok(
      !downloadDirectoryExists,
      `downloads/${releaseVersion} 已存在但缺 release-assets.txt：清單是「文件 ↔ 真實資產」比對的唯一憑據`,
    );
    t.diagnostic(
      `downloads/${releaseVersion} 不存在，略過與發佈清單的比對`,
    );
  } else {
    const listed = new Set(
      manifest
        .split("\n")
        .map((line) => line.trim())
        .filter(Boolean),
    );
    assert.deepEqual(
      [...listed].sort(),
      [...rendered].sort(),
      "頁面渲染的下載檔名與 downloads/<版本>/release-assets.txt 不一致",
    );
  }
});

// The repository README and `docs/Zh-cn/README.md` are two hand-written copies of the
// same Simplified-Chinese text. Fixing only one of them is how the root README kept
// telling beta5 users to delete "1.4-beta4 and earlier" (two JARs of the same modId
// then sit in `mods/`, and the game refuses to start) and kept describing Forge
// family JARs as continuous version ranges. Link targets and the few lines GitHub's
// repository page needs are allowed to differ; everything else must match line for
// line, otherwise the copies have drifted again.
const README_COPY_DRIFT_ALLOWED = new Set([
  // GitHub 首頁徽章只出現在根 README。
  "[![主要建置](X)](X)",
  // 語言切換列：docs 版多一個「倉庫首頁」回連。
  "[简体中文](X) · [繁體中文](X) · [English](X)",
  "[简体中文](X) · [繁體中文](X) · [English](X) · [仓库首页](X)",
]);

function normalizeReadmeCopy(text) {
  return text
    .split("\n")
    .map((line) => line.trim().replace(/\]\([^)]*\)/g, "](X)").replace(/\*\*/g, ""))
    .filter((line) => line && !README_COPY_DRIFT_ALLOWED.has(line));
}

test("keeps the repository README and docs/Zh-cn/README.md saying the same thing", async () => {
  const [root, docs] = await Promise.all([
    readFile(new URL("../../README.md", import.meta.url), "utf8"),
    readFile(new URL("../../docs/Zh-cn/README.md", import.meta.url), "utf8"),
  ]);
  const rootLines = new Set(normalizeReadmeCopy(root));
  const docsLines = new Set(normalizeReadmeCopy(docs));
  const drift = [
    ...[...rootLines]
      .filter((line) => !docsLines.has(line))
      .map((line) => `根 README 獨有：${line}`),
    ...[...docsLines]
      .filter((line) => !rootLines.has(line))
      .map((line) => `docs/Zh-cn/README.md 獨有：${line}`),
  ];
  assert.deepEqual(
    drift,
    [],
    "兩份簡中 README 已經漂移：同步內容，或把刻意不同的那一行加進 README_COPY_DRIFT_ALLOWED",
  );
});

// 兩個對外頭條數字（「48 個發布目標」與「51 個精確實作」）被手抄在十幾處，但真正決定它們的是
// 產生端：`prepare-release.yml` 的 `TARGETS_JSON` 與 `platforms/fabric/*` 的實作目錄。先前只有
// 「31 個可安裝 JAR」有守（文案宣稱的 N 必須等於渲染出的 `.jar` 連結數），於是新增一個 Fabric
// 版本時 31 會逼人更新 `page.tsx`，而 48／51 可以同時錯在十幾處、CI 全綠——錯的還是使用者用來
// 判斷「我的版本有沒有被支援」的那兩個數字。
const TARGET_COUNT_CLAIMS = [
  /(\d+)\s+个构建产物/,
  /(\d+)\s+個建置產物/,
  /(\d+)\s+build artifacts/,
  /(\d+)\s+个发布构建产物/,
  /(\d+)\s+個發佈建置產物/,
  /(\d+)\s+release build artifacts/,
  /发布目标（(\d+)\s+个）/,
  /發佈目標（(\d+)\s+個）/,
  /Release target\s+\((\d+)\)/,
];

const IMPLEMENTATION_CLAIMS = [
  /(\d+)\s+个精确版本实现/,
  /(\d+)\s+個精確版本實作/,
  /(\d+)\s+exact-version implementations/,
  /(\d+)\s+个 Fabric 实现/,
  /(\d+)\s+個 Fabric 實作/,
  /(\d+)\s+Fabric implementations/,
  /(\d+)\s+个精确实作/,
  /(\d+)\s+個精確實作/,
];

// 「NeoForge N 个版本各一颗」是同一頁的第三個頭條數字，也是 31 = 1(fabric-all) + 11(forge) + 19(neoforge)
// 的那個分解項：48／51／31 都有守，這個沒有——補上一個 NeoForge 版本時同一頁會自相矛盾。
const NEOFORGE_COUNT_CLAIMS = [
  /(\d+)\s+个版本各一颗/,
  /(\d+)\s+個版本各一顆/,
];

const COVERAGE_DOCS = [
  "../../README.md",
  "../../docs/Zh-cn/README.md",
  "../../docs/Zh-tw/README.md",
  "../../docs/en/README.md",
  "../../docs/Zh-cn/BUILDING.md",
  "../../docs/Zh-tw/BUILDING.md",
  "../../docs/en/BUILDING.md",
];

// 除 26.x 之外，每個 bundle 的版本目錄在 `versions/` 底下；26.x 的版本目錄直接掛在 bundle 根
// （`platforms/fabric/26.x/{26.1,…}`，同層還有 `bundle` 與 `shared`，那不是版本）。
const FABRIC_VERSIONED_BUNDLES = ["1.13", "1.14-1.15", "1.16", "1.17-1.18", "1.19", "1.20", "1.21"];

test("keeps the headline coverage numbers in sync with their producers", async () => {
  // 48：權威是 `prepare-release.yml` 裡那份 TARGETS_JSON，不是任何文件裡的數字。
  const prepareWorkflow = await readFile(
    new URL("../../.github/workflows/prepare-release.yml", import.meta.url),
    "utf8",
  );
  const targetsJson = prepareWorkflow.match(
    /TARGETS_JSON="\$\(cat <<'JSON'\n([\s\S]*?)\n\s*JSON\n/,
  )?.[1];
  assert.ok(targetsJson, "prepare-release.yml 找不到 TARGETS_JSON heredoc");
  const releaseTargets = JSON.parse(targetsJson);
  assert.ok(releaseTargets.length > 0, "prepare-release.yml 的 TARGETS_JSON 解析出 0 個目標");

  // 51：權威是實作目錄數（不寫死，也不問任何文件）。
  const counts = await Promise.all([
    ...FABRIC_VERSIONED_BUNDLES.map(
      async (bundle) =>
        (await readdir(new URL(`../../platforms/fabric/${bundle}/versions`, import.meta.url))).length,
    ),
    readdir(new URL("../../platforms/fabric/26.x", import.meta.url)).then(
      (entries) => entries.filter((name) => /^\d/.test(name)).length,
    ),
  ]);
  const implementations = counts.reduce((sum, count) => sum + count, 0);
  assert.ok(implementations > 0, "platforms/fabric 的實作目錄數算成 0");

  const pageSource = await readFile(new URL("../app/page.tsx", import.meta.url), "utf8");
  const pageImplementations = Number(
    pageSource.match(/const fabricImplementations = (\d+)/)?.[1] ?? NaN,
  );
  assert.equal(
    pageImplementations,
    implementations,
    `app/page.tsx 的 fabricImplementations 是 ${pageImplementations}，但 versions/ 目錄合計 ${implementations}`,
  );

  // 19：權威是 `prepare-release.yml` 的 neoforge 目標，它同時應該等於 `platforms/neoforge/**/versions`
  // 的版本目錄。數字對齊了但卡片少一張（或反之）一樣算漂移，所以三邊都比。比的必須是**成員**而不是
  // 數量：目標側有 1.20.1（獨立專案，沒有 `versions/`）、目錄側有 1.20.2（座標解析不到，不在
  // `TARGETS_JSON`），兩個 19 純屬巧合，只比數量會在補上 1.20.2 時給出「目錄少了」的錯誤診斷。
  const neoforgeTargetVersions = new Set(
    releaseTargets
      .filter((target) => target.target.endsWith("-neoforge"))
      .map((target) => target.target.replace(/^mc/, "").replace(/-neoforge$/, "")),
  );
  const neoforgeTargets = neoforgeTargetVersions.size;
  const neoforgeVersionDirs = new Set();
  for (const layer of await readdir(new URL("../../platforms/neoforge", import.meta.url))) {
    let versions = [];
    try {
      versions = await readdir(
        new URL(`../../platforms/neoforge/${layer}/versions`, import.meta.url),
      );
    } catch {
      // 沒有 `versions/` 的目錄不是版本層（`1.20.1` 是獨立專案、`shared` 是共用層）。
      continue;
    }
    for (const version of versions) neoforgeVersionDirs.add(version);
  }
  // 兩側各有一個結構例外，寫成具名斷言：任何一邊變動時這裡會直接說出是哪一邊改了，
  // 而不是丟一句「目錄數對不上」把人推向去補目錄的錯誤方向。
  assert.ok(
    !neoforgeTargetVersions.has("1.20.2"),
    "1.20.2 的座標解析不到，不該出現在 TARGETS_JSON；若修好了，請同步移除本例外與 page.tsx 的措辭",
  );
  assert.ok(
    neoforgeVersionDirs.has("1.20.2"),
    "platforms/neoforge/1.20/versions/1.20.2 仍被 settings.gradle 註冊，刪除前要一併改 settings.gradle",
  );
  const neoforgeMissingDirs = [...neoforgeTargetVersions].filter(
    (version) => version !== "1.20.1" && !neoforgeVersionDirs.has(version),
  );
  assert.deepEqual(
    neoforgeMissingDirs,
    [],
    `TARGETS_JSON 有、platforms/neoforge 沒有 versions/ 的版本：${neoforgeMissingDirs.join("、")}`,
  );
  const neoforgeMissingTargets = [...neoforgeVersionDirs].filter(
    (version) => version !== "1.20.2" && !neoforgeTargetVersions.has(version),
  );
  assert.deepEqual(
    neoforgeMissingTargets,
    [],
    `platforms/neoforge 有 versions/ 目錄、但 TARGETS_JSON 沒有目標的版本：${neoforgeMissingTargets.join("、")}`,
  );
  const neoforgeCards = (pageSource.match(/releaseFile\("[^"]*-neoforge"\)/g) ?? []).length;
  assert.equal(
    neoforgeCards,
    neoforgeTargets,
    `app/page.tsx 渲染 ${neoforgeCards} 張 NeoForge 下載卡，但 prepare-release.yml 有 ${neoforgeTargets} 個 neoforge 目標`,
  );
  for (const pattern of NEOFORGE_COUNT_CLAIMS) {
    for (const [matched, digits] of pageSource.matchAll(new RegExp(pattern.source, "g"))) {
      assert.equal(
        Number(digits),
        neoforgeTargets,
        `app/page.tsx：「${matched}」寫 ${digits}，但 prepare-release.yml 有 ${neoforgeTargets} 個 neoforge 目標`,
      );
    }
  }

  const problems = [];
  for (const relative of COVERAGE_DOCS) {
    const text = await readFile(new URL(relative, import.meta.url), "utf8");
    let mentionsTargets = false;
    for (const pattern of TARGET_COUNT_CLAIMS) {
      for (const [matched, digits] of text.matchAll(new RegExp(pattern.source, "g"))) {
        mentionsTargets = true;
        if (Number(digits) !== releaseTargets.length) {
          problems.push(
            `${relative}：「${matched}」寫 ${digits}，但 prepare-release.yml 有 ${releaseTargets.length} 個發布目標`,
          );
        }
      }
    }
    let mentionsImplementations = false;
    for (const pattern of IMPLEMENTATION_CLAIMS) {
      for (const [matched, digits] of text.matchAll(new RegExp(pattern.source, "g"))) {
        mentionsImplementations = true;
        if (Number(digits) !== implementations) {
          problems.push(
            `${relative}：「${matched}」寫 ${digits}，但 versions/ 目錄合計 ${implementations} 個實作`,
          );
        }
      }
    }
    // 這兩句話是使用者判斷「我的版本有沒有被支援」的第一手依據，所以不是「有寫就要對」，
    // 而是每份文件都必須寫。
    if (!mentionsTargets) {
      problems.push(`${relative} 完全沒有寫發布目標數量（例如「48 个构建产物」）`);
    }
    if (!mentionsImplementations) {
      problems.push(`${relative} 完全沒有寫 Fabric 實作數量（例如「51 个精确版本实现」）`);
    }
  }
  assert.deepEqual(problems, [], "頭條數字與產生端不一致：改文件或改產生端，兩者必須同步");
});

// page.tsx 的 metadata description 是分享卡片與搜尋摘要的唯一來源，而它先前承諾「1.0.0–1.12.2 的
// Fabric／Ornithe 目標仍需自行構建」——那 12 個目錄早已被刪除，與同一頁 :43 的「26 個版本」互相打臉。
test("keeps the source-only Fabric range counts in app/page.tsx equal to their directories", async () => {
  const source = await readFile(new URL("../app/page.tsx", import.meta.url), "utf8");
  const ranges = [
    [/1\.3\.1–1\.8\.8 共 (\d+) 个版本/, "1.0-1.8"],
    [/1\.8\.9–1\.12\.2 共 (\d+) 个版本/, "1.8-1.12"],
  ];
  for (const [pattern, bundle] of ranges) {
    const claimed = source.match(pattern)?.[1];
    assert.ok(claimed, `app/page.tsx 找不到「${pattern.source}」`);
    const entries = await readdir(
      new URL(`../../platforms/fabric/${bundle}/versions`, import.meta.url),
    );
    assert.equal(
      Number(claimed),
      entries.length,
      `app/page.tsx 宣稱 ${claimed} 個 ${bundle} 版本，但 versions/ 有 ${entries.length} 個目錄`,
    );
  }
  // 這 12 個版本（1.0.0–1.2.5、1.6.2、1.7.3、1.7.5、1.7.7）沒有目標目錄，不得再被寫成「可自行構建」。
  assert.doesNotMatch(
    source,
    /1\.0\.0–(?:1\.12\.2|1\.2\.5)/,
    "app/page.tsx 不得再宣稱 1.0.0 起的目標存在（那 12 個目錄已於 S2-128 刪除）",
  );
});

// 目前這份清單覆蓋 13 個檔案。文件是「照著做」的前提，所以路徑與版本號都要能被機械比對。
const DOC_FILES = [
  "../../README.md",
  "../../docs/README.md",
  "../../docs/Zh-cn/ARCHITECTURE.md",
  "../../docs/Zh-cn/BUILDING.md",
  "../../docs/Zh-cn/COMPATIBILITY.md",
  "../../docs/Zh-cn/ONLINE_APIS.md",
  "../../docs/Zh-cn/README.md",
  "../../docs/Zh-cn/THIRD_PARTY_OFFLINE.md",
  "../../docs/Zh-cn/USER_GUIDE.md",
  "../../docs/Zh-tw/ARCHITECTURE.md",
  "../../docs/Zh-tw/BUILDING.md",
  "../../docs/Zh-tw/COMPATIBILITY.md",
  "../../docs/Zh-tw/ONLINE_APIS.md",
  "../../docs/Zh-tw/README.md",
  "../../docs/Zh-tw/THIRD_PARTY_OFFLINE.md",
  "../../docs/Zh-tw/USER_GUIDE.md",
  "../../docs/en/ARCHITECTURE.md",
  "../../docs/en/BUILDING.md",
  "../../docs/en/COMPATIBILITY.md",
  "../../docs/en/ONLINE_APIS.md",
  "../../docs/en/README.md",
  "../../docs/en/THIRD_PARTY_OFFLINE.md",
  "../../docs/en/USER_GUIDE.md",
];

// 反引號內、以倉庫目錄開頭的字串才會被當成「這條路徑應該存在」。`legacy/` 這種泛稱不算，
// 只有 `legacy/<還有內容>` 才算：根目錄已經沒有 legacy/，舊版 Forge 在 platforms/forge/legacy/。
const DOC_PATH_TOKEN = /^(?:platforms|translator-core|docs|scripts|website|gradle|downloads|\.github)\/|^legacy\/.+/;
// 輸出目錄（build/…）不在版控裡；含佔位符或 glob 的字串無從比對，跳過。
const DOC_BUILD_SUFFIX = /\/build(?:\/.*)?$/;
const DOC_PLACEHOLDER = /[<>*…{}$\s]/;

test("keeps every repository path quoted in the docs pointing at a real file or directory", async () => {
  const problems = [];
  let checked = 0;
  for (const relative of DOC_FILES) {
    const text = await readFile(new URL(relative, import.meta.url), "utf8");
    for (const [, token] of text.matchAll(/`([^`\n]+)`/g)) {
      const candidate = token.trim();
      if (!DOC_PATH_TOKEN.test(candidate) || DOC_PLACEHOLDER.test(candidate)) continue;
      const target = candidate.replace(DOC_BUILD_SUFFIX, "").replace(/\/+$/, "");
      if (!target) continue;
      checked += 1;
      let found = true;
      try {
        await stat(new URL(`../../${target}`, import.meta.url));
      } catch {
        found = false;
      }
      if (!found) {
        problems.push(`${relative}：\`${candidate}\` 指向不存在的路徑 ${target}`);
      }
    }
  }
  assert.ok(checked > 60, `只比對了 ${checked} 個文件路徑，抽取規則可能已經失效`);
  assert.deepEqual(
    problems,
    [],
    "文件指名的路徑必須真的存在：目錄重構（legacy/ → platforms/forge/legacy/、版本專案 → versions/）之後要一起改",
  );
});

// 兩類會被照抄的版本號：產物檔名（`...-1.3.9.jar`）與 commands 裡的 `downloads/1.4`、`--version 1.4`。
// 前者今天就是錯的（現行 1.4），後者會在 bump 後失效——而 `downloads/` 只保留現行版本的目錄，
// `prepare-release` 會把舊目錄整個刪掉，所以照著過期文件做必然失敗。
const DOC_STALE_ARTIFACT = /mc-auto-translation-tool-[a-z0-9.-]+?-\d+\.\d+(?:\.\d+)?(?:-[a-z0-9]+)?\.jar/g;
const DOC_STALE_RELEASE_DIR = /downloads\/\d+\.\d+(?:\.\d+)?/g;
const DOC_STALE_VERSION_OPTION = /--version\s+\d+\.\d+(?:\.\d+)?/g;
// 對外發布的資產檔名是 `MCAutoTranslationTool-<版本>-<目標>.jar`（`downloads/<版本>/release-assets.txt`
// 逐字如此），而上面那條只認小寫的 `mc-auto-translation-tool-*` bundle 範例：31 個真實檔名一個都不會
// 命中，於是「文件裡的產物檔名必須是現行版本」實際只守了一種從未上過 Release 的寫法。
const DOC_ASSET_REFERENCE = /MCAutoTranslationTool-[A-Za-z0-9.<>*_-]+\.jar/g;

test("keeps the version numbers hard-coded in the docs equal to gradle.properties", async () => {
  const properties = await readFile(new URL("../../gradle.properties", import.meta.url), "utf8");
  const modVersion = properties.match(/^mod_version=(.+)$/m)?.[1]?.trim();
  assert.ok(modVersion, "gradle.properties 找不到 mod_version");
  const problems = [];
  let assetsSeen = 0;
  for (const relative of DOC_FILES) {
    const text = await readFile(new URL(relative, import.meta.url), "utf8");
    for (const reference of text.match(DOC_ASSET_REFERENCE) ?? []) {
      const rest = reference.slice("MCAutoTranslationTool-".length);
      // `<mod_version>` 這類佔位符不在文件裡寫死版本；連版本段都沒有的寫法會落到下面的比對而紅。
      if (rest.startsWith("<") || rest.startsWith("*")) continue;
      assetsSeen += 1;
      if (!rest.startsWith(`${modVersion}-`)) {
        problems.push(
          `${relative}：資產檔名 \`${reference}\` 的版本段不是現行 ${modVersion}（或改用 \`<mod_version>\` 佔位符）`,
        );
      }
    }
    for (const stale of text.match(DOC_STALE_ARTIFACT) ?? []) {
      if (!stale.endsWith(`-${modVersion}.jar`)) {
        problems.push(`${relative}：產物檔名 \`${stale}\` 寫死了非現行版本（現行 ${modVersion}）`);
      }
    }
    for (const stale of text.match(DOC_STALE_RELEASE_DIR) ?? []) {
      if (stale !== `downloads/${modVersion}`) {
        problems.push(`${relative}：\`${stale}\` 不是現行版本目錄（現行 downloads/${modVersion}）`);
      }
    }
    for (const stale of text.match(DOC_STALE_VERSION_OPTION) ?? []) {
      if (stale !== `--version ${modVersion}`) {
        problems.push(`${relative}：\`${stale}\` 應該改成 \`--version ${modVersion}\` 或佔位符`);
      }
    }
  }
  assert.deepEqual(problems, [], "文件裡的版本號要跟 gradle.properties 的 mod_version 一致，或改用佔位符");
  // 反向驗證：抽取規則失效時上面會「一個都沒比到」而空過（四份 README 各抄一份 31 個檔名的清單）。
  assert.ok(
    assetsSeen >= 31,
    `只比對了 ${assetsSeen} 個資產檔名，抽取規則可能已經失效`,
  );
  // 發布派送用 `CHANGELOG.md` 的 `## <版本> - ` 區段當 release notes，而那個檢查在
  // `publish-release.yml`、只有 push `main` 之後才跑；同一個前提搬到 PR 階段，半套 bump 才會提早紅。
  const changelog = await readFile(new URL("../../CHANGELOG.md", import.meta.url), "utf8");
  const newestSection = changelog.match(/^## .+$/m)?.[0] ?? "";
  assert.ok(
    newestSection.startsWith(`## ${modVersion} - `),
    `CHANGELOG.md 最新的區段標題是「${newestSection}」，不是「## ${modVersion} - <日期>」`,
  );
  // 標題以外的內容沒有任何關卡（`DOC_FILES` 不含這份檔案），而 release notes 逐字取自本版區段。
  // NeoForge 沒有家族合併，任何「a–b」範圍都會被讀成「每一版都有」，補不上的缺口必須明寫。
  const notesBody = changelog.split(/^## /m)[1] ?? "";
  assert.doesNotMatch(
    notesBody,
    /NeoForge\s+\d+\.\d+(?:\.\d+)?\s*[–—-]\s*\d+\.\d+(?:\.\d+)?/,
    "CHANGELOG 的本版區段不得用範圍描述 NeoForge（它不是家族合併，範圍會被讀成每版都有）；請逐版列出或明確寫出缺口",
  );
});

// 「可安裝 JAR 數量」（今天是 31）與 48／51 一樣是手抄的頭條數字，但它被抄在 13 個檔案、四種措辭，
// 而先前的守只認 `page.tsx` 的「N 个经过校验的 JAR」。加一筆發布目標時 48 會被既有關卡逼著改，
// 同一句裡的 31 卻留在原地：官網同一頁會自相矛盾（文案 32、下載卡 32、另一句仍寫 31）。
const INSTALLABLE_CLAIMS = [
  /(\d+)\s+个可直接安装的 JAR/,
  /(\d+)\s+個可直接安裝的 JAR/,
  /(\d+)\s+个可直接安装的发布 JAR/,
  /(\d+)\s+個可直接安裝的發行 JAR/,
  /(\d+)\s+directly installable/,
  /(\d+)\s+个可安装 JAR/,
  /(\d+)\s+個可安裝 JAR/,
  /(\d+)\s+installable JAR/,
  /(\d+)\s+个经过校验的 JAR/,
  /(\d+)\s+個經過校驗的 JAR/,
  /(\d+)\s+颗可安装 JAR/,
  /(\d+)\s+顆可安裝 JAR/,
];

const INSTALLABLE_COVERAGE_DOCS = [
  "../../README.md",
  "../../docs/Zh-cn/README.md",
  "../../docs/Zh-tw/README.md",
  "../../docs/en/README.md",
  "../../docs/Zh-cn/BUILDING.md",
  "../../docs/Zh-tw/BUILDING.md",
  "../../docs/en/BUILDING.md",
  "../../docs/Zh-cn/COMPATIBILITY.md",
  "../../docs/Zh-tw/COMPATIBILITY.md",
  "../../docs/en/COMPATIBILITY.md",
  "../../docs/Zh-cn/USER_GUIDE.md",
  "../../docs/Zh-tw/USER_GUIDE.md",
  "../../docs/en/USER_GUIDE.md",
];

test("keeps the installable JAR count in every document in sync with the rendered page", async () => {
  const pageSource = await readFile(new URL("../app/page.tsx", import.meta.url), "utf8");
  const releaseVersion = pageSource.match(/const releaseVersion = "([^"]+)"/)?.[1];
  assert.ok(releaseVersion, "app/page.tsx 找不到 releaseVersion");
  const html = await (await render()).text();
  const installableJars = new Set(
    html.match(new RegExp(`download/v${escapeRe(releaseVersion)}/[A-Za-z0-9.-]+\\.jar`, "g")) ?? [],
  ).size;
  assert.ok(installableJars > 0, "頁面沒有渲染出任何 .jar 下載連結");

  const sources = [["website/app/page.tsx", pageSource]];
  for (const relative of INSTALLABLE_COVERAGE_DOCS) {
    sources.push([relative, await readFile(new URL(relative, import.meta.url), "utf8")]);
  }

  const problems = [];
  for (const [label, text] of sources) {
    let mentions = false;
    for (const pattern of INSTALLABLE_CLAIMS) {
      for (const [matched, digits] of text.matchAll(new RegExp(pattern.source, "g"))) {
        mentions = true;
        if (Number(digits) !== installableJars) {
          problems.push(
            `${label}：「${matched}」寫 ${digits}，但頁面渲染 ${installableJars} 個 .jar 下載連結`,
          );
        }
      }
    }
    // 這一句是使用者判斷「我該下載幾顆、有沒有漏」的第一手依據，所以每份文件都必須寫。
    if (!mentions) {
      problems.push(`${label} 完全沒有寫可安裝 JAR 數量（例如「31 个可直接安装的 JAR」）`);
    }
  }
  assert.deepEqual(problems, [], "可安裝 JAR 數量與真實下載不一致：改文件或改產生端，兩者必須同步");
});

function versionKey(text) {
  const parts = text.split(".").map((part) => Number(part));
  while (parts.length < 3) parts.push(0);
  return parts;
}

function compareVersions(left, right) {
  for (let index = 0; index < 3; index += 1) {
    if (left[index] !== right[index]) return left[index] - right[index];
  }
  return 0;
}

// 「N 個精確實作」的清單（1.13–…–26.3）在 8 個檔案 9 處手抄，而既有的關卡只綁那個數字。
// 新增一個 Fabric 版本目錄時，IMPLEMENTATION_CLAIMS 會逼七份文件把 51 改成 52，括號與冒號後面的
// 區間清單卻留在原處——加起來仍然是 51 個版本名，而使用者問的是「我的版本在不在清單裡」。
const FABRIC_LIST_SOURCES = [
  ["../../README.md", /覆盖：(.+?)。列表之外的版本/],
  ["../../docs/Zh-cn/README.md", /覆盖：(.+?)。列表之外的版本/],
  ["../../docs/Zh-tw/README.md", /涵蓋：(.+?)。清單以外/],
  ["../../docs/en/README.md", /exact-version implementations\*\*: ([\s\S]+?)\. A game version outside/],
  ["../app/page.tsx", /\$\{fabricImplementations\} 个精确版本实现（([^）]+)）/],
  ["../app/page.tsx", /个精确版本实现：([^。]+)。/],
  ["../../docs/Zh-cn/USER_GUIDE.md", /\|\s*(1\.13[^|\n]*?)（单一 JAR，\d+ 个精确实现）/],
  ["../../docs/Zh-tw/USER_GUIDE.md", /\|\s*(1\.13[^|\n]*?)（單一 JAR，\d+ 個精確實作）/],
  ["../../docs/en/USER_GUIDE.md", /\|\s*(1\.13[^\n]*?)\(single JAR, \d+ exact implementations\)/],
];

test("keeps the hand-copied Fabric version lists covering every implementation directory", async () => {
  // 權威是目錄名集合：26.x 的版本掛在 bundle 根，其餘在各自的 `versions/` 底下；
  // 1.13.0 的目錄在啟動器與文件裡都叫 `1.13`，所以去掉結尾的 `.0`。
  const directoryNames = [];
  for (const bundle of FABRIC_VERSIONED_BUNDLES) {
    const entries = await readdir(new URL(`../../platforms/fabric/${bundle}/versions`, import.meta.url));
    for (const name of entries) directoryNames.push(name.replace(/\.0$/, ""));
  }
  for (const name of await readdir(new URL("../../platforms/fabric/26.x", import.meta.url))) {
    if (/^\d/.test(name)) directoryNames.push(name);
  }
  const universe = [...new Set(directoryNames)].sort((left, right) =>
    compareVersions(versionKey(left), versionKey(right)),
  );
  assert.ok(universe.length > 0, "platforms/fabric 的實作目錄數算成 0");

  const problems = [];
  for (const [relative, pattern] of FABRIC_LIST_SOURCES) {
    const text = await readFile(new URL(relative, import.meta.url), "utf8");
    const listed = text.match(pattern)?.[1];
    if (!listed) {
      problems.push(`${relative}：找不到 Fabric 版本清單（${pattern.source}）`);
      continue;
    }
    const covered = new Set();
    for (const segment of listed.split(/[、，,;；\n]/)) {
      const entry = segment.trim();
      if (!entry) continue;
      const single = /^(\d+\.\d+(?:\.\d+)?)$/.exec(entry);
      if (single) {
        covered.add(single[1]);
        continue;
      }
      const span = /^(\d+\.\d+(?:\.\d+)?)\s*[–—-]\s*(\d+\.\d+(?:\.\d+)?)$/.exec(entry);
      if (!span) {
        problems.push(`${relative}：無法解析的版本區間「${entry}」`);
        continue;
      }
      const low = versionKey(span[1]);
      const high = versionKey(span[2]);
      let touchesUniverse = false;
      for (const version of universe) {
        const key = versionKey(version);
        if (compareVersions(low, key) <= 0 && compareVersions(key, high) <= 0) {
          covered.add(version);
          touchesUniverse = true;
        }
      }
      if (!touchesUniverse) {
        problems.push(`${relative}：區間「${entry}」對不到任何實作目錄`);
      }
    }
    const missing = universe.filter((version) => !covered.has(version));
    if (missing.length > 0) {
      problems.push(`${relative}：清單漏掉 ${missing.join("、")}`);
    }
  }
  assert.deepEqual(problems, [], "Fabric 版本清單必須涵蓋所有實作目錄：新增版本時 9 處清單都要一起改");
});

// 三份 `USER_GUIDE.md` 的 Forge 家族列先前把整條 1.21 線的頭尾（51.0.33–61.2.0）掛在單一家族上，
// 讀者裝 1.21.5 會照著去挑 61.2.0（那是 1.21.11 的建置，JAR 的 minecraft restriction 聯集不含它）。
// 權威是每個版本目錄的 `ext.forgeVersion`，所以逐版本比對，不再接受「整條線的區間」寫法。
const FORGE_FAMILY_GUIDES = [
  "../../docs/Zh-cn/USER_GUIDE.md",
  "../../docs/Zh-tw/USER_GUIDE.md",
  "../../docs/en/USER_GUIDE.md",
];
// 26.2 有自己的單列（`Forge 65.1.1`），不採 `版本＝Forge 版本` 的對照格式。
const FORGE_FAMILY_STANDALONE = new Set(["26.2"]);

test("keeps the documented Forge loader versions equal to each version's build.gradle", async () => {
  const expected = new Map();
  for (const tree of ["1.21", "26.x"]) {
    const versions = await readdir(
      new URL(`../../platforms/forge/${tree}/versions`, import.meta.url),
    );
    for (const version of versions) {
      const script = await readFile(
        new URL(`../../platforms/forge/${tree}/versions/${version}/build.gradle`, import.meta.url),
        "utf8",
      );
      const forgeVersion = script.match(/ext\.forgeVersion = "([^"]+)"/)?.[1];
      assert.ok(
        forgeVersion,
        `platforms/forge/${tree}/versions/${version}/build.gradle 找不到 ext.forgeVersion`,
      );
      expected.set(version, forgeVersion);
    }
  }
  assert.ok(expected.size > 0, "platforms/forge 的版本目錄數算成 0");

  const problems = [];
  for (const relative of FORGE_FAMILY_GUIDES) {
    const text = await readFile(new URL(relative, import.meta.url), "utf8");
    for (const [version, forgeVersion] of expected) {
      if (FORGE_FAMILY_STANDALONE.has(version)) continue;
      // 錨點抓不到也算失敗：整段被刪掉或改寫成區間時，這條才會紅。
      if (!text.includes(`${version}＝${forgeVersion}`)) {
        problems.push(
          `${relative}：找不到「${version}＝${forgeVersion}」（platforms/forge 該版本目錄的 ext.forgeVersion）`,
        );
      }
    }
  }
  assert.deepEqual(
    problems,
    [],
    "USER_GUIDE 的 Forge 版本必須逐版本對上 build.gradle 的 ext.forgeVersion：改 Forge 建置版本時三語表格要一起改",
  );
});
