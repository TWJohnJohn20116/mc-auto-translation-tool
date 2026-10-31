import assert from "node:assert/strict";
import { readFile, readdir } from "node:fs/promises";
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
  // 寫回 `downloads/<版本>/` 隨 PR 提交。存在時就以它為準再比一次；不存在時只提示、不紅燈——
  // 版本號 bump 後 `downloads/` 還沒重新產生時，網站檢查不該先壞掉（那個窗口一過就該看到它）。
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
    t.diagnostic(
      `downloads/${releaseVersion}/release-assets.txt 不存在，略過與發佈清單的比對`,
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
