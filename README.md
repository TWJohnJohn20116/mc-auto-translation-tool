# MC 自动翻译工具（MC Auto Translation Tool）

[简体中文](README.md) · [繁體中文](docs/Zh-tw/README.md) · [English](docs/en/README.md)

一个面向 Minecraft Java 版的公益、开源、纯客户端全界面翻译模组。

[⬇️ 下载最新版](https://github.com/wuxiangdan96-byte/mc-auto-translation-tool/releases/latest) ·
[📚 文档中心](docs/README.md) · [📖 安装与使用说明](docs/Zh-cn/USER_GUIDE.md)

原作者：[B站「我小张7272635」](https://space.bilibili.com/3546631091783712)。
转载、再发布或改编时，请保留原作者署名与 MIT License 版权声明。

目标是翻译服务器、模组和整合包显示给玩家的可见文字，包括聊天、任务书、
模组设置与菜单、记分板、Tab 列表、Action Bar、标题、Boss Bar、容器标题、
物品名称与 Lore、告示牌、书、全息文字和实体自定义名称。玩家名、数字、网址和
Minecraft 样式代码默认保留。

## 下载

推荐从 [GitHub Releases 下载最新版](https://github.com/wuxiangdan96-byte/mc-auto-translation-tool/releases/latest)。
请务必选择与你的 Minecraft 版本和模组加载器完全对应的文件：

| Minecraft | 加载器 | 下载 |
| --- | --- | --- |
| 1.8.9 | Forge | [下载 JAR](https://github.com/wuxiangdan96-byte/mc-auto-translation-tool/releases/download/v1.2/MCAutoTranslationTool-1.2-mc1.8.9-forge.jar) |
| 1.12.2 | Forge | [下载 JAR](https://github.com/wuxiangdan96-byte/mc-auto-translation-tool/releases/download/v1.2/MCAutoTranslationTool-1.2-mc1.12.2-forge.jar) |
| 1.20.1 | Fabric | [下载 JAR](https://github.com/wuxiangdan96-byte/mc-auto-translation-tool/releases/download/v1.2/MCAutoTranslationTool-1.2-mc1.20.1-fabric.jar) |
| 1.20.1 | Forge | [下载 JAR](https://github.com/wuxiangdan96-byte/mc-auto-translation-tool/releases/download/v1.2/MCAutoTranslationTool-1.2-mc1.20.1-forge.jar) |
| 1.21.11 | Fabric | [下载 JAR](https://github.com/wuxiangdan96-byte/mc-auto-translation-tool/releases/download/v1.2/MCAutoTranslationTool-1.2-mc1.21.11-fabric.jar) |
| 1.21.11 | Forge | [下载 JAR](https://github.com/wuxiangdan96-byte/mc-auto-translation-tool/releases/download/v1.2/MCAutoTranslationTool-1.2-mc1.21.11-forge.jar) |

请勿跨 Minecraft 版本或加载器混用 JAR。为了避免 Mixin 注入失败导致游戏无法启动，
发布文件只声明已完成构建验证的精确版本；相邻版本需要单独验证后才会加入支持范围。

[查看全部版本与更新说明](https://github.com/wuxiangdan96-byte/mc-auto-translation-tool/releases) ·
[SHA-256 校验文件](https://github.com/wuxiangdan96-byte/mc-auto-translation-tool/releases/download/v1.2/SHA256SUMS.txt)

## 设计原则

- 服务器无需安装模组。
- 默认使用用户电脑上的离线模型，不要求 API 密钥或项目服务器。
- 离线模式仅绑定 `127.0.0.1`，服务器文字不会离开用户电脑。
- 可使用 LibreTranslate、腾讯兼容接口或 OpenAI 兼容 LLM API；离线失败时的 API 回退默认关闭。
- 可单独开启“发送翻译”，普通聊天后台翻译后再按原顺序发送；命令保持原样。
- 翻译在后台执行；服务不可用时立即保留原文，不影响游戏。
- 相同文本和动态文本模板使用本地缓存，减少延迟与费用。
- 在进入世界前也会捕获使用原版字体渲染器的模组菜单和整合包标题界面。
- 默认不翻译玩家名、坐标、数字、网址和格式代码。
- 用户可以按服务器关闭私聊或其他敏感内容的外发。

## 版本模块

| Minecraft | 加载器 | Java |
| --- | --- | --- |
| 26.2（开发中） | Fabric | 25 |
| 26.1.2（开发中） | Fabric | 25 |
| 26.1（开发中） | Fabric | 25 |
| 1.21.5（开发中） | Fabric | 21 |
| 1.21.4 / 1.21.5 / 1.21.11（单一 JAR，开发中） | Fabric | 21 |
| 1.21.4（开发中） | Fabric | 21 |
| 1.21.11 | Forge | 21 |
| 1.21.11 | Fabric | 21 |
| 1.20.1 | Forge | 17 |
| 1.20.1 | Fabric | 17 |
| 1.19.2（开发中） | Fabric | 17 |
| 1.16.5（开发中） | Fabric | 8 |
| 1.12.2 | Forge | 8 |
| 1.8.9 | Forge | 8 |

不同游戏版本会生成不同 JAR，但共享相同的核心逻辑和配置语义。

主分支另包含 Fabric 1.16.5、1.19.2、1.21.4、1.21.5、26.1、26.1.2 与 26.2
开发适配。在实机启动和服务器内回归完成前，这些源码目标不列入上方正式版下载。

## 当前状态

1.2 正式版提供六个独立客户端 JAR：新增 1.20.1 Fabric、1.20.1 Forge 和
1.21.11 Forge，并重新构建原有三个版本。六条构建线均通过干净构建，现代版本还完成
Fabric 重映射或 Forge 运行时映射检查；共享核心自测通过。1.21.11 Forge 的实际启动
验证已进入 Forge 图形初始化阶段，但当前自动化环境没有可用显示器，因此不把它写成
“已到主菜单”。详细证据见兼容性矩阵。

未发布的七个 Fabric 开发目标已通过本次联合干净构建；其中 26.1 保留此前主菜单
启动基线，其余目标仍需实机启动与服务器内人工回归后才能生成正式发行文件。

Fabric 版本进入游戏后按 `U` 打开设置；Forge 1.20.1/1.21.11 当前使用
`config/universal-translator.properties`，修改后按 `U` 重新载入。模组默认关闭；新安装默认选择“离线”，并使用
“仅译文”替换方式，避免记分板和容器文字因双语拼接溢出。按 `F8` 可随时开启或
关闭翻译，两个快捷键都能在 Minecraft 按键绑定界面修改。首次遇到待翻译文字后会在后台下载约 10–17 MB 的平台引擎
和 491 MB 的 Lite 模型，下载期间继续显示原文；模型默认优先使用 ModelScope
国内源，失败后自动续传并回退官方源。所有文件通过大小和 SHA-256 校验后才会执行。
也可以继续选择 LibreTranslate 或旧腾讯兼容模式。
进入服务器约三秒后，聊天栏会显示一条仅限本机的 `U`/`F8` 操作提示，不会发送
任何聊天消息或数据包给服务器。

已经验证的行为：

- 保护玩家名、服务器 IP/域名、端口、颜色代码、数字、百分比和网址。
- 受保护内容在本机分段，不会发送给离线模型或在线 API。
- 将动态记分板内容归一为可复用模板。
- 缓存译文并合并同时发生的相同请求。
- 对已经是目标语言或只有数字的内容跳过联网。
- 中英文混合文本只翻译英文片段，已有中文保持不变。
- 译文可以使用青色、绿色、金色等独立颜色，也可保留原色。
- 翻译服务异常时返回原文。
- 后台翻译不会阻塞渲染线程。
- 设置保存后无需重启游戏即可应用。
- 聊天与其他界面可分别禁止外发。
- 玩家发送翻译默认关闭，目标语言与界面翻译目标语言分开设置。
- 模组菜单、任务/配方界面和整合包自定义标题屏可在未连接服务器时自动翻译。
- 离线 Lite 与 Quality 模型按需安装，不放入模组 JAR。

## 隐私提示

离线模式不会发送服务器、模组或整合包文字。在线 API 模式或“API 回退”意味着
选中的服务器文字以及本机可见的模组/整合包文字可能被发送到用户配置的翻译服务。项目会提供
明确的总开关、聊天/其他内容开关和本地缓存。密钥只在用户本机配置，禁止提交
到代码仓库。远程端点必须使用 HTTPS；只有精确的本机回环地址允许 HTTP。

详细安装和使用方法见 `docs/Zh-cn/USER_GUIDE.md`，实际验证范围和后续版本顺序见
`docs/Zh-cn/COMPATIBILITY.md`。官网源码位于 `website/`。
