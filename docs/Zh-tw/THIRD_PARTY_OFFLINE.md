# 離線翻譯第三方元件

[简体中文](../Zh-cn/THIRD_PARTY_OFFLINE.md) · [繁體中文](THIRD_PARTY_OFFLINE.md) · [English](../en/THIRD_PARTY_OFFLINE.md) · [返回繁中 README](README.md)

MC 自動翻譯工具不會將以下大型檔案打包進模組 JAR。當使用者啟用離線元件自動下載時，
模組會透過 HTTPS 下載選定且鎖定版本的檔案，並於執行前驗證檔案大小及 SHA-256。

針對中國大陸使用者，Qwen 模型會先嘗試從 ModelScope 的 Qwen 官方儲存庫下載，失敗後
回退至固定修訂版的 Hugging Face。llama.cpp 壓縮檔會先嘗試 HTTPS 加速端點，失敗後
回退至 GitHub 官方發行版。預期大小及 SHA-256 已內建於模組，因此鏡像無法在未被偵測
的情況下變更可執行檔內容。

- llama.cpp `b9637`，MIT 授權條款：<https://github.com/ggml-org/llama.cpp>
- Qwen2.5 0.5B Instruct GGUF，Apache-2.0 授權條款：
  <https://modelscope.cn/models/qwen/Qwen2.5-0.5B-Instruct-GGUF> 及
  <https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF>
- Qwen2.5 1.5B Instruct GGUF，Apache-2.0 授權條款：
  <https://modelscope.cn/models/qwen/Qwen2.5-1.5B-Instruct-GGUF> 及
  <https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF>

引擎一律使用 `--host 127.0.0.1` 啟動，不會監聽公開位址。
