# MC 自动翻译工具官网

完全公益、免费开源的 Minecraft Java 版全界面自动翻译模组官网。

## 本地开发

需要 Node.js 22.13+ 与 pnpm 11。

```bash
pnpm install
pnpm run dev
```

## 验证

```bash
pnpm run build
pnpm exec eslint . --ignore-pattern dist --ignore-pattern .next
node --test tests/rendered-html.test.mjs
```

网站不使用追踪器、广告或数据收集服务。
