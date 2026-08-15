# MC 自动翻译工具

**MC Auto Translation Tool** 是一个面向 Minecraft Java 版的公益、开源、纯客户端全界面翻译模组。
服务器无需安装本模组；它可以翻译聊天、任务书、模组菜单、记分板、物品说明、告示牌等玩家可见文字。

[简体中文](README.md) · [繁體中文](../Zh-tw/README.md) · [English](../en/README.md) · [仓库首页](../../README.md)

[⬇️ 下载最新版](https://github.com/wuxiangdan96-byte/mc-auto-translation-tool/releases/latest) ·
[📖 安装与使用](USER_GUIDE.md) ·
[✅ 兼容性矩阵](COMPATIBILITY.md) ·
[📝 更新记录](../../CHANGELOG.md)

> 当前正式版与发布日期以 [GitHub Releases](https://github.com/wuxiangdan96-byte/mc-auto-translation-tool/releases/latest)
> 为准。请只使用与你的 Minecraft 版本和模组加载器相符的 JAR。

## 为什么使用它

| 特性 | 说明 |
| --- | --- |
| 全界面翻译 | 覆盖聊天、任务/配方界面、模组菜单、记分板、Tab、标题、Boss Bar、容器、物品名与 Lore、书、告示牌、全息文字等 |
| 纯客户端 | Minecraft 服务器无需安装模组，也不会收到额外的模组数据包 |
| 本地离线 | 推荐使用内置离线模式，不需要 API Key；模型和翻译内容保留在用户电脑上 |
| 多种服务 | 内置中国主流机器翻译与 LLM 服务，也支持 LibreTranslate、OpenAI 兼容和自定义 HTTP JSON API |
| 流畅与稳定 | 翻译在后台执行；未完成或服务异常时继续显示原文，不阻塞渲染线程 |
| 隐私保护 | 玩家名默认在本机分离，也可主动开启玩家名翻译；服务器地址、网址、数字和格式代码始终保持原样 |
| 屏蔽关键词 | 自定义不翻译关键词；匹配的整段文字保持原样，不会进入离线模型或在线 API |
| 本地缓存 | 合并相同请求并缓存译文，降低重复翻译的延迟和在线服务费用 |
| 可选发送翻译 | 可将普通聊天翻译后按顺序发送；该功能默认关闭，命令始终保持原样 |

## 快速开始

1. 确认游戏使用的 **Minecraft 版本、模组加载器和 Java 版本**。
2. 打开 [最新 Release](https://github.com/wuxiangdan96-byte/mc-auto-translation-tool/releases/latest)，按下表选择 JAR。
3. 将 JAR 放进该游戏实例的 `mods` 文件夹。Fabric 还需安装对应版本的 Fabric API。
4. 启动游戏。按 `F8` 开启或关闭自动翻译；按 `U` 打开设置。若已安装 Mod Menu，也可在模组列表打开同一设置页。
5. 首次使用建议选择“离线”与“仅译文”。详细步骤见[安装与使用指南](USER_GUIDE.md)。

Fabric、Forge 与 NeoForge 均可按 `U` 打开图形设置，直接编辑屏蔽词、玩家名翻译等选项。
已安装 Mod Menu 的 Fabric 实例也可从模组列表进入同一设置页。
腾讯云密钥等进阶选项仍可写在游戏实例的 `config/universal-translator.properties`。

## 下载与版本选择

正式版把 27 个精确版本构建产物整理为 **13 个可直接安装的 JAR**。Fabric 使用一个由 Loader
自动选择内嵌实现的全版本 JAR；Forge 只在已验证兼容的相邻版本间共用 JAR。
源码中另有已完成构建与 Loader 选版的邻近 Fabric 族群、Ornithe 旧版，以及 NeoForge 1.21.3／1.21.11；
这些目标尚未打入当前 GitHub 正式版，请不要把 v1.3.3 的 `fabric-all` 用在未列出的游戏版本上。

下表提供目前正式版各 JAR 的直接下载链接，并列出已验证但尚未随 v1.3.3 提供的版本。
发布新版本时，这些链接会随 README 一并更新：

| Minecraft | 加载器 | Java | 下载 |
| --- | --- | ---: | --- |
| 1.16.5、1.19.2、1.20.1、1.21–1.21.11、26.1–26.2 | Fabric | 8 / 17 / 21 / 25 | [下载 JAR](https://github.com/wuxiangdan96-byte/mc-auto-translation-tool/releases/download/v1.3.3/MCAutoTranslationTool-1.3.3-fabric-all.jar) |
| 1.0.0–1.8.8 | Fabric（Ornithe） | 8 | 尚未随 v1.3.3 提供 |
| 1.8.9–1.12.2 | Fabric（Ornithe） | 8 | 尚未随 v1.3.3 提供 |
| 1.13、1.13.1、1.13.2 | Fabric | 8 | 尚未随 v1.3.3 提供 |
| 1.14–1.15.2 | Fabric | 8 | 尚未随 v1.3.3 提供 |
| 1.16–1.16.4 | Fabric | 8 | 尚未随 v1.3.3 提供（1.16.5 见上方全版本 JAR） |
| 1.17–1.18.2 | Fabric | 17 | 尚未随 v1.3.3 提供 |
| 1.19、1.19.1、1.19.3、1.19.4 | Fabric | 17 | 尚未随 v1.3.3 提供（1.19.2 见上方全版本 JAR） |
| 1.20、1.20.2–1.20.6 | Fabric | 17 / 21 | 尚未随 v1.3.3 提供（1.20.1 见上方全版本 JAR） |
| 1.8.9 | Forge | 8 | [下载 JAR](https://github.com/wuxiangdan96-byte/mc-auto-translation-tool/releases/download/v1.3.3/MCAutoTranslationTool-1.3.3-mc1.8.9-forge.jar) |
| 1.12.2 | Forge | 8 | [下载 JAR](https://github.com/wuxiangdan96-byte/mc-auto-translation-tool/releases/download/v1.3.3/MCAutoTranslationTool-1.3.3-mc1.12.2-forge.jar) |
| 1.16.5 | Forge | 8 | [下载 JAR](https://github.com/wuxiangdan96-byte/mc-auto-translation-tool/releases/download/v1.3.3/MCAutoTranslationTool-1.3.3-mc1.16.5-forge.jar) |
| 1.19.2 | Forge | 17 | [下载 JAR](https://github.com/wuxiangdan96-byte/mc-auto-translation-tool/releases/download/v1.3.3/MCAutoTranslationTool-1.3.3-mc1.19.2-forge.jar) |
| 1.20.1 | Forge | 17 | [下载 JAR](https://github.com/wuxiangdan96-byte/mc-auto-translation-tool/releases/download/v1.3.3/MCAutoTranslationTool-1.3.3-mc1.20.1-forge.jar) |
| 1.21、1.21.1、1.21.3–1.21.5 | Forge | 21 | [下载 JAR](https://github.com/wuxiangdan96-byte/mc-auto-translation-tool/releases/download/v1.3.3/MCAutoTranslationTool-1.3.3-mc1.21-1.21.5-forge.jar) |
| 1.21.6–1.21.8 | Forge | 21 | [下载 JAR](https://github.com/wuxiangdan96-byte/mc-auto-translation-tool/releases/download/v1.3.3/MCAutoTranslationTool-1.3.3-mc1.21.6-1.21.8-forge.jar) |
| 1.21.9–1.21.11 | Forge | 21 | [下载 JAR](https://github.com/wuxiangdan96-byte/mc-auto-translation-tool/releases/download/v1.3.3/MCAutoTranslationTool-1.3.3-mc1.21.9-1.21.11-forge.jar) |
| 26.1–26.1.2 | Forge | 25 | [下载 JAR](https://github.com/wuxiangdan96-byte/mc-auto-translation-tool/releases/download/v1.3.3/MCAutoTranslationTool-1.3.3-mc26.1-26.1.2-forge.jar) |
| 26.2 | Forge | 25 | [下载 JAR](https://github.com/wuxiangdan96-byte/mc-auto-translation-tool/releases/download/v1.3.3/MCAutoTranslationTool-1.3.3-mc26.2-forge.jar) |
| 1.20.1 | NeoForge 47.1.106–47.1.x | 17 | [下载 JAR](https://github.com/wuxiangdan96-byte/mc-auto-translation-tool/releases/download/v1.3.3/MCAutoTranslationTool-1.3.3-mc1.20.1-neoforge.jar) |
| 1.21.1 | NeoForge 21.1.248 | 21 | [下载 JAR](https://github.com/wuxiangdan96-byte/mc-auto-translation-tool/releases/download/v1.3.3/MCAutoTranslationTool-1.3.3-mc1.21.1-neoforge.jar) |
| 1.21.3 | NeoForge 21.3.97 | 21 | 尚未随 v1.3.3 提供 |
| 1.21.11 | NeoForge 21.11.45 | 21 | 尚未随 v1.3.3 提供 |

> **不要跨加载器或超出文件名范围混用 JAR。** 相邻 Minecraft 版本的渲染 API 与 Mixin
> 目标可能不同；只有完成验证的版本才会写入发布 metadata。

[查看最新 Release](https://github.com/wuxiangdan96-byte/mc-auto-translation-tool/releases/latest) ·
[查看所有版本](https://github.com/wuxiangdan96-byte/mc-auto-translation-tool/releases) ·
[查看实际验证范围](COMPATIBILITY.md)

## 翻译方式与隐私

| 方式 | 需要 API Key | 文字是否离开本机 | 适合场景 |
| --- | --- | --- | --- |
| 离线 Lite / Quality | 否 | 否 | 推荐；首次使用会下载平台引擎和所选模型 |
| 本地 LibreTranslate | 视服务配置 | 否 | 已在本机运行 LibreTranslate 的用户 |
| 远程 LibreTranslate | 视服务配置 | 是 | 使用可信的 HTTPS 翻译服务 |
| 本地 OpenAI 兼容 API | 视服务配置 | 否 | 自行运行 llama.cpp、Ollama 等兼容服务 |
| 在线 OpenAI 兼容 API | 通常需要 | 是 | 使用用户自行选择的 HTTPS LLM 服务 |
| 专用机器翻译／LLM API | 通常需要 | 是 | 百度、腾讯云、阿里云、有道、火山引擎、讯飞、华为云、DeepSeek、通义千问、智谱等 |
| 自定义 HTTP JSON API | 视服务配置 | 视端点而定 | 自行配置安全的请求模板、鉴权头与嵌套响应路径 |
| 腾讯兼容接口 | 是 | 是 | 仅用于兼容旧配置 |

- 离线模式只在本机回环地址上运行；服务器、模组和整合包文字不会发送到项目方。
- 使用远程 API 时，获准翻译的可见文字会发送到用户配置的服务；项目不会代管密钥。
- API Key 只保存在当前游戏实例的配置文件中。远程端点必须使用 HTTPS。
- 可以分别关闭聊天内容、其他界面和玩家主动发送翻译；发送翻译默认关闭。
- 缓存键使用 SHA-256，原文不会以明文形式写入缓存文件。
- 命中屏蔽关键词的文字会留在本机，不会发送到翻译服务。

完整在线服务和自定义请求配置见[在线翻译 API 配置](ONLINE_APIS.md)；
其余设置、模型下载说明和常见问题见[安装与使用指南](USER_GUIDE.md)。

## 当前验证状态

当前正式版的全部目标均通过干净构建、共享核心自测、Mixin/refmap、运行时映射、发布结构与
SHA-256 校验。Fabric 全版本 JAR 内含 19 个精确实现，并已通过各目标版本的真实 Loader
选择测试；NeoForge 1.20.1 已完成实际客户端启动与模组初始化。

“能够编译”不等同于“完成服务器内人工回归”。各版本的构建、启动和人工验证层级会分别记录，
详见[兼容性矩阵](COMPATIBILITY.md)。

## 文档

| 文档 | 内容 |
| --- | --- |
| [安装与使用指南](USER_GUIDE.md) | 安装、首次设置、离线模型、API 配置、常见问题 |
| [兼容性矩阵](COMPATIBILITY.md) | 各 Minecraft／加载器目标的实际验证层级 |
| [构建指南](BUILDING.md) | 开发环境、Gradle 任务、发布 JAR 验证 |
| [在线翻译 API 配置](ONLINE_APIS.md) | 内置服务、自定义 HTTP JSON、超时、重试与安全限制 |
| [架构说明](ARCHITECTURE.md) | 共用核心、平台适配、翻译与缓存流程 |
| [第三方离线服务](THIRD_PARTY_OFFLINE.md) | 连接用户自行运行的本地翻译服务 |
| [更新记录](../../CHANGELOG.md) | 正式版与测试版变更 |
| [多语言文档目录](../README.md) | 简体中文、繁體中文与 English 文档入口 |

## 交流、作者与授权

- QQ 交流群：`1054795488`
- 爱发电：[支持「我小张7272635」](https://afdian.com/a/XiaoZhangGG)
- 原作者：[B站「我小张7272635」](https://space.bilibili.com/3546631091783712)
- 授权协议：[MIT License](../../LICENSE)

赞助完全自愿；本项目会继续保持公益、开源和免费下载。转载、再发布或改编时，请保留
原作者署名与 MIT License 版权声明。
