# a02-core-net 審計報告

範圍：`translator-core/src/main/java/org/universaltranslator/core/` 的 `net/`、`offline/`、`provider/` 三個子包全部（34 檔）

配額：BUG-C ×2（004–005）、BUG-M ×3（003–005）、BUG-S ×5（046–050，有才寫）

產出：BUG-C 2 / BUG-M 3 / BUG-S 5（實際，未超額）

> **實際行數與任務書不符，行號一律以實際檔案為準**：`OpenAiChatTranslationProvider` 1156 行（非 1096）、`LlamaCppOfflineProvider` 863 行（非 814）、`OnlineProviderConfig` 636 行（非 594）、`OfflineProcessSupport` 535 行（非 507）、`SafeArchiveExtractor` 396 行（非 369）、`JsonStrings` 421 行、`HttpJsonClient` 339 行。
>
> **孫代理分工**：a02-S1-offline（`offline/` 4 檔）、a02-S2-providers（provider 中小型類）、a02-S3-bigproviders（`OpenAiChatTranslationProvider` + `LlamaCppOfflineProvider`）。全部條目由本人重新 `read` 對照行號後才收錄；其中一條孫代理提出的「`String.format("%02x")` 受 locale 影響」經我對 JDK 8/21/25 的 `java.util.Formatter` 原始碼逐行驗證後**判定為誤報，已剔除**（見檔尾第 2 條），另有一條 `LlamaCppOfflineProvider.stopRequested` 疑似未重設亦經查證為非缺陷，未列入。

---

### BUG-C-004 · 批次收集窗被中斷後無限自旋，永久燒掉一顆 CPU 核

- **嚴重度**：嚴重 ｜ **信心**：高
- **位置**：`translator-core/src/main/java/org/universaltranslator/core/provider/OpenAiChatTranslationProvider.java:575-639`（`translateBatched` 的 `while (true)` 在 `:580-597`；`Thread.sleep(batchWindowMillis)` 在 `:609-611`；`catch (InterruptedException)` 在 `:625-627`；`finally` 在 `:630-638`）
- **受影響範圍**：所有走批次協定的線上 provider（`openai-compatible`、`deepseek`、`dashscope`、`zhipu`、`volcengine-ark`、`azure-openai`）；Fabric 1.0–26.x、Forge 1.8.9–26.2、NeoForge 全平台共用此類。離線 loopback 豁免（`:311` 走 `translateOne`）。
- **現象**：切換世界、按 F8 開關翻譯、或儲存設定時，客戶端卡住約 5 秒，之後有一顆 CPU 核心長期 100% 且該執行緒永不結束。`coordinator.close()` 每次都印出 `[MC Auto Translation Tool] translation workers did not stop within 5000ms; closing provider anyway`。重複切換幾次就多幾顆被燒滿的核心，直到遊戲關閉。
- **觸發條件**：批次 leader 正好停在 `:609` 的 `Thread.sleep(batchWindowMillis)`（預設 30 ms，`TranslationTuning.java:31`；`batch-window-ms` 可設到 500 ms）時，`TranslationCoordinator.close()` 執行 `executor.shutdownNow()`（`TranslationCoordinator.java:394`）中斷它。一次批次請求含網路往返約 0.5–3 s，其中 30 ms 落在該 sleep，故每次關閉時「恰好有批次在飛」約 1–6% 命中；`batch-window-ms` 拉到 500 時可達 15–50%。
- **根因**：`serveBatch` 吞下中斷後只把中斷旗標設回去，卻沒有讓自己脫離佇列，於是回到 `translateBatched` 的同一個迴圈立刻重新當選 leader：

  ```java
  // :609-611  收集窗
  if (batchWindowMillis > 0L) {
      Thread.sleep(batchWindowMillis);       // ← 這裡被中斷
  }
  // :625-627
  } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();    // ← 旗標被設回
      failGroup(group, interrupted);         // ← group 此時還是空的（sleep 在收集之前）
  } finally {
      synchronized (batchLock) {
          for (BatchEntry entry : group) { entry.settled = true; }  // ← 空迴圈
          batchServing = false;
          batchLock.notifyAll();
      }
  }
  ```

  `leader` 自己既沒被移出 `pendingBatch`，`settled` 也從未設為 `true`。回到 `:580` 的 `while (true)`：`entry.settled` 仍是 `false`、`pendingBatch.contains(entry)` 仍是 `true` 且 `batchServing` 已被 finally 清成 `false`，於是 `:586-588` 再把它選為 leader → `:610` 的 `Thread.sleep` 因為中斷旗標已設而**立刻**拋 `InterruptedException`（同時清掉旗標）→ `:626` 又把旗標設回去 → 無限緊迴圈，每一圈都不阻塞，只做取得/釋放鎖與丟一個例外。這不是「卡住」，是純 CPU 自旋。
- **影響**：命中一次即產生一個永不結束、100% CPU 的執行緒（`TranslationCoordinator.java:454` 是 daemon，故不阻止 JVM 結束，但會陪著整個遊戲生命週期）；每次 `close()` 固定被 `awaitTermination(5000)` 拖滿 5 秒，而 `close()` 位於儲存設定與世界卸載路徑上，屬玩家可感知的凍結（`FabricTranslationRuntimeBase.java:120-123` 的註解正是在說明這個 5 秒等待）。同一根因的第二個症狀：非 leader 在 `:590` 的 `batchLock.wait(1000L)` 被中斷時例外直接外傳，該 `BatchEntry` 永久留在 `pendingBatch` 且 `settled=false`，之後某個批次會白花一次付費請求去翻譯一條已經沒人等的文字。
- **修法**：中斷時必須讓 leader 離開佇列並把失敗交回呼叫者，`translateBatched` 既有的 `if (entry.settled) break;` 與 `:598-600` 會自然收斂。

  ```java
  } catch (InterruptedException interrupted) {
      synchronized (batchLock) {
          pendingBatch.remove(leader);   // 不會再被選為 candidate
          leader.settled = true;         // 讓 while (true) 於 :583 立刻 break
      }
      leader.failure = interrupted;      // :598-600 會把它拋給 coordinator
      Thread.currentThread().interrupt();
      failGroup(group, interrupted);
  }
  ```

  外層再補一層，修掉非 leader 的殘留：

  ```java
  try {
      while (true) { /* 原迴圈 */ }
  } catch (InterruptedException interrupted) {
      synchronized (batchLock) {
          pendingBatch.remove(entry);
          entry.settled = true;
      }
      Thread.currentThread().interrupt();
      throw interrupted;
  }
  ```

  回歸測試：`setBatchWindowMillis(500)` 後中斷執行緒再呼叫 `translate()`，斷言 1 秒內拋 `InterruptedException` 且 `pendingBatch` 清空。

---

### BUG-C-005 · 沒有任何完成度判定，被截斷的譯文被永久寫入持久快取

- **嚴重度**：嚴重 ｜ **信心**：高
- **位置**（四個獨立入口，同一後果）：
  - `translator-core/src/main/java/org/universaltranslator/core/provider/OpenAiChatTranslationProvider.java:461-463`（串流接受條件）、`:473-507`（`[DONE]` 在 `:480-483`、relay 錯誤事件在 `:499-505`）、`:410-415`（註解與實際行為不符）、`:538-547`（離線預算塌陷）
  - `translator-core/src/main/java/org/universaltranslator/core/provider/GeminiTranslationProvider.java:96-112`（不讀 `finishReason`）、`:209-212`（預算上限 2048）
  - `translator-core/src/main/java/org/universaltranslator/core/provider/ClaudeTranslationProvider.java:87-104`（不讀 `stop_reason`）、`:200-203`
  - 共同缺口：`translator-core/src/main/java/org/universaltranslator/core/TranslationOutputValidator.java:52-64`（只有上限、沒有下限）
- **受影響範圍**：所有 provider（OpenAI 相容的串流與非串流、Gemini、Claude、離線 llama.cpp）與所有平台。寫入端為 `TranslationCoordinator.java:368` 的 `cache.put`，落地端為 `PersistentTranslationCache.java:236-280`（無 TTL、無到期、載入時不複驗）。
- **現象**：譯文在句子中間被截斷，仍被當成「翻譯成功」顯示、計入統計、並寫進磁碟快取。之後同一句話永遠回傳這半截結果，重開遊戲也一樣，沒有任何錯誤提示或日誌，使用者只能手動刪快取檔。
- **觸發條件**（任一即可）：
  1. **串流提前結束**：端點或中間層（relay、Cloudflare、行動網路、公司代理）送出數個 `data:` chunk 後直接關閉連線而**沒有送 `data: [DONE]`**。`ServerSentEvents.read` 走完 `:57` 的迴圈自然回傳，`attempt.content` 就被設為累積內容。
  2. **串流 keep-alive 被誤判為錯誤**：端點在串流中插入帶 `message` 欄位的 keep-alive（例如 `{"type":"ping","message":"keep-alive"}`），`:499-505` 判定為 relay 錯誤事件並 `return false` 提前停止讀取。
  3. **預算用盡但未讀停止原因**：Gemini 回 `finishReason: "MAX_TOKENS"`、Claude 回 `stop_reason: "max_tokens"`、OpenAI 回 `finish_reason: "length"`。三個 provider 全都不讀這個欄位（已 grep 確認全 repo 無 `finish_reason` / `finishReason` / `stop_reason` 的消費端）。
  4. **離線預算塌成 1 token**：輸入估算落在 832–895 token 時，`remaining = 1024 - 128 - estimatePromptTokens(text)` 只剩 1–64，`:546` 的 `Math.max(1, Math.min(Math.max(64, requestedTokens), remaining))` 回 1–64；模型吐 1 個字（非空）→ 通過驗證 → 入快取。
- **根因**：`StreamAttempt` 沒有「串流是否完整結束」這個狀態，`:461-463` 只區分「有沒有內容」：

  ```java
  // :461-463
  if (accumulated.length() > 0) {
      attempt.content = accumulated.toString();   // 只要有字就當成最終答案
  }
  ```

  `:480-483` 收到 `[DONE]` 時 `return false`，但那個回傳值只被用來停止讀取，沒有回寫到 `attempt`。因此 `:410-415` 註解所稱「A partial text is never shown as the answer」只對「空內容／relay 錯誤物件」成立，對「提前 EOF」不成立。

  四個 provider 的驗證端點都是同一句 `TranslationOutputValidator.requireValid(text, translated)`，而它只檢查長度**上限**、行數、指令殘留與佔位符序列（`:52-64`），**沒有下限**——所以被截斷的結果必然通過。`:535` 的既有註解已承認同一機制會讓截斷結果「entered the persistent cache」（那段註解是為了修 token 預算而寫的，但只修了預算、沒修驗證）。

- **影響**：依 §2 的定義屬「快取永久污染」——一次截斷 = 一條**永久**錯誤譯文，跨重開、跨 session 都存在。對含保護佔位符的行，`:62-64` 的 token 序列比對有機會擋下；純文字行則直接寫入。量級：單行、永久、完全靜默，且隨時間累積；第 3 類觸發（長輸入）在任務書／模組 tooltip 這種長文字上是穩定可重現的。
- **修法**：三個 provider 都要有「完整結束」判定，否則交給會產生可讀錯誤的路徑。

  ```java
  // (a) OpenAiChatTranslationProvider：StreamAttempt 增加 complete 旗標
  // :480-483
  if ("[DONE]".equals(payload.trim())) {
      attempt.complete = true;          // ← 唯一授權接受內容的出口
      DebugLog.global().logStreamEvent(provider, "done", 0);
      return false;
  }
  // :461-463
  if (attempt.complete && accumulated.length() > 0) {
      attempt.content = accumulated.toString();
  }

  // (b) Gemini / Claude：驗證前檢查停止原因
  if (response.contains("\"finishReason\":\"MAX_TOKENS\"")) {
      throw new IllegalStateException(
              "Gemini stopped at maxOutputTokens; raise the budget or shorten the text");
  }
  // Claude 同理："stop_reason":"max_tokens"（串流則檢查 message_delta 的 stop_reason）

  // (c) 離線預算不足時不要硬送
  if (remaining < 64) {
      throw new IllegalArgumentException(
              "text does not fit the offline context window (" + remaining + " tokens left)");
  }
  ```

  另外建議在 `TranslationOutputValidator` 補一個與來源長度相關的下限（例如非空白譯文至少要有 `min(8, source.length()/4)` 個字元），把這一類缺口一次擋住。回歸測試：以一個只送兩個 chunk 就斷線的假 SSE 伺服器，斷言結果不是那兩個 chunk 的串接。

---

### BUG-M-003 · 讀取逾時被誤判為 session 取消，`api-fallback` 完全失效

- **嚴重度**：中 ｜ **信心**：高
- **位置**：`translator-core/src/main/java/org/universaltranslator/core/provider/FallbackTranslationProvider.java:47-74`（`isInterruption` 在 `:67-74`，判定使用點在 `:48-54`）
- **受影響範圍**：設定為「離線引擎 + `api-fallback=true`」的所有使用者（`FabricConfig.java:398-407` 的 `new FallbackTranslationProvider(local, createApiProvider(apiFallbackProvider))`）；Forge／NeoForge 同構。全平台。
- **現象**：本地模型一慢或迴圈埠沒起來，玩家看到的是 `Read timed out` 之類的失敗，而不是設計上的「自動改用線上 API」；HUD 也不會出現「主翻译服务失败，已使用 API 回退」。同一個翻譯請求同時把中斷旗標設在一個池化 worker 上。
- **觸發條件**：
  1. **讀取逾時**：`LlamaCppOfflineProvider.java:363-367` 給 loopback client 的設定是 `new HttpJsonClient(1_000, 15_000)`，read timeout 只有 15 秒，而離線路徑送的是 `"stream":false`（模型生成完才回應）；`--threads 2`（`:324`）的小模型約 15–25 tok/s，譯文超過約 200–300 token 就會逾時。
  2. **連線逾時**：伺服器卡住時 `setConnectTimeout` 逾時同樣拋 `SocketTimeoutException`（`HttpJsonClient` 只把 `ConnectException` 轉成 `TranslationEndpointUnavailableException`，`SocketTimeoutException` 不在其中）。
- **根因**：`isInterruption` 用 `InterruptedIOException` 當「已被取消」的判準：

  ```java
  // :67-74
  private static boolean isInterruption(Throwable failure) {
      for (Throwable current = failure; current != null; current = current.getCause()) {
          if (current instanceof InterruptedException || current instanceof InterruptedIOException) {
              return true;
          }
      }
      return Thread.currentThread().isInterrupted();
  }
  ```

  但 `java.net.SocketTimeoutException extends java.io.InterruptedIOException`（JDK 8 `java/net/SocketTimeoutException.java` 原始碼確認），而 `HttpJsonClient.java:74` 的 `setReadTimeout` 與 `:73` 的 `setConnectTimeout` 逾時拋出的正是 `SocketTimeoutException`。`LlamaCppOfflineProvider.translate`（`:181-194`）只改 status 後原樣 rethrow，型別一路傳到 `:48`，於是被當成「session 正在取消」→ `Thread.currentThread().interrupt(); throw primaryFailure;`，`fallback.translate(...)`（`:56`）永遠不會執行。註解（`:49-51`）寫的意圖是正確的，錯的是判斷條件太寬。
- **影響**：`api-fallback` 這個功能在最需要它的情境（本地模型太慢、埠沒起來）**100% 失效**——而這正是它存在的唯一理由。附帶：對固定池的 worker（`TranslationCoordinator.java` 的 executor）設定中斷旗標，同一任務內後續任何 `sleep`/`wait` 會立刻拋 `InterruptedException`（`ResilientTranslationProvider.java:57`、`:89` 的兩處 `Thread.sleep(delay)` 就在其後）。
- **修法**：把逾時從「取消」中排除，先判 `SocketTimeoutException` 再判 `InterruptedIOException`：

  ```java
  private static boolean isInterruption(Throwable failure) {
      for (Throwable current = failure; current != null; current = current.getCause()) {
          if (current instanceof InterruptedException) {
              return true;
          }
          if (current instanceof java.net.SocketTimeoutException) {
              return false;       // 讀取／連線逾時是網路失敗，不是取消
          }
          if (current instanceof InterruptedIOException) {
              return true;
          }
      }
      return Thread.currentThread().isInterrupted();
  }
  ```

  回歸測試：讓 primary 拋 `new SocketTimeoutException("Read timed out")`，斷言 fallback 被呼叫且執行緒中斷旗標未被設定。

---

### BUG-M-004 · 模型名被拼進 URL 兩次，用內建選單選到的 Gemini 模型必定 404

- **嚴重度**：中 ｜ **信心**：高
- **位置**：`translator-core/src/main/java/org/universaltranslator/core/provider/GeminiTranslationProvider.java:221-223`（`generateUrl`）、`:292-297`（`requireModel`）、`:276-290`（`stripMethod`）、`:35-36`（`DEFAULT_ENDPOINT` 以 `/models` 結尾）；值來源 `OpenAiModelCatalog.java:267-283`（`addModel` 的鍵序在 `:276`）與 `platforms/fabric/shared/screens-shared/src/client/java/org/universaltranslator/fabric/UniversalTranslatorSettingsScreen.java:1288`
- **受影響範圍**：Gemini provider 與其連線測試（`ProviderProbe.java:45-47`）；全平台（6 個設定畫面同構）。
- **現象**：在設定畫面按「取得模型清單」、從清單點一個模型之後，測試連線與所有翻譯都失敗（`HTTP 404 NOT_FOUND`），網址長成 `.../v1beta/models/models/gemini-2.5-flash:generateContent`。把 model 欄手動改成不含 `models/` 前綴的名字才恢復。
- **觸發條件**（兩條獨立路徑，同一根因）：
  1. **用內建模型選單**：Gemini 的 `ListModels` 每個模型物件把資源名放在 `name: "models/gemini-…"`（裸 id 在 `baseModelId`），**沒有 `id`**。`OpenAiModelCatalog.addModel` 的取值順序是 `{"id", "name", "model"}`（`:276`），於是 `name` 被選走，清單字串就是 `models/gemini-2.5-flash`；畫面在 `UniversalTranslatorSettingsScreen.java:1288` 直接 `llmModel = fetchedModels.get(index);` 寫回設定，`GeminiTranslationProvider` 的 `requireModel`（`:292-297`）只 `trim()`，不剝前綴。
  2. **貼上完整方法 URL**：`stripMethod`（`:276-290`）只砍 `:generateContent` / `:streamGenerateContent` 與 query，**不砍 `/models/<model>`**，於是 `base` 變成 `.../v1beta/models/gemini-2.0-flash`，再拼上 model 就成為 `.../models/gemini-2.0-flash/gemini-2.0-flash:generateContent`。
- **根因**：`generateUrl()` 無條件假設 `model` 是裸 id：

  ```java
  // :221-223
  private String generateUrl() {
      return base + "/" + model + GENERATE;     // base 已含 /models，model 又自帶 models/
  }
  ```

  反證在同一個類別裡：`modelsUrl`（`:195-203`）**明確**用 `lastIndexOf("/models/")` 把 model 段截回集合基底，javadoc（`:189-193`）還寫著「truncated back to the collection first」。作者知道貼完整 URL 這條輸入存在，只是生成路徑沒套同一段截斷邏輯；而 `OpenAiModelCatalog` 也沒有把 `models/` 前綴正規化。兩邊各漏一半。

- **影響**：只要走最自然的路徑（用內建選單挑模型），Gemini provider 與其「測試連線」100% 失敗，使用者只會看到 404；且 `id()`（`:92`）會把壞掉的 model 寫進快取鍵，換回正確名稱後不會被舊資料污染（這一點沒有二次傷害）。屬「功能明顯不正確但可繞過」→ 中。
- **修法**：provider 端吸收兩種輸入，catalog 端優先取裸 id。

  ```java
  // :292-297
  private static String requireModel(String model) {
      if (model == null || model.trim().isEmpty()) {
          throw new IllegalArgumentException("Gemini model is required");
      }
      String value = model.trim();
      return value.startsWith("models/") ? value.substring("models/".length()) : value;
  }

  // :73-77  建構子：與 modelsUrl 共用同一段截斷
  String base = stripMethod(endpoint == null || endpoint.trim().isEmpty()
          ? DEFAULT_ENDPOINT : endpoint.trim());
  int marker = base.lastIndexOf("/models/");
  if (marker >= 0) {
      base = base.substring(0, marker + "/models".length());
  }
  this.base = base;

  // OpenAiModelCatalog.java:276   Gemini 的裸 id 在 baseModelId
  String[] keys = {"id", "baseModelId", "name", "model"};
  ```

---

### BUG-M-005 · 4xx 錯誤只讀扁平 error 欄位，真正原因全丟失並讓預算學習永久失效

- **嚴重度**：中 ｜ **信心**：高
- **位置**：`translator-core/src/main/java/org/universaltranslator/core/net/HttpJsonClient.java:107-114`（非串流）與 `:224-233`（串流）；下游受害邏輯在 `translator-core/src/main/java/org/universaltranslator/core/provider/OpenAiChatTranslationProvider.java:1057-1066`（`rejectsCompletionBudget`）與 `:1036-1050`
- **受影響範圍**：所有把錯誤放在**巢狀物件**裡的 provider —— OpenAI 及其全部相容 relay（`{"error":{"message":…,"type":…}}`）、Anthropic（`{"type":"error","error":{…}}`）、Gemini（`{"error":{"code":400,"message":…}}`）。全平台。
- **現象**：金鑰打錯、額度用完、模型不存在、請求太大，使用者看到的都是同一句「Translation service returned HTTP 401 / 429 / 400」，沒有任何原因；設定畫面的連線測試也只回報狀態碼。
- **觸發條件**：任何非 2xx 回應，只要該 provider 的錯誤主體是 `{"error":{…}}` 這種物件形式（三大家的預設格式）就一定踩到。
- **根因**：`HttpStatusException` 的訊息只由兩個扁平查詢組出：

  ```java
  // HttpJsonClient.java:108-114
  String providerError = JsonStrings.readStringField(response, "error");
  if (providerError == null) {
      providerError = JsonStrings.readStringField(response, "Message");
  }
  throw new HttpStatusException(status, "Translation service returned HTTP " + status
          + (providerError == null ? "" : ": " + providerError), …);
  ```

  `JsonStrings.readStringField`（`JsonStrings.java:138-167`）在 `:158-160` 要求冒號後第一個非空白字元必須是 `"`，否則 `continue`。OpenAI 的 `error` 值是物件 `{`，因此**永不匹配**，`Message` 也沒有，訊息退化成純狀態碼。這個缺陷是已知的——`ProviderJson.nestedMessage`（`ProviderJson.java:64-93`）就是專門為 `error.message` 寫的讀取器——但 `HttpJsonClient` 這條路徑沒有用它。

  下游後果可量化：`rejectsCompletionBudget` 只檢查 `failure.getMessage()` 裡有沒有 `max_tokens` / `max_completion_tokens` / `too large` / `exceed`（`:1063-1065`），而該訊息現在只有「Translation service returned HTTP 400」，所以這個判斷**永遠為 false**，`acceptedReasoningTokens = MAXIMUM_TOKENS_LIMIT`（`:1047`）永遠不會被設定。

- **影響**：(1) 使用者無法自行判斷要修哪個設定，只能翻日誌猜；(2) 對一個拒絕較大 `max_tokens` 的端點，每一行譯文都會先送一次必被拒絕的請求再送第二次，即**每一行多付一次計費呼叫**——正是 `:1032-1034` 註解聲稱「without this every later line would ask for the same rejected value and be charged for the rejection again」要避免的成本，而這個保護因訊息被截斷而完全失效。
- **修法**：`HttpJsonClient` 改用能讀巢狀物件的方式取錯誤訊息（兩條路徑都要改）。

  ```java
  private static String providerErrorText(String response) {
      String flat = JsonStrings.readStringField(response, "error");
      if (flat != null) {
          return flat;
      }
      try {
          Object root = JsonStrings.parse(response);
          if (root instanceof Map) {
              Object error = ((Map<?, ?>) root).get("error");
              if (error instanceof Map) {
                  Object message = ((Map<?, ?>) error).get("message");
                  if (message instanceof String) {
                      return (String) message;
                  }
              }
          }
      } catch (RuntimeException ignored) {
          // 非 JSON 主體（例如 HTML 錯誤頁）走原本的 Message 與狀態碼。
      }
      return JsonStrings.readStringField(response, "Message");
  }
  ```

  並在 `ProviderSelfTest` 補一條斷言：巢狀 `error.message` 必須出現在 `HttpStatusException.getMessage()` 裡。

---

### BUG-S-046 · 逾時與重試次數設定越界即拋例外，整個模組被靜默停用

- **嚴重度**：小（影響面大但可繞過，且僅手改設定檔可達） ｜ **信心**：高
- **位置**：`translator-core/src/main/java/org/universaltranslator/core/provider/OnlineProviderConfig.java:583-594`（`integer`），呼叫點 `:474-478` 與 `:536-538`
- **受影響範圍**：所有平台、所有 provider；只有手動編輯 `config/universal-translator.properties` 才會踩到（這四個鍵沒有任何遊戲內 UI）。
- **現象**：翻譯功能整個消失。啟動日誌為 `MC Auto Translation Tool configuration failed; translation remains disabled`；按 F8 則顯示 `toggle_failed`。
- **觸發條件**：把下列任一鍵改成範圍外的值並重啟或按 F8——`api-connect-timeout-ms`（5000，250–60000）、`api-read-timeout-ms`（120000，1000–300000）、`api-max-attempts`（3，1–5）、`api-min-request-interval-ms`（60，0–60000）。常見踩法：想「不要逾時」把 read timeout 設 0；想多試幾次把 `api-max-attempts` 設 10。
- **根因**：`integer` 對越界值是**拋例外**而不是夾取：

  ```java
  // :590-593
  if (parsed < minimum || parsed > maximum) {
      throw new IllegalArgumentException(key + " must be between " + minimum + " and " + maximum);
  }
  ```

  而 `create()` 是唯一入口且沒有 try/catch：`create()`（`:474-539`）→ `FabricConfig.createApiProvider`（`platforms/fabric/shared/settings/modern/.../FabricConfig.java:409-411`）→ `createProvider()`（`:398-407`）→ `validateProviderConfiguration()`（`:370-375`，直接往外拋）與 `FabricClientBootstrapBase.java:176`。同一個檔的其他數值設定走完全相反的政策：`translationWorkers()`／`batchSize()`／`batchWindowMillis()`（`:249-273`）都經 `TranslationTuning.clamp` 採「夾取而非拒絕」，`TranslationTuning.java:13-17` 的類別註解甚至明文寫著「a typo there must not stop translation from working」。同一設定檔、同一 `create()` 路徑上存在兩套矛盾政策，而越界的那一套讓整個功能停用。
- **影響**：手改一個數字 = 翻譯 100% 不可用，直到使用者自己找到並改回。`FabricClientBootstrapBase.java:116-119` 會捕捉並記錄，所以不崩潰，但**不會自動修復**，且錯誤訊息只在日誌出現一次。
- **修法**：與同檔其他設定一致地夾取。

  ```java
  private int integer(String key, int fallback, int minimum, int maximum) {
      int parsed;
      try {
          parsed = Integer.parseInt(value(key));
      } catch (NumberFormatException notANumber) {
          return fallback;                                        // 非數字 → 預設值
      }
      return TranslationTuning.clamp(parsed, minimum, maximum);    // 越界 → 夾取，不再拋
  }
  ```

---

### BUG-S-047 · 自訂 provider 的 id 指紋漏算 port／query／header，換設定仍命中舊快取

- **嚴重度**：小 ｜ **信心**：高
- **位置**：`translator-core/src/main/java/org/universaltranslator/core/provider/CustomHttpJsonTranslationProvider.java:48-56`
- **受影響範圍**：`custom-http-json` provider；全平台。
- **現象**：只改 header 值（最常見的「換模型」手段）、只改 content-type，或把端點從 `http://127.0.0.1:8080/translate` 換到 `:9090`（同 host 同 path），翻譯結果完全不變——舊譯文從磁碟快取原樣回來。使用者會判定「改了沒用」。
- **觸發條件**：上述任一設定變更後，對同一段文字發出請求（快取跨 session 持久化）。
- **根因**：指紋不含 port、query、content-type 與 header 樣板：

  ```java
  // :48-56
  this.id = "custom-http-json:" + host + ":"
          + sha256Hex(endpoint.getRawPath() + method + responsePath + requestTemplate);
  ```

  `getHost()` 不含 port，也沒有 `getRawQuery()`、`contentType`、`headerTemplates`——而這些都會改變請求語意。`id` 由 `TranslationCoordinator` 直接組成快取鍵的一部分，漏算即等於快取鍵碰撞。同 package 的 `LibreTranslateProvider.id()`（`:30`）用的是含 port 的 `scheme://authority+path`，兩個自訂類的嚴格度不一致。
- **影響**：換設定後得到舊結果，且錯譯文會被當成新設定的結果繼續命中。不會壞資料，但會誤導排查。
- **修法**：指紋納入 port/query/content-type/headers（仍**不可**含 apiKey 值，維持原設計）。

  ```java
  String fingerprint = endpoint.getScheme() + "://" + endpoint.getAuthority()
          + endpoint.getRawPath() + "\n" + endpoint.getRawQuery()
          + "\n" + method + "\n" + contentType
          + "\n" + responsePath + "\n" + requestTemplate
          + "\n" + headerTemplates;      // LinkedHashMap，插入序穩定
  ```

---

### BUG-S-048 · Tencent TMT 以 UTF-8 位元組判定 2000 上限，與同 package 的字元計法不一致

- **嚴重度**：小 ｜ **信心**：中
- **位置**：`translator-core/src/main/java/org/universaltranslator/core/provider/TencentTmtProvider.java:50`
- **受影響範圍**：`tencent-tmt` provider；全平台。
- **現象**：中文→外語（本模組最常用方向）時，來源只要超過約 666 個中文字就被本地 `IllegalArgumentException` 擋掉（`Tencent TMT source text exceeds the provider's 2000 byte limit`），請求根本沒送出。
- **觸發條件**：`request.getText()` 的 UTF-8 長度 > 2000（CJK 每字 3 bytes）。
- **根因**：`ProviderSupport.requireUtf8Bytes(..., 2000)` 是位元組檢查，但同一 package 的其他長度檢查全部用字元：`AliyunMachineTranslationProvider.java:50`（`length() > 5000`）、`HuaweiCloudTranslationProvider.java:45`（2000 字）、`YoudaoTranslationProvider.java:46`（5000 字）。用 bytes 檢查等於對 CJK 收緊到 1/3。`BaiduTranslationProvider` 用 bytes 是正確的（文件就是 6000 bytes），所以問題只在 TMT 這一行。
- **影響**：合法請求被本地拒絕，使用者看到「超過上限」而實際沒超過。只影響長輸入，故列小。**信心標中**——程式碼內部不一致是可確認的，但 Tencent 官方文件的上限單位我無法離線複核（若官方確實是 bytes，則此條僅為文案問題）。
- **修法**：改成字元數，或明確標示單位。

  ```java
  // :50
  if (request.getText().length() > 2000) {
      throw new IllegalArgumentException("Tencent TMT source text exceeds 2000 characters");
  }
  ```

---

### BUG-S-049 · 記憶體不足退出碼的提示分支被通用日誌分支擋死

- **嚴重度**：小 ｜ **信心**：高
- **位置**：`translator-core/src/main/java/org/universaltranslator/core/offline/OfflineProcessSupport.java:498-503`
- **受影響範圍**：離線 llama.cpp 引擎啟動失敗的診斷；全平台。
- **現象**：引擎被系統以記憶體不足終止（exit 9 / 137）時，使用者看到的是通用的「离线引擎启动失败（退出码 0x…）：<日誌片段>」，而不是「请检查可用内存」這句唯一能自己動手修的建議。
- **觸發條件**：`exitCode == 9 || 137` 且 `logDetail` 非空。llama.cpp 在載入模型期間一定會輸出（`LlamaCppOfflineProvider.java:340-341` 的 `redirectErrorStream(true)` + `appendTo(log)`），而 `summarizeLog` 只要取到任一行就回非空（`:399-441`），所以幾乎總是先命中 `:498`。
- **根因**：判斷順序錯誤，退出碼分支寫在通用分支之後，實質不可達：

  ```java
  if (!detail.isEmpty()) {                                     // :498 先攔截
      return "离线引擎启动失败（退出码 " + code + "）：" + detail;
  }
  if (exitCode == 9 || exitCode == 137) {                       // :501 到不了
      return "离线引擎被系统终止（退出码 " + code + "），请检查可用内存";
  }
  ```

- **影響**：純診斷品質，不影響功能；但這是使用者唯一能自行處置的失敗種類（降模型等級／關掉其他程式），被吞掉後只能翻 `llama-server.log`。
- **修法**：

  ```java
  if (exitCode == 9 || exitCode == 137) {
      String hint = "离线引擎被系统终止（退出码 " + code + "），请检查可用内存";
      return detail.isEmpty() ? hint : hint + "：" + detail;
  }
  if (!detail.isEmpty()) {
      return "离线引擎启动失败（退出码 " + code + "）：" + detail;
  }
  ```

---

### BUG-S-050 · 下載回應驗證失敗時不中斷連線，socket 洩漏

- **嚴重度**：小 ｜ **信心**：高
- **位置**：`translator-core/src/main/java/org/universaltranslator/core/offline/VerifiedDownloader.java:120-131`（`validateResponse` 在 `:130` 拋出後直接離開方法），另 `:244-257` 的 `open` 在 `getResponseCode()` 拋出時同樣未 `disconnect()`
- **受影響範圍**：離線引擎與模型下載（`LlamaCppOfflineProvider.java:415`、`:467`）；全平台。
- **現象**：離線功能反覆啟動失敗的環境裡，程序內累積未關閉的 `HttpURLConnection` 與底層 socket，直到 GC 才清理。
- **觸發條件**：`validateResponse` 任一條失敗，且**同一次 `download()` 內每個鏡像各一次**（`downloadSources()` 有 2 個來源）：`:187-190` 回應 `Content-Type` 含 `text/html`（鏡像回錯誤頁／被劫持／proxy 攔截）；`:191-198` 續傳 `Content-Range` 不符；`:200-203` HTTP 200 但 `Content-Length != expectedSize`（上游換版）。搭配 5 分鐘退避（`delayStartupRetries`）約每 5 分鐘 2 條。
- **根因**：連線只在兩個地方被釋放——狀態碼分支 `:123-126` 與讀取主體的 `finally`（`:152-154`）。`validateResponse(...)` 在 `:130` 位於兩者**之間**，它拋出時 `finally` 尚未進入，`connection` 沒有任何持有者去 `disconnect()`；`open()` 在 `:257` 的 `getResponseCode()` 拋 `IOException` 時也一樣（connect timeout 與 TLS 失敗最常見）。連線主體沒被讀完，JDK 也不會把它放回 keep-alive 池。
- **影響**：socket／檔案句柄洩漏，完全靜默。量級受限於「2 鏡像 × 5 分鐘退避」，同 session 數十條，不會無界增長，故列小而非中。
- **修法**：把 `getResponseCode()` 之後的所有檢查納入會 `disconnect()` 的保護範圍。

  ```java
  HttpURLConnection connection = open(source, offset, 0);
  int status;
  boolean append;
  try {
      status = connection.getResponseCode();
      if (status < 200 || status >= 300) {
          throw new IOException("Download returned HTTP " + status + " for " + source.getHost());
      }
      append = offset > 0L && status == HttpURLConnection.HTTP_PARTIAL;
      if (!append) {
          offset = 0L;
      }
      validateResponse(connection, status, offset, expectedSize, append);
  } catch (IOException failure) {
      connection.disconnect();      // 唯一釋放點，涵蓋所有失敗分支
      throw failure;
  }
  ```

  `open()` 內則包住 `getResponseCode()`，失敗時先 `disconnect()` 再重新拋出。

---

## 附：未佔號段的觀察（信心中或量級更小，供 Lead 取捨合併）

以下是確實存在但未達本號段收錄標準的缺陷，若其他子代理有同類條目可併入：

| 觀察 | 位置 | 為什麼不佔號段 |
| --- | --- | --- |
| SSE 讀取器尾端殘留事件跳過累計上限，且 `handler.onData` 的回傳值被丟棄 | `net/ServerSentEvents.java:87-94` | 只在不補行結束符就斷線時觸發，後果是上限約束失效，無錯誤結果 |
| `rfc3986Encode` 是死碼，且若啟用會漏編 `!` `'` `(` `)` 四個字元 | `net/CryptoSupport.java:80-82` | 全 repo 零呼叫點（已 grep），純維護風險 |
| 串流路徑的錯誤主體不經 `decoded()`，與非串流路徑不對稱 | `net/HttpJsonClient.java:226` | 需「串流失敗」∩「錯誤主體被 gzip」才觸發，無法證明真實 CDN 會這樣做 |
| 一次非協議類 4xx（401/403/404）就把 `streamingUnsupported` 永久鎖上 | `provider/OpenAiChatTranslationProvider.java:452-460`、`ClaudeTranslationProvider.java:144-150` | 只失去串流預覽，譯文由 plain path 正常產生；影響長度取決於實例存活 |
| `OfflineEngineAsset` 以 `java.home`/`user.home`/`tmpdir` 的子字串判 Android，桌面端路徑含 `\data\data\` 會誤判 | `offline/OfflineEngineAsset.java:107-110` | 是刻意啟發式且被 `CoreSelfTest.java:816-817` 釘住，屬取捨 |

---

## 已排查但判定無問題

1. **Zip Slip／symlink 逃逸／解壓炸彈（`SafeArchiveExtractor.java:226-242`、`:207-224`、`:131-157`、`:374-395`）** — 不是字串前綴比較：`safePath` 用 `normalizedRoot.resolve(name).normalize()` 之後再對**絕對路徑**做 `startsWith`，`../`、`/etc/x`、`C:/x`、`\\host\share`、反斜線、`..` 全被 normalize 收起後攔下，`containsWindowsIllegalCharacters`（`:248-257`）再擋掉 `:` 等 Windows 無法表示的字元。symlink 延後到所有普通 entry 寫完才建立（`:103-105`），目標在宣告（`:207-224`）、建立（`:136-138`）與鏈上每一跳（`:174-203`，含 `MAX_LINK_HOPS=16` 與 `visited` 環路偵測）都重做 root 前綴檢查；hardlink（tar type 1）落到 `:96-98` 被丟棄。炸彈防護用減法比較 `bytes > MAX_EXPANDED_BYTES - expanded`（`:386`）不會溢位，entry 上限 10 000 逐筆累計。

2. **`String.format("%02x")` 是否受 locale 影響（原候選，經核對 JDK 原始碼後判定為誤報）** — 我對 JDK 8／21／25 三份 `java.util.Formatter` 原始碼逐行核對：只有 `Conversion.DECIMAL_INTEGER`（`%d`）會走 `localizedMagnitude`（JDK 8 `Formatter.java:3090-3102`；JDK 21 同構），`%x`／`%X`／`%o` 一律用 `Long.toHexString`／`Long.toOctalString` 產生 ASCII 字元（JDK 8 `:3109`、`:3123`；JDK 21 `:3397`），寬度補零也是字面 `'0'`。因此 `VerifiedDownloader.java:238-240`、`OfflineProcessSupport.java:473`、`JsonStrings.java:27` 的十六進位輸出在任何 locale 下都是 ASCII，**不是 bug**。此條已從正式清單剔除，避免虛報。

3. **SSE 半包、UTF-8 跨 chunk、`\r\n`、多事件黏包（`ServerSentEvents.java:52-96`）** — 逐位元組組行，`line` 跨 `read()` 保留；`\r` 與 `\n` 都當終止符，`\r\n` 的第二半被 `line.size() == 0`（`:68`）吃掉；多位元組 UTF-8 只在收到終止符後才 `new String(…, UTF_8)`（`:104`）解碼，半個字元不可能被解碼；`data:` 前綴與單一可選空格在 `:113-118` 處理。上限有兩層（單行 256 KiB、累計 1 MiB）。唯一缺口是尾端路徑，已列於附表。

4. **串流是否洩漏（含例外路徑）** — `postStreamingAttempt` 用 try-with-resources 持有 `StreamedResponse`（`OpenAiChatTranslationProvider.java:441-442`），其 `close()`（`HttpJsonClient.java:284-298`）先關 body 再 `disconnect()`，並有 `closed` 旗標做幂等；`postStreaming` 在未交出物件時於 `finally` 斷線（`:243-248`）；`readBounded` 用 try-with-resources 關閉 `decoded()` 回傳的包裝流（`:325`）。非 2xx、`[DONE]` 提前停止、`IOException` 三條路徑都不漏關。

5. **重試上限／429 打死配額／`Retry-After`（`ResilientTranslationProvider.java:34-35`、`:46-60`、`:147-161`、`:163-171`；`HttpStatusException.java:43-45`）** — 上限硬夾 1–5 次，退避 `min(2000, 200 << (attempt-1))`，`Retry-After` 取 `min(max(backoff, retryAfter), 30000ms)` 且秒轉毫秒前先夾取防溢位；`isRetryable` 對 400／401／403／404 一律不可重試，`TranslationEndpointUnavailableException` 直接視為不可重試。`postBatch` 的「拿掉 `response_format` 再試一次」有 `jsonOutputRejected` latch，一生只付一次（`OpenAiChatTranslationProvider.java:951-964`）。

6. **子行程 stderr 未消費造成死鎖（`LlamaCppOfflineProvider.java:337-341`）** — `redirectErrorStream(true)` 把 stderr 併進 stdout，再用 `redirectOutput(Redirect.appendTo(log))` 直接寫檔，全程沒有 pipe，不存在讀取不及導致子行程阻塞。終止路徑 `:807-854` 是 `destroy()` → `waitFor(2s)` → `destroyForcibly()` → `waitFor(2s)` 的二次確認，另有 `Runtime.addShutdownHook` 保底。順帶確認：`stopRequested` 全檔只有 `set(true)` 沒有 `set(false)`，但所有呼叫端都是「用完即丟」的實例，不存在關閉後重用的路徑，故非缺陷。

7. **API Key 是否進日誌或 URL** — provider package 全域 grep 只有 `IflytekNiuTransProvider.java:68` 把 key 放進 **header 值**（`api_key="…"`），沒有 `?key=`／`?token=` 形式的 URL 參數；`DebugLog.logRequest` 走 `hostOnly(endpoint)`（`DebugLog.java:146`、`:230-251`）只記 host，並經 `DiagnosticsLogExporter.sanitize` 二次遮蔽（`:214`），`ProviderSelfTest.java:1003`、`:1017` 有 `?token=secret` 的回歸測試釘住。`HttpJsonClient` 的錯誤訊息只帶狀態碼與 provider 自報文字。

8. **HTTP 主體大小上限（`HttpJsonClient.java:21`、`:324-338`；`ServerSentEvents.java:28-30`）** — 非串流路徑上限 1 MiB，計數在**解壓後**進行（`readBounded(decoded(stream))`），所以 gzip 炸彈無效；超過即拋 `IOException` 且流已被 try-with-resources 關閉。`JsonStrings.Parser` 另有 `MAX_DEPTH = 64` 的巢狀上限（`:251`、`:268-271`）擋深層巢狀，平坦的超大陣列由外層 1 MiB 主體上限兜住。四個簽名器／雜湊工具全部經 `CryptoSupport.utf8()`（`:90-92`），沒有未指定 charset 的 `getBytes()`／`new String(byte[])`。
