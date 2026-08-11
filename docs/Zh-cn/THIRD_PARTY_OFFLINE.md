# 离线翻译第三方组件

[简体中文](THIRD_PARTY_OFFLINE.md) · [繁體中文](../Zh-tw/THIRD_PARTY_OFFLINE.md) · [English](../en/THIRD_PARTY_OFFLINE.md) · [返回简中 README](README.md)

MC 自动翻译工具不会把以下大型文件打包进模组 JAR。当用户启用离线组件自动下载时，
模组会通过 HTTPS 下载选定且固定版本的文件，并在执行前校验文件大小和 SHA-256。

对于中国大陆用户，Qwen 模型会先尝试从 ModelScope 的 Qwen 官方仓库下载，失败后
回退到固定修订版的 Hugging Face。llama.cpp 压缩包会先尝试 HTTPS 加速端点，失败后
回退到 GitHub 官方发布。预期大小和 SHA-256 已内置于模组，因此镜像无法在不被发现
的情况下更改可执行文件内容。

- llama.cpp `b9637`，MIT 许可证：<https://github.com/ggml-org/llama.cpp>
- Qwen2.5 0.5B Instruct GGUF，Apache-2.0 许可证：
  <https://modelscope.cn/models/qwen/Qwen2.5-0.5B-Instruct-GGUF> 和
  <https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF>
- Qwen2.5 1.5B Instruct GGUF，Apache-2.0 许可证：
  <https://modelscope.cn/models/qwen/Qwen2.5-1.5B-Instruct-GGUF> 和
  <https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF>

引擎始终使用 `--host 127.0.0.1` 启动，不会监听公开地址。
