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
测试在它存在时以它为准再比一次。它不存在时只提示、不红灯——版本号 bump 后 `downloads/`
还没重新产生时，网站检查不该先坏；这个窗口一过就该看到清单。
同一支测试还会把两个头条数字绑回产生端：「48 个发布目标」比对 `prepare-release.yml` 的
`TARGETS_JSON` 笔数，「51 个精确版本实现」比对 `platforms/fabric/*/versions` 的实作目录数
（`26.x` 的版本目录直接挂在 bundle 根）。改发布目标、家族档名或新增 Fabric 版本时，
这五份文件与 `page.tsx` 必须一起改，否则网站检查会红。

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
