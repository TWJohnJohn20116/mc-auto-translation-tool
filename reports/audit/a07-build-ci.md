# a07-build-ci 審計報告

範圍：`build.gradle`、`settings.gradle`、`gradle.properties`、`gradle/*.gradle`、`.github/workflows/{build,legacy-targets,prepare-release,publish-release}.yml`、`.github/actions/gradle-build/action.yml`、`.github/dependabot.yml`、`scripts/{prepare_release_assets,verify_release_jars,test_*}.py`、`scripts/build_all.ps1`、`scripts/build_single.ps1`、`scripts/README.md`、`scripts/tools/*.py`、`website/`（`app/`、`db/`、`tests/`、設定檔）、`README.md`、`CHANGELOG.md`、`docs/**` 的版本號與數量一致性

配額：BUG-S-031~036（小 6 條）＋ BUG-M-016~018（中，最多 3 條）

產出：BUG-C 0 / BUG-M 3 / BUG-S 6（實際）

> 審計方式：純讀取（`read`／`grep`／`glob`／`pwsh` 僅做檔案列舉與文字比對）。未執行任何 Gradle／javac／java／Python 腳本／測試，未修改任何原始碼檔案，未觸發 workflow。
> `build/`、`.gradle/`、`node_modules`、`dist/`、`__pycache__`、`_re/`、`downloads/` 內的 JAR 二進位未納入審計（只做檔名、數量與 zip entry 名稱比對）。
> 實際行數：`prepare_release_assets.py` 435 行、`verify_release_jars.py` 510 行（交辦說明的 380／443 是舊值，以下行號以實際檔案為準）。

---

### BUG-M-016 · 同步 1.4 與 26.3 之後沒跟上的文件與網站文案

- **嚴重度**：中 ｜ **信心**：高
- **位置**（同一根因，四組檔案一次列出）：
  1. **NeoForge 清單停在 4 顆**：`README.md:85-92`、`docs/Zh-cn/README.md:83-90`、`docs/Zh-tw/README.md:85-92`、`docs/en/README.md:88-99`
  2. **「33 → 16」停在 1.3.11**：`docs/Zh-cn/README.md:50-51`、`docs/Zh-tw/README.md:52-53`、`docs/en/README.md:48-50`
  3. **官網宣稱的覆蓋範圍與 legacy 發布狀態**：`website/app/page.tsx:6`、`:33`（同頁 `:55`、`:59` 自相矛盾）
  4. **最高版本停在 26.2**：`docs/Zh-tw/ARCHITECTURE.md:9`、`:35`、`:69`、`:115`
- **受影響範圍**：四份 README（含唯一的英文入口）、官網首頁與 SEO description、架構文件
- **現象**：1.4 與 26.3 之後，文件與網站停在舊狀態，且彼此矛盾。實測：
  - 四份 README 的 NeoForge 表各只有 **4 列**（1.20.1、1.21.1、1.21.3、1.21.11），正文寫死「所以正式版仍是**四个**独立 JAR」／「still ships **four** separate JARs」（`docs/en/README.md:92`）。實際：`downloads/1.4/` 有 19 個 `*-neoforge.jar`、`publish-release.yml:72-121` 的 `expected` 含 19 個 `-neoforge` 名稱、`prepare-release.yml:101-119` 有 19 個 `*-neoforge` 目標、`website/app/page.tsx:96-114` 也列 19 列。四份文件各只有 4 個 `neoforge.jar` 連結（實測 `neoforge links = 4`）。**15 顆已發布 JAR 沒有任何文件入口**。
  - 三份譯文 README 仍寫「33 個建置產物 → **16 個**可直接安裝的 JAR」。實際 `mod_version=1.4`（`gradle.properties:7`），`downloads/1.4/` 是 40 個完整 `.jar` ＋ 8 顆被分片（33 個 `.part-*` 檔）＝ 48 個邏輯目標，`SHA256SUMS.txt` 48 筆，`publish-release.yml:123` 要 48 顆、`:184` 要 31 JAR ＋ `SHA256SUMS.txt` = 32 個資產。根 `README.md:52` 與 `page.tsx:272` 都已是「48 → 31」。
  - `page.tsx:6` 的 `metadata.description` 寫「Fabric 覆盖 1.3.1–26.3」、`:33` 把 legacy Fabric bundle 標成「正式版已发布 / ready」，但 `downloads/1.4/` 沒有任何 legacy fabric 資產、`publish-release.yml:72-121` 的 `expected` 也沒有，而四份 README 都明寫「1.0.0–1.12.2（Fabric / Ornithe）尚未隨 v1.4 提供」（`README.md:63`、`docs/Zh-cn/README.md:61`、`docs/Zh-tw/README.md:63`、`docs/en/README.md:64`）；同頁 `:55`／`:59` 又寫「1.13–1.21.11」。一頁兩套覆蓋範圍。
  - `docs/Zh-tw/ARCHITECTURE.md` 四處仍寫 26.2 為上限（`:9`「1.3.1 至 26.2」、`:35`「45+ 版本合一」、`:69` 的 `platforms/fabric/26.x/` 版本清單沒有 26.3、`:115`「1.13 至 26.2」）。但 26.3 已存在並已納入：`platforms/fabric/26.x/26.3/` 目錄存在、`settings.gradle:122` 有 `platform-fabric-26.3`、`settings.gradle:185` 的 bundle 依賴含 `"26.3"`、`gradle.properties:305` 有 `minecraft_26_3_version=26.3`、`CHANGELOG.md:244` 明寫「新增支持 Minecraft 26.3（Fabric）……26.3 已纳入 `fabric-all` 的 26.x 族群」、`README.md:59` 也寫「26.1–26.3」。
- **觸發條件**：不必等特殊情況，現在打開這幾個檔案就是錯的。使用者情境：(a) 玩 NeoForge 1.20.4／1.20.6／1.21／1.21.4–1.21.10／26.x 其中任一版本 → README 表中找不到列，並被「四個獨立 JAR」誤導成不支援；(b) 只讀三份譯文任一 → 以為要下載 16 顆；(c) 看官網 `/` 或搜尋結果的 description → 被告知 1.3.1–1.12.2 的 Fabric 已發布，去 Releases 找不到；(d) 讀架構文件 → 以為 `platforms/fabric/26.x/` 只到 26.2。
- **根因**：`AGENTS.md:196-216`（發布流程第 5–7 步）把「更新文件與網站」寫成純人工步驟，且 `AGENTS.md:210` 自己承認「這一步沒有自動化，漏掉只會在事後被使用者發現」；`AGENTS.md:208-209` 更明說「網站測試只從 `app/page.tsx` 讀 `releaseVersion` 與 `metadata.title`，其餘文案不會被測試抓到，數字與覆蓋範圍必須人工確認」。實際上 `build.yml:63-102` 的「檢查版本號一致性」只驗版本號字串（`mod_version`／`UserAgent.VALUE`／`releaseVersion`／四個 `releases/download/v…/` 前綴），完全不碰「幾顆」「覆蓋哪幾個版本」。於是每一次發布／新增版本都會重演同一種漏改，只是漏的欄位不同：1.4 漏的是 NeoForge 列數、譯文數量、官網覆蓋範圍，26.3 漏的是架構文件的版本上限。
- **影響**：19 顆 NeoForge 裡 15 顆（79%）在主要文件沒有入口；三份文件（含唯一英文文件）的發布規模敘述與實際差 15 個建置產物／15 顆可安裝 JAR；官網宣稱一個 v1.4 完全不存在的支援範圍。`AGENTS.md:314-315` 特別指出排程紅燈會掩蓋真正失敗、文件錯誤同理——這些錯誤都不會被任何 CI 步驟攔下，只能靠使用者回報。
- **修法**：三件事一起做，避免下一次再漏：
  1. 產生表格：新增 `scripts/gen_release_docs.py --release-dir downloads/1.4 --reduced build/release-assets --version 1.4`，由 `scripts/prepare_release_assets.py` 的 `FORGE_FAMILIES`（`:29-54`）與 `:380` 的 `fabric-all` 推出 31 個可安裝名稱，一次產生四份語言的 NeoForge／Forge 表格區塊與「48 → 31」句子，取代手寫。
  2. 補 CI 閘門（放在 `build.yml:63` 那個步驟後面，用同一個 `failed` 旗標）：

```bash
# 文件宣稱的可安裝 JAR 數必須等於實際縮減結果
assets="$(find build/release-assets -maxdepth 1 -name '*.jar' | wc -l)"
for doc in README.md docs/Zh-cn/README.md docs/Zh-tw/README.md docs/en/README.md; do
  grep -q "\b${assets}\b" "$doc" || { echo "::error::$doc 沒有提到可安裝 JAR 數量 $assets"; exit 1; }
done
# NeoForge 每個已發布版本都必須在文件裡有連結
for jar in downloads/$version/*neoforge.jar downloads/$version/*neoforge.jar.part-aa; do
  [[ -e "$jar" ]] || continue
  name="$(basename "${jar%.part-aa}")"
  grep -q "$name" README.md || echo "::error::README.md 缺少 NeoForge 連結：$name"
done
```

  3. `page.tsx` 與 `ARCHITECTURE.md` 的手改：

```diff
-    "MC 自动翻译工具 1.4 正式版，下载按 Fabric／Forge／NeoForge 分组；Fabric 覆盖 1.3.1–26.3（1.16 已涵盖 1.16.0–1.16.4，1.13.2 也支援 HUD 指示器），Forge 覆盖 1.8.9–26.2，NeoForge 覆盖 1.20.1–26.2。",
+    "MC 自动翻译工具 1.4 正式版，下载按 Fabric／Forge／NeoForge 分组；Fabric 覆盖 1.13–1.21.11 与 26.1–26.3（1.16 已涵盖 1.16.0–1.16.4，1.13.2 也支援 HUD 指示器），Forge 覆盖 1.8.9–26.2，NeoForge 覆盖 1.20.1–26.2，每个 NeoForge 版本单独一颗 JAR。",
```

```diff
-  ["1.3.1–1.8.8、1.8–1.12.2", "Fabric（Legacy／Ornithe bundle）", "正式版已发布", "ready"],
+  ["1.3.1–1.8.8、1.8–1.12.2", "Fabric（Legacy／Ornithe bundle）", "源码可构建，未随 1.4 发布", "planned"],
```

（`planned` 樣式 class 是否已存在需一併確認；若沒有就用既有的非 `ready` 樣式。）

```diff
-| `platforms/fabric/26.x/` | 快照/未來版 | 26.1, 26.1.1, 26.1.2, 26.2 | Fabric Loom | **Java 25**| Fabric Loom 1.17 |
+| `platforms/fabric/26.x/` | 快照/未來版 | 26.1, 26.1.1, 26.1.2, 26.2, 26.3 | Fabric Loom | **Java 25**| Fabric Loom 1.17 |
```

  `docs/Zh-tw/ARCHITECTURE.md:35` 與 `:115` 的「45+」建議改成由 `settings.gradle:167-186` 的 `platformDependencies` 實際數量推算（fabric 家族共 3+8+6+5+5+7+12+5 = 51 個版本模組），避免再出現無來源的概數。

---

### BUG-M-017 · 補齊 class 位元碼對映表，21 顆資產現在整段不檢查

- **嚴重度**：中 ｜ **信心**：高
- **位置**：
  - `scripts/verify_release_jars.py:70-81`（`_class_major_for_minecraft` 的對映表）
  - `scripts/verify_release_jars.py:95-96`（`expected_major is None` 時直接 `return`，等於關掉整個閘門）
  - `scripts/verify_release_jars.py:260-267`（內嵌 Fabric JAR 用它自己的版本號取 major）
  - `scripts/verify_release_jars.py:84-89`（`_expected_class_major` 的內嵌正則不含 `-fabric-all`，與 `:23-26` 的 `RELEASE_JAR` 不一致）
- **受影響範圍**：每一次發布驗證；具體是 19 個 fabric 內嵌實現 ＋ 2 顆外層 NeoForge JAR
- **現象**：`_class_major_for_minecraft` 只認得四組寫法——`{"1.8.9","1.12.2","1.16.5"}` 與前綴 `1.13`/`1.14`/`1.15` → 52；`{"1.18.2","1.19.2","1.20.1"}` → 61；前綴 `1.21` → 65；前綴 `26.` → 69；**其餘一律 `return None`**（`:81`）。而 `_validate_class_versions`（`:92-96`）的第一件事就是 `if expected_major is None: return`。於是「查不到規則」被下游解讀成「這個 JAR 不需要檢查」，class 位元碼閘門整段不執行，且沒有任何訊息。
  缺的具體版本共 19 個：`1.16`、`1.16.1`、`1.16.2`、`1.16.3`、`1.16.4`、`1.17`、`1.17.1`、`1.18`、`1.18.1`、`1.19`、`1.19.1`、`1.19.3`、`1.19.4`、`1.20`、`1.20.2`、`1.20.3`、`1.20.4`、`1.20.5`、`1.20.6`。
  實際不受保護的資產共 **21 顆**：
  - **19 個 `fabric-all` 內嵌實現**：`_validate_fabric:256-273` 對每個 `META-INF/jars/universal-translator-<mc>.jar` 用 `nested_match.group(1)` 呼叫 `_class_major_for_minecraft`（`:263-267`），上列 19 個版本全部落到 `None` → `verify_jar_bytes` 的 `:373-378` 拿到 `None`、`_validate_class_versions` 立刻 `return`。
  - **2 顆外層 NeoForge**：`mc1.20.4-neoforge`、`mc1.20.6-neoforge` 的 mc 標籤就是 `1.20.4`／`1.20.6`，同樣回 `None`。
  - 另外 4 個 fabric bundle 的外層標籤 `1.16.x`／`1.17-1.18.x`／`1.19.x`／`1.20.x` 也回 `None`，但這些是聚合 JAR、頂層可能不含 `.class`，影響較小；`-fabric-all.jar` 本身則因 `:86-89` 的正則不認得 `-fabric-all` 而永遠回 `None`（同一個「`None` 即跳過」的失效模式）。
- **觸發條件**：任何一次發布驗證都會走到；不需要特殊輸入。要讓它變成真實故障，只需要某個 `1.16`–`1.20.6` 的內嵌實現或 1.20.4／1.20.6 的 NeoForge 模組被誤編成較高的 `options.release`（例如某個版本的 `build.gradle` 抄到隔壁的 21 而沒改成 17／8）。
- **根因**：對映表用「列舉＋前綴」硬寫，缺條目時回 `None`，而 `None` 在下游被當成「不適用」而非「規則缺失」。驗證器對「不認識的版本」是靜默放行，不是報錯——這正好違反 `AGENTS.md:353`「不要為了讓 CI 變綠而刪除測試或放寬驗證」的精神：這裡是驗證自己把自己放寬了。
- **影響**：`AGENTS.md:242-243` 明列 `check`／`verifyBundle`／`verifyLoaderSelection`／`verifyPreparedReleaseAssets`／`verify_release_jars.py` 的驗證「都必須保留」，而 `verify_release_jars.py` 的 class 位元碼比對正是它存在的主要理由（Java 8 的 1.16.5 玩家若拿到 major 61 的 class，會直接 `UnsupportedClassVersionError` 而無法啟動）。現在 48 顆目標裡有 21 顆的這道防線是空的，而 CI 顯示全綠。
- **修法**：缺表即報錯，並把 bundle 區間標籤明確列為「不適用」。

```python
# 1.20.4 / 1.20.6 的實際 options.release 請從 platforms/neoforge/{1.20/versions/1.20.4,1.20/versions/1.20.6}/build.gradle 取值後填入
_CLASS_MAJORS = {
    "1.16": 52, "1.16.1": 52, "1.16.2": 52, "1.16.3": 52, "1.16.4": 52,
    "1.17": 61, "1.17.1": 61, "1.18": 61, "1.18.1": 61,
    "1.19": 61, "1.19.1": 61, "1.19.3": 61, "1.19.4": 61,
    "1.20": 61, "1.20.2": 61, "1.20.3": 61, "1.20.4": 61, "1.20.5": 61, "1.20.6": 61,
}


def _class_major_for_minecraft(minecraft: str) -> int | None:
    if minecraft in _CLASS_MAJORS:
        return _CLASS_MAJORS[minecraft]
    if "x" in minecraft or "-" in minecraft:
        # bundle 的區間標籤（1.16.x、1.17-1.18.x…）沒有單一位元碼規則，
        # 內嵌實現各自有具體版本，會走上面的表。
        return None
    if minecraft.startswith("1.21"):
        return 65
    if minecraft.startswith("26."):
        return 69
    if minecraft in {"1.8.9", "1.12.2", "1.16.5"} or minecraft.startswith(("1.13", "1.14", "1.15")):
        return 52
    if minecraft in {"1.18.2", "1.19.2", "1.20.1"}:
        return 61
    raise VerificationError(f"no class-version rule for Minecraft {minecraft}")
```

並把 `_expected_class_major` 改成共用 `RELEASE_JAR`，避免第二套檔名正則：

```diff
-    match = re.search(
-        r"-mc(.+)-(?:fabric|forge|neoforge|forge-neoforge)\.jar$", outer_name
-    )
-    return _class_major_for_minecraft(match.group(1)) if match else None
+    match = RELEASE_JAR.fullmatch(outer_name)
+    if match is None:
+        return None
+    mc = re.search(r"-mc(.+?)(?:-(?:fabric|forge|neoforge|forge-neoforge))?$", outer_name)
+    return _class_major_for_minecraft(mc.group(1)) if mc else None
```

補一條回歸測試（目前 `test_verify_release_jars.py` 沒有任何案件會走到 `_class_major_for_minecraft` 的 `None` 分支）：

```python
def test_every_published_minecraft_label_has_a_class_rule(self) -> None:
    for minecraft in ("1.16", "1.16.4", "1.17", "1.18.1", "1.19.4", "1.20.4", "1.20.6"):
        assert _class_major_for_minecraft(minecraft) is not None
    with self.assertRaises(VerificationError):
        _class_major_for_minecraft("9.99")
```

---

### BUG-M-018 · 修掉只看第一個下載連結的版本號一致性檢查（假綠燈通道）

- **嚴重度**：中 ｜ **信心**：高
- **位置**：`.github/workflows/build.yml:89-92`

```yaml
89:          for readme in README.md docs/Zh-cn/README.md docs/Zh-tw/README.md docs/en/README.md; do
90:            linked="$(grep -o 'releases/download/v[^/]*/' "$readme" | head -n 1 | sed 's|releases/download/v||; s|/$||')"
91:            check "$readme 的下載連結" "$linked"
92:          done
```

- **受影響範圍**：整個 PR 必經路徑的「版本號一致性」閘門；四份文件各有 16 個下載連結
- **現象**：`grep -o … | head -n 1` 只取**每一份文件的第一個** `releases/download/v…/`。四份文件目前各有 16 個連結（實測 `matches=16`，`distinct=1`），只要第一顆（Fabric 那顆）更新到新版本，其餘 15 個 Forge／NeoForge 連結即使整批留在舊版本號，這一步仍會輸出「版本號一致」並讓 CI 綠燈。
- **觸發條件**：發布新版本時只改了 Fabric 那一行連結（或只改表格第一列），其餘 15 行漂回 `v1.3.11` → `CI 總結` 全綠、分支保護放行；合併後 Release 上那些連結 404。`AGENTS.md:204-205` 記錄過同類後果（「新增或移除目標時四個檔案都要一起改，否則會指向不存在的檔案」），那次靠人工發現。
- **根因**：`head -n 1` 原本只是為了讓 `grep` 的多行輸出能餵給單一變數，但它同時把「部分漂移」——也就是最常見的漏改——變成不可偵測。檢查的語意是「這份文件的所有下載連結都指向目前版本」，實作卻退化成「這份文件至少有一個連結指向目前版本」。
- **影響**：這條檢查存在的唯一目的（`build.yml:67-69` 的註解：「讓漂移在 PR 上就紅，而不是等使用者發現」）在部分漂移時完全失效。它同時只比對版本號、不比對檔名，所以「版本對但檔名打錯」也抓不到——這種錯正好是本報告 BUG-M-016 那 15 顆 NeoForge 連結漏列的鄰近風險。
- **修法**：把「取第一個」改成「必須唯一」。

```bash
for readme in README.md docs/Zh-cn/README.md docs/Zh-tw/README.md docs/en/README.md; do
  prefix="$(grep -o 'releases/download/v[^/]*/' "$readme" | sed 's|releases/download/v||; s|/$||' | sort -u)"
  if [[ "$(printf '%s\n' "$prefix" | wc -l)" -ne 1 ]]; then
    echo "::error::$readme 的下載連結混用了多個版本：$(printf '%s' "$prefix" | tr '\n' ' ')"
    failed=1
  else
    check "$readme 的下載連結" "$prefix"
  fi
done
```

檔名存在性建議一併驗（需對照 `prepare_release_assets.py` 的 31 個可安裝名稱白名單，因為 `fabric-all.jar` 與 4 個 Forge 家族檔是發布時才生成的縮減名稱，不直接在 `downloads/<version>` 裡）：

```bash
for name in $(grep -oh 'releases/download/v[^/]*/[^)"]*\.jar' README.md docs/*/README.md | sed 's|.*/||' | sort -u); do
  python3 scripts/verify_release_jars.py --checksum-file build/release-assets/SHA256SUMS.txt \
    "build/release-assets/$name" >/dev/null 2>&1 \
    || { echo "::error::文件連結指向不存在的可安裝 JAR：$name"; failed=1; }
done
```

---

### BUG-S-031 · 補上 upload-artifact 的 PR 排除條件

- **嚴重度**：小 ｜ **信心**：高
- **位置**：
  - `.github/workflows/build.yml:104-113`（`上傳檢查報告`，`:105` 是裸的 `if: failure()`）
  - 同類殘留：`.github/workflows/legacy-targets.yml:159-165`、`:220-226`、`:287-293`（三個 `upload-artifact` 步驟完全沒有 `if:`）
- **受影響範圍**：所有 PR 的失敗執行；artifact 儲存配額
- **現象**：`build.yml` 另外三個 `upload-artifact`（`:298-308`、`:392-407`、`:476-487`）都帶 `github.event_name != 'pull_request'`，只有 `:105` 是裸的 `if: failure()`；`legacy-targets.yml` 三個步驟連 `if:` 都沒有。`AGENTS.md:292-295` 的規則寫的是「**所有** `upload-artifact` 步驟的 `if:` 都帶 `github.event_name != 'pull_request'`」，`retention-days` 一律 3 天（這部分四份 workflow 都符合：`build.yml:113`、`:308`、`:407`、`:487`、`legacy-targets.yml:165`、`:226`、`:293`、`prepare-release.yml:231`）。
- **觸發條件**：任何 PR 的 `核心檢查` 失敗（`check` 跑 `--no-daemon check`，含 `validateNeoForgeModuleCoordinates`、`verifyPreparedReleaseAssets` 等重量級驗證，失敗率不低）→ `check-reports` 產物被上傳並保留 3 天。
- **根因**：`check-reports` 是除錯用產物，寫的時候直覺用了 `if: failure()`，沒有套用同一條產物政策；`legacy-targets.yml` 因為只由排程／手動觸發，加這個條件「看起來多餘」所以沒加。但政策是無條件陳述，實作在兩處不齊。
- **影響**：功能上不致命（3 天過期），但違反明文規則；而 PR 正是最頻繁的觸發源——`AGENTS.md:293-294` 自己說明「PR 是最頻繁的觸發來源，產物對審查沒有幫助卻會佔用儲存與頻寬」。日後若有人照 `:105` 的樣式新增產物步驟，缺口會擴大。
- **修法**：

```diff
       - name: 上傳檢查報告
-        if: failure()
+        if: failure() && github.event_name != 'pull_request'
         uses: actions/upload-artifact@v7
```

`legacy-targets.yml` 三個步驟同理（該 workflow 目前只有 `schedule`／`workflow_dispatch`，加這個條件是為了讓政策在檔案層面一致，也為了日後真的加上 `pull_request` 觸發時不會默默違規）：

```diff
       - name: 上傳舊版 Fabric JAR
+        if: github.event_name != 'pull_request'
         uses: actions/upload-artifact@v7
```

---

### BUG-S-032 · 拿掉網站測試裡寫死的 JAR 數量

- **嚴重度**：小 ｜ **信心**：高
- **位置**：`website/tests/rendered-html.test.mjs:48`（`assert.match(html, /31 个经过校验的 JAR/);`）
- **受影響範圍**：`website` job（PR 動到 `website/` 時、push `main`、Dependabot 的 npm PR）
- **現象**：測試把「31 个经过校验的 JAR」整句寫死，而這個數字只存在於 `page.tsx:272` 的文案裡。同檔 `:20-21` 的註解明說「版本號只在 `app/page.tsx` 定義一次，測試從原始碼取，這樣每次發布都不必改測試」，`:23` 的 `releaseVersion` 也確實這樣取；但數量沒走同一條路。同時 `:52-58` 只驗「不同下載連結的數量是 32」，**沒有**驗「文案宣稱的數量 == 實際下載連結數」，所以文案與連結數可以各自漂移而測試全綠。
- **觸發條件**：下一次發布（1.5）把 `page.tsx:272` 的「31」改成新數字 → `:48` 立刻紅。反過來，若有人只改 `page.tsx:272` 卻漏改下載卡，`:48` 仍會過（它只比對那串字面）。
- **根因**：`:48` 是快照式斷言——把當下的文案整句抄進測試，而不是斷言「文案的數字等於連結數」。這與 `:20-21` 註解所描述的政策（從原始碼推導、不寫死）相反；`AGENTS.md:305-307` 只規定版本號與標題要從原始碼取，沒規定數量，於是數量成了漏網的寫死值。
- **影響**：每次發布都要多改一處測試（`AGENTS.md:210` 已在抱怨「這一步沒有自動化，漏掉只會在事後被使用者發現」，這裡又多加一個必改點）；而且它守的不是真契約。改成「數量 == 連結數」才真的能抓到 BUG-M-016 那類漏改。
- **修法**：

```diff
-  assert.match(html, /31 个经过校验的 JAR/);
+  // 文案宣稱的數量必須等於實際渲染出的 .jar 連結數。
+  const claimed = source.match(/(\d+) 个经过校验的 JAR/)?.[1];
+  assert.ok(claimed, "app/page.tsx 找不到「N 个经过校验的 JAR」");
+  const jarLinks = new Set(releaseDownloads.filter((href) => href.endsWith(".jar"))).size;
+  assert.equal(
+    Number(claimed),
+    jarLinks,
+    `文案宣稱 ${claimed} 顆 JAR，但頁面渲染出 ${jarLinks} 個 .jar 連結`,
+  );
```

---

### BUG-S-033 · 讓本機建置腳本對齊 48 個發布目標

- **嚴重度**：小 ｜ **信心**：高
- **位置**：
  - `scripts/build_all.ps1:39-46`（JDK 25 清單）、`:50-62`（JDK 21 清單）、`:66-71`（JDK 17 清單）
  - `scripts/build_single.ps1:24`、`:32`（legacy 別名）、`:54`（組出的 task 名）
- **受影響範圍**：任何照 `AGENTS.md` 想在本機（或另一台自建 runner）走「全量建置 → `_assemble_*.py`」手動發布流程的維護者
- **現象**：`build_all.ps1` 註解與 `README.md:9` 都寫「全平台全量構建」，實際只 invoke 33 個專案建置（JDK25 5 個＋JDK21 21 個＋JDK17 5 個＋legacy 2 個），對得上 `prepare-release.yml` 48 個發布目標的只有 **32 個**：
  - 缺 15 個 NeoForge：`1.20.4`、`1.20.6`、`1.21`、`1.21.2`、`1.21.4`–`1.21.10`、`26.1`、`26.1.1`、`26.1.2`、`26.2`（這些只存在於 `prepare-release.yml:105-119`；`build.yml` 與 `legacy-targets.yml` 也沒有全部涵蓋）。
  - 用 `fabric-1.16.5`（`:50`、`:54`）取代發布清單要求的 `fabric-1.16.x` bundle（`prepare-release.yml:76`，`jar_dir` 是 `platforms/fabric/1.16/bundle`）。單版本專案產出的是 `1.16.5` 專屬 JAR，不是涵蓋 1.16–1.16.5 的 bundle。

  另外 `build_single.ps1:24`／`:32` 的別名只接受 `1.8.9`／`legacy-1.8.9`／`forge-1.8.9`（1.12.2 同理），但 `AGENTS.md:156-157` 與 `legacy-targets.yml:67-68` 的正式目標名是 `forge-legacy-1.8.9`／`forge-legacy-1.12.2`。傳正式名稱不會命中兩個 `if`，會落到 `:54` 組出 `:platform-forge-legacy-1.8.9:build`——而 `settings.gradle` 沒有這個專案（1.8.9／1.12.2 是獨立 Gradle 建置），Gradle 直接 task not found。
- **觸發條件**：(a) 用 `build_all.ps1` 建完再接 `scripts/tools/_assemble_*.py`，會在缺的 15 個 NeoForge 目標上撞 `FileNotFoundError`，而 `1.16.x` 那顆會撿到單版本 JAR 當 bundle 用；(b) 依 `AGENTS.md` 的目標名呼叫 `.\scripts\build_single.ps1 forge-legacy-1.8.9` → task not found，且錯誤訊息不指向真正原因。
- **根因**：`prepare-release.yml` 的目標清單在改成 48 項動態 matrix（`plan` 的 `TARGETS_JSON`）之後，這兩支本機腳本沒跟上——它們保存的仍是較早期的手寫清單。三處（`prepare-release.yml`／`build_all.ps1`／`build_single.ps1`）各有一份目標認知，沒有任何交叉檢查。
- **影響**：`AGENTS.md` 明確禁止在本機跑這些腳本，所以不會污染 CI；但它們仍是文件化的手動替代路徑（`AGENTS.md:191-192` 把 `_assemble_*.py` 指為手動流程的參考實作），「本機全量建置通過」不代表 48 個目標都能建，會誤導維護者對發布完整性的判斷。屬於 `§2` 的「命名不一致＋維護風險」。
- **修法**：`build_all.ps1` 補 15 個 NeoForge（26.x 那 4 個進 `$jdk25Tasks`，其餘 11 個進 `$jdk21Tasks`），並把 `:platform-fabric-1.16.5:build` 改成 `:platform-fabric-1.16.x:build`、`fabric-1.16.5` 改成 `fabric-1.16.x`；`build_single.ps1` 的別名改成容納正式名稱：

```powershell
# build_all.ps1:39
$jdk25Platforms = "fabric-26.x,forge-26.1,forge-26.1.1,forge-26.1.2,forge-26.2," +
    "neoforge-26.1,neoforge-26.1.1,neoforge-26.1.2,neoforge-26.2"
# build_all.ps1:50 —— fabric-1.16.5 改成 bundle
$jdk21Platforms = "fabric-1.13.x,fabric-1.14-1.15.x,fabric-1.16.x,fabric-1.17-1.18.x,fabric-1.19.x,fabric-1.20.x,fabric-1.21.x," + ...
```

```powershell
# build_single.ps1:24
if ($cleanTarget -match '^(forge-)?legacy-1\.8\.9$' -or $cleanTarget -eq '1.8.9' -or $cleanTarget -eq 'forge-1.8.9') {
# build_single.ps1:32
if ($cleanTarget -match '^(forge-)?legacy-1\.12\.2$' -or $cleanTarget -eq '1.12.2' -or $cleanTarget -eq 'forge-1.12.2') {
```

長期建議：讓 `build_all.ps1` 直接讀 `prepare-release.yml` 的 `TARGETS_JSON`（或抽成 `scripts/targets.json`），三處共用一份清單。

---

### BUG-S-034 · 收攏 614400 與 part 後綴公式，並讓驗證端檢查分片尺寸

- **嚴重度**：小 ｜ **信心**：高
- **位置**：
  - `scripts/prepare_release_assets.py:57-58`（`_part_suffix`）
  - `scripts/verify_release_jars.py:399-400`（`_part_suffix`，與上者逐字相同）
  - `.github/workflows/prepare-release.yml:282`（`part_size = 614400`）、`:291`（同一條後綴公式的第三份實作）
  - `scripts/tools/_assemble_1_3_11_beta.py:22`（`PART = 614400`）、`:113`（第四份後綴實作）
- **受影響範圍**：`downloads/<version>` 的分片慣例；`prepare-release` → `publish-release` 整條鏈
- **現象**：`AGENTS.md:189-191` 規定「fabric 的 JAR 超過 614400 bytes（600 KiB）會切成 `.jar.part-aa`、`.jar.part-ab`…」，但這個常數在整個 repo 只出現兩次（workflow 的內嵌 python 與 `_assemble_1_3_11_beta.py`），**兩支被正式使用的腳本都不含它**；後綴公式 `chr(ord("a") + i // 26) + chr(ord("a") + i % 26)` 有四份獨立實作。驗證端 `verify_release_jars.py:403-422` 只驗「從 `part-aa` 連續、至少 2 片、名稱符合 `[a-z]{2}`」，**完全不驗分片大小與片數**，所以「600 KiB」這個規格沒有任何檢查在守。
- **觸發條件**：改門檻時只改到一處。例如把 `prepare-release.yml:282` 的 `614400` 誤打成 `6144`，一顆 2 MB 的 fabric JAR 會切成約 340 片——片數仍小於 676（後綴仍是兩字母），`_split_parts` 的連續性檢查照樣通過，`_read_file_or_split_jar` 接回後 SHA256 也正確，於是 `publish-release.yml` 一路綠燈，Release 上出現 340 個分片檔案。反過來，若把 `zipfile` 以外的某一份後綴公式改成單字母（`part-a`…`part-z`），`PART_PATTERN`／`SPLIT_JAR_PART` 的 `[a-z]{2}` 會讓驗證直接失敗，但錯誤訊息只說「must be contiguous from part-aa」，不會指向「兩份公式不一致」。
- **根因**：門檻與命名規則被視為「產生端的慣例」，只寫在產生端；驗證端只認命名、不認尺寸，兩者沒有共用的權威常數，也沒有契約測試。
- **影響**：`AGENTS.md:192` 的「同一顆 JAR 同時存在完整檔與分片會被驗證器視為錯誤」這條有守（見下方無問題清單第 1 條），但「分片大小」這半條規格完全沒守。分片尺寸失守會讓 `downloads/` 暴增、`git` 物件暴增，且 `publish-release.yml:57-71` 的 `cat "${parts[@]}"` 依賴檔名字典序，片數一多就更容易在人工介入時出錯。
- **修法**：把版面常數集中到單一模組，兩端與 workflow 都引用它。

```python
# scripts/release_layout.py（新增）
PART_SIZE = 614400                                   # 600 KiB，見 AGENTS.md:189-191
PART_PATTERN = re.compile(r"^(?P<jar>.+\.jar)\.part-(?P<suffix>[a-z]{2})$")

def part_suffix(index: int) -> str:
    return chr(ord("a") + index // 26) + chr(ord("a") + index % 26)
```

```diff
 # verify_release_jars.py:_split_parts，接在連續性檢查之後
     for index, part in enumerate(parts):
         ...
+    for part in parts[:-1]:          # 最後一片允許較小
+        if part.stat().st_size != PART_SIZE:
+            raise VerificationError(
+                f"split JAR part has unexpected size: {part.name} "
+                f"({part.stat().st_size} != {PART_SIZE})"
+            )
```

`prepare-release.yml:280-296` 的內嵌 python 改成 `python3 scripts/split_release_jars.py "$target_dir"`，由該腳本 import `release_layout`，讓門檻只剩一個定義。

---

### BUG-S-035 · 統一 `verify_release_jars.py` 的兩套發布檔名正則

- **嚴重度**：小 ｜ **信心**：高
- **位置**：`scripts/verify_release_jars.py:23-26`（`RELEASE_JAR`）vs `scripts/verify_release_jars.py:86-89`（`_expected_class_major` 內嵌的正則）
- **受影響範圍**：class 位元碼閘門對「哪些檔案算發布 JAR」的判斷；未來任何新檔名
- **現象**：同一支檔案對「發布 JAR 的檔名」有兩套答案。`RELEASE_JAR`（`:23-26`）的 alternation 是 `(?:-mc.+-(?:fabric|forge|neoforge|forge-neoforge)|-fabric-all)`，含 `-fabric-all`；`_expected_class_major`（`:86-89`）用的是 `-mc(.+)-(?:fabric|forge|neoforge|forge-neoforge)\.jar$`，**不含** `-fabric-all`。
- **觸發條件**：`MCAutoTranslationTool-1.4-fabric-all.jar` 目前頂層不含 `.class`，所以 `_expected_class_major` 回 `None` 剛好無害。但只要新增任何 `-xxx-all` 形式的聚合資產、或 `fabric-all` 之後真的在頂層放 class，class 版本閘門就會靜默失效（`_validate_class_versions:95-96` 對 `None` 直接 `return`）。這與 BUG-M-017 共用同一個「`None` 即跳過」的失效模式，但成因不同：M-017 是「查得到版本、查不到規則」，這裡是「根本解析不到版本」。
- **根因**：兩處正則各自演化。`_expected_release_version`（`:65-67`）走 `RELEASE_JAR`，`_expected_class_major` 自己重寫了一份更窄的，沒有共用。
- **影響**：命名規則一改就出現「版本解析得到、class 規則解析不到」的半套行為，而且不會有任何錯誤訊息——驗證器會安靜地少做一項檢查。屬於 `§2` 的「命名不一致」與「罕見路徑未處理」。
- **修法**：讓 `_expected_class_major` 共用 `RELEASE_JAR`，並在解析不到 mc 段時明確報錯而不是回 `None`（與 BUG-M-017 的修法一起做最省事）：

```diff
 def _expected_class_major(label: str) -> int | None:
     outer_name = _archive_basename(label)
-    match = re.search(
-        r"-mc(.+)-(?:fabric|forge|neoforge|forge-neoforge)\.jar$", outer_name
-    )
-    return _class_major_for_minecraft(match.group(1)) if match else None
+    if RELEASE_JAR.fullmatch(outer_name) is None:
+        return None                     # 不是發布 JAR，交給呼叫端處理
+    mc = re.search(r"-mc([0-9][0-9A-Za-z.\-]*?)(?:-(?:fabric|forge|neoforge|forge-neoforge))?\.jar$", outer_name)
+    if mc is None:
+        raise VerificationError(f"cannot derive a Minecraft label for class checks: {outer_name}")
+    return _class_major_for_minecraft(mc.group(1))
```

---

### BUG-S-036 · 修好 scripts/README.md 兩個範例，兩條指令都必然失敗

- **嚴重度**：小 ｜ **信心**：高
- **位置**：`scripts/README.md:11`（`prepare_release_assets.py` 那一列）、`scripts/README.md:12`（`verify_release_jars.py` 那一列）
- **受影響範圍**：`scripts/` 的第一站說明文件；任何想手動重跑發布資產處理的人或代理
- **現象**：第 11 行的範例寫死 `--release-dir downloads\1.3.11-beta.2 … --version 1.3.11-beta.2`，但 `downloads/` 目前只有 `1.4` 一個目錄（`prepare-release.yml:336-345` 的「移除舊版本的 downloads 目錄」會主動刪掉其他版本），`gradle.properties:7` 是 `mod_version=1.4`；照抄只會得到「release directory is missing」。第 12 行的範例是裸的 `python scripts\verify_release_jars.py`，但該腳本實際需要 `--checksum-file` 與至少一個 JAR 路徑（`build.gradle:382-386` 與 `publish-release.yml:151-154` 都是這樣呼叫），照抄會以參數錯誤結束。
- **觸發條件**：任何人（或代理）第一次照這份 README 試跑，不需特殊狀態。
- **根因**：文件跟著寫死的版本號走，而 `verify_release_jars.py` 的介面在改成「必須給 JAR 或 `--checksum-file`」之後沒同步。`build.yml` 的 `lint` job（`:140-166`）只檢查 workflow 的 YAML 與 shell，不檢查 markdown 裡的指令範例。這是 `1.3.11` 在「可執行操作指令」層級唯一的殘留——其餘出現處都合理：`scripts/test_verify_release_jars.py:303/305/321` 是驗證版本號解析的 fixture、`scripts/verify_release_jars.py:58` 是 Windows 路徑的 docstring 範例、`scripts/tools/_assemble_1_3_11_beta.py:2` 是凍結的歷史腳本、`CHANGELOG.md` 是歷史紀錄（其中 `:255`「33 个精确发布目标与 16 个最终安装包」正好佐證 BUG-M-016 的 33→16 是 1.3.11 的數字）。
- **影響**：`AGENTS.md:191-192` 把 `_assemble_*.py` 指為手動流程的參考實作，而 `scripts/README.md` 是進入 `scripts/` 的第一站；範例失效會讓人繞回「自己拼指令」，或誤以為腳本壞了。純文件問題，不影響 CI。
- **修法**：

```diff
-| **`prepare_release_assets.py`** | Python 3 | 將各平台產物組裝為發布用 JAR（含超壓縮 `fabric-all.jar` 與 SHA256 驗證碼） | `python scripts\prepare_release_assets.py --release-dir downloads\1.3.11-beta.2 --output-dir build\release-assets --version 1.3.11-beta.2` |
-| **`verify_release_jars.py`** | Python 3 | 驗證下載目錄的完整結構與 Fabric Loader 選版邏輯 | `python scripts\verify_release_jars.py` |
+| **`prepare_release_assets.py`** | Python 3 | 將各平台產物組裝為發布用 JAR（含超壓縮 `fabric-all.jar` 與 SHA256 驗證碼） | `python scripts\prepare_release_assets.py --release-dir downloads\1.4 --output-dir build\release-assets --version 1.4` |
+| **`verify_release_jars.py`** | Python 3 | 驗證下載目錄的完整結構與 Fabric Loader 選版邏輯 | `python scripts\verify_release_jars.py --checksum-file downloads\1.4\SHA256SUMS.txt --require-complete-checksums downloads\1.4\*.jar` |
```

並在檔頭加一句「這些指令只供 GitHub Actions 與已授權的環境使用；本機不得執行（見 `AGENTS.md`）」，避免範例被當成本機操作的邀請。

---

## 已排查但判定無問題

1. **`publish-release.yml:122-126` 的「必須正好 48 顆 JAR」不會被分片檔誤判**：`downloads/1.4/` 實際是 40 個完整 `.jar` ＋ 33 個 `.part-*`（8 顆 fabric JAR 被切開，`part-aa` 正好 8 個），單看 `find -name '*.jar'` 只會數到 40。但 `:57-71` 的迴圈會先把每一組 `*.jar.part-aa` `cat` 回完整檔並 `rm -f` 掉分片，等到 `:122` 才計數，所以計數時是 48。這一點很容易被誤判成 BUG-C，實際正確。
2. **`publish-release.yml` 的 `expected` 陣列與 `prepare-release.yml:72-119` 的 `TARGETS_JSON` 逐字相同**：用 regex 抽出兩邊的 48 個目標名比對，`prepare-only` 與 `expected-only` 都是空集合。`AGENTS.md:223-230` 的「兩者不是同時改」是流程規定，但兩份清單目前沒有漂移。
3. **`build.gradle:12-16` 的 toolchain 前綴清單是完整的**：`platform-fabric-26.`、`platform-forge-26.`、`platform-neoforge-26.` 三個前綴都在，而 `settings.gradle` 裡的 26.x 專案名（`platform-fabric-26.{1,1.1,1.2,2,3,x}`、`platform-forge-26.{1,1.1,1.2,2}`、`platform-neoforge-26.{1,1.1,1.2,2}`）全部命中這三個前綴。`AGENTS.md:247-251` 記錄的 `platform-neoforge-26.` 漏加事故已修好，無殘留。
4. **`settings.gradle` 的 142 個 `platformProjects` 目錄全部存在**；且 `platforms/**/mods.toml`（32 個）與 `platforms/**/neoforge.mods.toml`（21 個）共 53 個 metadata 檔**全部**含 `modLoader = "javafml"`。`AGENTS.md:252-255` 記錄的 4 個 26.x toml 缺欄位事故已修好，無殘留。
5. **`.github/actions/gradle-build/action.yml` 完全符合 `AGENTS.md:323-332` 的三項要求**：`:34` 有 `set +e`（`:35` 另補 `set -uo pipefail` 保留 pipefail）、`:57` 的重試 grep 用 `grep -qiE`（有 `-i`）、訊息清單已含 `Failed to download`／`DownloadException`／`Failed to setup Minecraft` 等下載類字樣。`:29-33` 的註解也正確描述了「runner 用 `bash -e`」這個根因。
6. **`build.yml` 的 `ci-status.needs` 覆蓋全部 job、`pull_request` 沒有 paths 過濾**：`needs`（`:574-581`）列出 `check`、`lint`、`plan`、`platform-build`、`legacy-build`、`release-assets`、`website` 共 7 個，與檔案內實際定義的 7 個 job 完全對應，沒有漏加（`AGENTS.md:301` 的規則成立）；`on.pull_request`（`:4`）是裸的、沒有 `paths`／`paths-ignore`（符合 `AGENTS.md:339-340`）。
7. **workflow 沒有使用 GitHub 表達式不存在的字串函式**：四份 workflow 用到的只有 `contains`、`startsWith`、`format`、`fromJSON`、`toJSON`、`success`、`always`、`failure`，沒有 `replace`／`trim`；字串正規化一律在 `run:` 用 `tr -d '[:space:]'`（`build.yml:188`、`legacy-targets.yml:90`、`prepare-release.yml:60`），符合 `AGENTS.md:354-358` 與 `:356` 舉的例子。
8. **對外版本號只有一個來源且與 `mod_version` 一致**：`translator-core/src/main/java/org/universaltranslator/core/UserAgent.java:17` 是 `public static final String VALUE = "MCAutoTranslationTool/1.4";`，`gradle.properties:7` 是 `mod_version=1.4`；掃描 `translator-core/src/main/java` 全部 `.java`，`MCAutoTranslationTool/` 只出現在這一個檔案（1 處），與 `build.yml:94-98` 的「必須剛好 1 個檔案」檢查相符。`AGENTS.md:211-216` 記錄的版本號漂移已修好。
