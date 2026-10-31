# MC 自动翻译工具官网

完全公益、免费开源的 Minecraft Java 版全界面自动翻译模组官网。

## 本地开发

需要 Node.js 22.18+ 与 pnpm 11（`package.json` 的 `engines` 与 `packageManager` 是唯一来源）。

```bash
pnpm install
pnpm run dev
```

## 验证

脚本与 eslint 参数都写在 `website/package.json`，这里不再抄第二份：

```bash
pnpm run lint
pnpm test
```

`pnpm test` 会先跑一次 `vinext build`，再执行 `tests/rendered-html.test.mjs` 与
`tests/chatgpt-auth.test.mjs`。后者直接 import `.ts` 模块，依赖 Node 22.18 起才默认开启的
类型剥离；低于该版本会在 import 阶段直接失败，而不是把全部测试 skip 掉后回传成功。

`rendered-html.test.mjs` 还会把页面渲染出的下载档名与根 `README.md`、三份 `docs/*/README.md`
逐一比对（同一批 31 个档名被手抄了五份，原本没有任何关卡），权威则是
`downloads/<releaseVersion>/release-assets.txt`：那份清单由 `scripts/prepare_release_assets.py`
与 31 颗可安装 JAR 同一批产生（`prepare-release.yml` 把它写进 `downloads/<版本>/` 并随 PR 提交），
测试在它存在时以它为准再比一次。只有 `downloads/<releaseVersion>` 这个目录本身还不存在
（版本号 bump 后、`prepare-release` 还没跑）时才略过；目录在、清单不在一律算失败——
清单是「文件 ↔ 真实资产」比对的唯一凭据。
同一支测试还会把两个头条数字绑回产生端：「48 个发布目标」比对 `prepare-release.yml` 的
`TARGETS_JSON` 笔数，「51 个精确版本实现」比对 `platforms/fabric/*/versions` 的实作目录数
（`26.x` 的版本目录直接挂在 bundle 根）。改发布目标、家族档名或新增 Fabric 版本时，
这五份文件与 `page.tsx` 必须一起改，否则网站检查会红。

同一支测试还有七条关卡，都用来接住「照著文件做会失败」的漂移：把「31 个可直接安装的 JAR」
绑回页面渲染出的 `.jar` 连结数（13 份文件用四种措辞手抄，先前只守 `page.tsx` 的一种）；把文件里
反引号内的仓库路径逐一比对实际目录（`legacy/` → `platforms/forge/legacy/`、版本专案 → `versions/`
这类目录重构之后最容易漏）；把产物档名、`downloads/<版本>` 与 `--version` 的版本号绑回
`gradle.properties` 的 `mod_version`；把四份 README、`page.tsx` 与三份 `USER_GUIDE.md` 的 Fabric
版本区间清单展开后比对 `platforms/fabric/*/versions` 的目录集合（数字有守、清单没守，新增版本时
就会变成「内含 52 个实作」但只列出 51 个版本名）。另外三条守的是「贴上去就会被照抄」的形式：
资产档名 `MCAutoTranslationTool-<版本>-<目标>.jar` 的版本段必须是 `mod_version`，或用
`<mod_version>` 占位符（写少了版本段，读者会去找一个不存在的档案）；三份 `USER_GUIDE.md` 的
Forge 家族列要逐版本对上 `platforms/forge/{1.21,26.x}/versions/<版本>/build.gradle` 的
`ext.forgeVersion`；`page.tsx` 的「NeoForge N 个版本各一颗」绑回 `prepare-release.yml` 的
neoforge 目标数，`CHANGELOG.md` 的第一段标题必须是 `## <mod_version> - `（发布派送拿它当 release
notes，漏写会在合并之后才失败）。

这些关卡只在 website job 里跑，而它由 `git diff` 决定是否启动：只有 `website/`、`README.md`、
`docs/` 有变动才跑（`build.yml`），push `main` 时一律跑。所以**反方向没有守**——
`platforms/**`、`prepare-release.yml` 的 `TARGETS_JSON`、`gradle.properties`、`downloads/**`
这些「产生端」自己变动、文件没动的 PR 不会启动这个 job，数字会在 13 份文件上静默漂移，直到下一个
动到 `website/`／`README.md`／`docs/` 的 PR（可能只是无关的 npm 更新）或发布派送才红。要补这一半，
得在 `scripts/ci/` 加一条 `check` job 的权威比对（`test_ci_docs.py` 已有同型先例），或把 `build.yml`
的 `git diff` 正则扩到那些路径——两条都属 CI／脚本分区的改动。

## 部署

`app/layout.tsx` 的 `metadataBase` 只从 `NEXT_PUBLIC_SITE_URL` 取，没有备用网域：
`og:image` 用的是 `/og-card.png` 这种以 `/` 开头的路径，任何备用 base 都会把路径丢掉，
解析成 `<备用网域>/og-card.png` 这种不存在的图。部署时必须设定
`NEXT_PUBLIC_SITE_URL=https://<真实网域>`，否则社交平台拿到的预览图是空的——
Discord／Twitter／QQ／LINE 都不接受相对 URL，缺图与「指向不存在的网域」对使用者是一样的结果。

`tests/rendered-html.test.mjs` 对这条约定做双向断言：设了环境变量时预览图必须是同来源的
绝对 URL，没设时必须正好是 `/og-card.png`。也就是说，未设定环境变量的部署不会让测试变红
（测试走的是相对路径分支），要靠部署清单把关；CI 若要在真实管线上验证绝对 URL 分支，
得在 website job 给一个占位来源（例如 `NEXT_PUBLIC_SITE_URL=https://mc-auto-translation-tool.invalid`），
该 job 位于 `.github/workflows/build.yml`。

`.openai/hosting.json` 的 `d1` 字段同时是开关与绑定名称，值必须正好是 `DB`（对应
`db/index.ts` 里的 `env.DB`）；换成别的名字会让每次资料库请求都拿到「绑定不存在」，
而不是连上数据库。

网站不使用追踪器、广告或数据收集服务。
