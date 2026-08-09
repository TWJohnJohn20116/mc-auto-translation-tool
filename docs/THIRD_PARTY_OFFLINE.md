# Offline translation third-party components

MC Auto Translation Tool does not bundle the following large files in its mod JAR. When the user
enables automatic offline downloads, the mod downloads the selected, pinned files over HTTPS and
verifies their size and SHA-256 before execution.

For users in mainland China, Qwen model downloads try the official Qwen repositories on
ModelScope first and fall back to the pinned Hugging Face revisions. llama.cpp archives try an
HTTPS acceleration endpoint first and fall back to the official GitHub release. A mirror cannot
change executable contents undetected because the expected size and SHA-256 are built into the mod.

- llama.cpp `b9637`, MIT license: <https://github.com/ggml-org/llama.cpp>
- Qwen2.5 0.5B Instruct GGUF, Apache-2.0 license:
  <https://modelscope.cn/models/qwen/Qwen2.5-0.5B-Instruct-GGUF> and
  <https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF>
- Qwen2.5 1.5B Instruct GGUF, Apache-2.0 license:
  <https://modelscope.cn/models/qwen/Qwen2.5-1.5B-Instruct-GGUF> and
  <https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF>

The engine is always launched with `--host 127.0.0.1`. No public listening address is used.
