import type { Metadata } from "next";

export const metadata: Metadata = {
  title: "MC 自动翻译工具｜1.3 测试版已发布",
  description:
    "MC 自动翻译工具 1.3.0-test.2 预发行测试版，提供 25 个 Forge/Fabric 下载，支持服务器、模组、整合包界面与玩家发送内容翻译。",
};

const features = [
  {
    mark: "全",
    title: "不只翻译聊天",
    body: "覆盖聊天、记分板、标题、物品与容器界面等服务器、模组和整合包文本，并可选择直接替换，减少文字溢出。",
  },
  {
    mark: "离",
    title: "优先离线运行",
    body: "模型在你的电脑上推理。无需自建 API 服务器，网络不稳定时也能继续使用；仍保留 API 模式供高级用户选择。",
  },
  {
    mark: "隐",
    title: "隐私字段不翻译",
    body: "玩家名、服务器域名、IPv4、IPv6、端口、URL、数字与已有中文会在本地拆分并原样保留，不发送给翻译模型。",
  },
  {
    mark: "稳",
    title: "异步、缓存、发送翻译",
    body: "翻译不阻塞渲染线程；可单独开启发送内容翻译，超时或异常会安全回退原文，本地缓存减少重复推理。",
  },
];

const versions = [
  ["1.8.9、1.12.2", "Forge", "测试版已发布", "ready"],
  ["1.16.5", "Fabric / Forge", "测试版已发布", "ready"],
  ["1.19.2", "Fabric / Forge", "测试版已发布", "ready"],
  ["1.20.1", "Fabric / Forge", "测试版已发布", "ready"],
  ["1.21–1.21.11", "Fabric 单一 JAR", "测试版已发布", "ready"],
  ["1.21、1.21.1、1.21.3–1.21.11", "Forge 精确版本 JAR", "测试版已发布", "ready"],
  ["26.1–26.2", "Fabric 单一 JAR / Forge 精确版本 JAR", "测试版已发布", "ready"],
];

const releaseVersion = "1.3.0-test.2";
const githubDownloadBase =
  `https://github.com/wuxiangdan96-byte/mc-auto-translation-tool/releases/download/v${releaseVersion}`;

const releaseFile = (target: string) =>
  `MCAutoTranslationTool-${releaseVersion}-mc${target}.jar`;

const downloads = [
  {
    version: "1.8.9",
    loader: "Forge",
    java: "Java 8",
    file: releaseFile("1.8.9-forge"),
  },
  {
    version: "1.12.2",
    loader: "Forge",
    java: "Java 8",
    file: releaseFile("1.12.2-forge"),
  },
  {
    version: "1.16.5",
    loader: "Fabric",
    java: "Java 8",
    file: releaseFile("1.16.5-fabric"),
  },
  {
    version: "1.16.5",
    loader: "Forge",
    java: "Java 8",
    file: releaseFile("1.16.5-forge"),
  },
  {
    version: "1.19.2",
    loader: "Fabric",
    java: "Java 17",
    file: releaseFile("1.19.2-fabric"),
  },
  {
    version: "1.19.2",
    loader: "Forge",
    java: "Java 17",
    file: releaseFile("1.19.2-forge"),
  },
  {
    version: "1.20.1",
    loader: "Fabric",
    java: "Java 17",
    file: releaseFile("1.20.1-fabric"),
  },
  {
    version: "1.20.1",
    loader: "Forge",
    java: "Java 17",
    file: releaseFile("1.20.1-forge"),
  },
  {
    version: "1.21–1.21.11",
    loader: "Fabric 单一 JAR",
    java: "Java 21",
    file: releaseFile("1.21.x-fabric"),
  },
  ...["1.21", "1.21.1", "1.21.3", "1.21.4", "1.21.5", "1.21.6", "1.21.7", "1.21.8", "1.21.9", "1.21.10", "1.21.11"].map((version) => ({
    version,
    loader: "Forge",
    java: "Java 21",
    file: releaseFile(`${version}-forge`),
  })),
  {
    version: "26.1–26.2",
    loader: "Fabric 单一 JAR",
    java: "Java 25",
    file: releaseFile("26.x-fabric"),
  },
  ...["26.1", "26.1.1", "26.1.2", "26.2"].map((version) => ({
    version,
    loader: "Forge",
    java: "Java 25",
    file: releaseFile(`${version}-forge`),
  })),
];

export default function Home() {
  return (
    <main>
      <nav className="nav shell" aria-label="主导航">
        <a className="brand" href="#top" aria-label="MC 自动翻译工具首页">
          <span className="brandIcon" aria-hidden="true">
            文
          </span>
          <span>
            <strong>MC 自动翻译工具</strong>
            <small>MC Auto Translation Tool</small>
          </span>
        </a>
        <div className="navLinks">
          <a href="#features">功能</a>
          <a href="#privacy">隐私</a>
          <a href="#versions">兼容</a>
          <a className="navCta" href="#download">获取模组</a>
        </div>
      </nav>

      <section className="hero shell" id="top">
        <div className="heroCopy">
          <div className="eyebrow"><span /> 完全公益 · 免费开源 · 无广告</div>
          <h1>
            让服务器的每一句话，
            <em>都能被看懂。</em>
          </h1>
          <p className="heroLead">
            为 Minecraft Java 版打造的全界面自动翻译模组。保留原有游戏体验，
            自动处理聊天、记分板、箱子，以及模组和整合包界面里的英文内容。
          </p>
          <div className="heroActions">
            <a className="primaryButton" href="#download">下载 1.3 测试版 <span>→</span></a>
            <a className="textButton" href="#how">查看安装方法</a>
          </div>
          <div className="trustLine">
            <span>✓ 无需账号</span>
            <span>✓ 可完全离线</span>
            <span>✓ 不收集玩家数据</span>
          </div>
        </div>

        <div className="demoWrap" aria-label="翻译效果示意">
          <div className="pixelCloud one" />
          <div className="pixelCloud two" />
          <div className="gameCard">
            <div className="gameBar">
              <span className="lamp red" /><span className="lamp amber" /><span className="lamp green" />
              <span className="gameTitle">服务器大厅</span>
              <span className="status">● 离线翻译</span>
            </div>
            <div className="gameScene">
              <div className="scoreboard">
                <b>空岛生存</b>
                <p>玩家等级 <strong>16</strong></p>
                <p>在线人数 <strong>2,347</strong></p>
                <p>赛季进度 <strong>72%</strong></p>
              </div>
              <div className="chatStack">
                <div className="sourceText"><span>原文</span> Welcome to SkyBlock!</div>
                <div className="arrowDown">↓</div>
                <div className="translatedText"><span>译文</span> 欢迎来到空岛生存！</div>
              </div>
              <div className="privacyChip">玩家名与服务器地址保持原样</div>
            </div>
          </div>
        </div>
      </section>

      <section className="missionBand">
        <div className="shell missionInner">
          <p>这个项目不靠翻译收费，也不把玩家数据变成商品。</p>
          <strong>它存在的唯一目的，是让语言不再成为一起游戏的门槛。</strong>
        </div>
      </section>

      <section className="section shell" id="features">
        <div className="sectionHead">
          <div><span className="sectionKicker">核心能力</span><h2>翻译得更多，也克制得更多</h2></div>
          <p>模组只改显示层，不修改服务器数据，不代表玩家发送消息，也不会阻塞主线程。</p>
        </div>
        <div className="featureGrid">
          {features.map((item) => (
            <article className="featureCard" key={item.title}>
              <span className="featureMark">{item.mark}</span>
              <h3>{item.title}</h3>
              <p>{item.body}</p>
            </article>
          ))}
        </div>
      </section>

      <section className="privacySection" id="privacy">
        <div className="shell privacyGrid">
          <div className="privacyCopy">
            <span className="sectionKicker light">隐私设计</span>
            <h2>该保留的，永远原样保留。</h2>
            <p>文本先在本地被拆分。只有需要翻译的自然语言片段会进入离线模型或你自行配置的 API。</p>
            <ul>
              <li><span>01</span> 玩家名称与本机账号名</li>
              <li><span>02</span> 服务器域名、IP 地址与端口</li>
              <li><span>03</span> URL、数字、格式代码与已有中文</li>
            </ul>
          </div>
          <div className="privacyDiagram" aria-label="隐私处理流程示意">
            <div className="inputLine">Welcome <mark>Steve_42</mark> to <mark>play.example.cn:25565</mark></div>
            <div className="splitLabel">本地拆分</div>
            <div className="tokens">
              <span className="translateToken">Welcome</span>
              <span className="lockToken">Steve_42 🔒</span>
              <span className="translateToken">to</span>
              <span className="lockToken">play.example.cn:25565 🔒</span>
            </div>
            <div className="resultLine"><b>欢迎</b> Steve_42 <b>来到</b> play.example.cn:25565</div>
            <small>锁定字段不会进入翻译模型</small>
          </div>
        </div>
      </section>

      <section className="section shell" id="versions">
        <div className="sectionHead compact">
          <div><span className="sectionKicker">兼容状态</span><h2>先把常用版本做稳</h2></div>
          <p>每个版本都需要独立适配和实机验证。不会用“理论兼容”冒充已经支持。</p>
        </div>
        <div className="versionTable">
          <div className="versionHeader"><span>Minecraft</span><span>加载器</span><span>状态</span></div>
          {versions.map(([version, loader, state, kind]) => (
            <div className="versionRow" key={`${version}-${loader}`}>
              <strong>{version}</strong><span>{loader}</span>
              <span className={`versionState ${kind}`}><i />{state}</span>
            </div>
          ))}
        </div>
      </section>

      <section className="howSection" id="how">
        <div className="shell">
          <div className="sectionHead compact">
            <div><span className="sectionKicker">开始使用</span><h2>三步开始翻译</h2></div>
          </div>
          <div className="steps">
            <article><b>1</b><h3>选择对应版本</h3><p>下载与你的 Minecraft 版本及 Forge/Fabric 加载器匹配的 JAR。</p></article>
            <article><b>2</b><h3>放入 mods 文件夹</h3><p>不要改名或解压。Fabric 按 U 打开设置；现代 Forge 按 U 重新载入配置文件。</p></article>
            <article><b>3</b><h3>等待离线模型就绪</h3><p>默认优先国内镜像下载并校验文件；完成后即可在服务器中自动翻译。</p></article>
          </div>
        </div>
      </section>

      <section className="downloadSection shell" id="download">
        <div className="downloadIntro">
          <span className="sectionKicker light">1.3.0-test.2 预发行测试版</span>
          <h2>免费使用，也欢迎一起把它做得更好。</h2>
          <p>本次提供 25 个经过校验的 JAR，覆盖 Fabric 与 Forge 的已验证目标。请选择与你的 Minecraft 版本及加载器完全对应的文件，切勿跨版本或加载器混用。</p>
        </div>
        <div className="downloadGrid">
          {downloads.map((item) => (
            <article className="downloadCard" key={`${item.version}-${item.loader}`}>
              <div>
                <strong>Minecraft {item.version}</strong>
                <span>{item.loader} · {item.java}</span>
              </div>
              <a
                className="lightButton"
                href={`${githubDownloadBase}/${item.file}`}
                aria-label={`从 GitHub 下载 Minecraft ${item.version} ${item.loader} 版本`}
              >
                从 GitHub 下载 <span aria-hidden="true">↓</span>
              </a>
            </article>
          ))}
        </div>
        <div className="downloadMeta">
          <a href={`${githubDownloadBase}/SHA256SUMS.txt`}>SHA-256 校验文件</a>
          <a href="https://github.com/wuxiangdan96-byte/mc-auto-translation-tool/releases/tag/v1.3.0-test.2">查看测试版说明</a>
          <a href="https://github.com/wuxiangdan96-byte/mc-auto-translation-tool">查看 GitHub 源代码</a>
          <a href="https://space.bilibili.com/3546631091783712">原作者：B站「我小张7272635」</a>
          <span>转载或改编请保留原作者署名 · MIT License</span>
        </div>
      </section>

      <footer className="footer shell">
        <div className="brand footerBrand"><span className="brandIcon">文</span><span><strong>MC 自动翻译工具</strong><small>完全公益的 Minecraft 翻译项目</small></span></div>
        <p>不隶属于 Mojang Studios 或 Microsoft。Minecraft 是 Mojang Studios 的商标。</p>
        <a href="https://space.bilibili.com/3546631091783712">© 2026 原作者：我小张7272635</a>
      </footer>
    </main>
  );
}
