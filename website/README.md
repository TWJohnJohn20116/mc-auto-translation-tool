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
类型剥离；低于该版本会在 import 阶段直接失败，而不是把 5 个测试全部 skip 掉后回传成功。

## 部署

`app/layout.tsx` 的 `metadataBase` 只从 `NEXT_PUBLIC_SITE_URL` 取，没有备用网域：
`og:image` 用的是 `/og-card.png` 这种以 `/` 开头的路径，任何备用 base 都会把路径丢掉，
解析成 `<备用网域>/og-card.png` 这种不存在的图。部署时必须设定
`NEXT_PUBLIC_SITE_URL=https://<真实网域>`，否则社交平台拿到的预览图是空的。

`.openai/hosting.json` 的 `d1` 字段同时是开关与绑定名称，值必须正好是 `DB`（对应
`db/index.ts` 里的 `env.DB`）；换成别的名字会让每次资料库请求都拿到「绑定不存在」，
而不是连上数据库。

网站不使用追踪器、广告或数据收集服务。
