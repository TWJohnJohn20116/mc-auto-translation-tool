# `.openai/hosting.json` 的约束

`hosting.json` 是 JSON，不能写注释，所以这份约束放在旁边：

- `d1` **同时是开关和绑定名称**。它的值会原样成为 `vite.config.ts` 里
  `d1_databases[].binding`，而 `db/index.ts` 读的是硬编码的 `env.DB`。
  因此这个值必须是 `DB`；写成 `NOTES` 之类的名字，`env.DB` 就永远是 `undefined`，
  每次请求都会以「D1 binding `DB` is unavailable」失败（HTTP 503）。
- `d1` 为 `null` 表示不启用数据库，此时 `d1_databases` 是空数组，`getDb()` 抛出同一个错误。
- `r2` 同理，`db`/`vite.config.ts` 之外没有第二个读取点。

改完 `hosting.json` 之后不需要改代码，但必须重新建置：`hosting.json` 是在
`tooling/sites-vite-plugin.ts` 的 `closeBundle` 阶段被复制进 `dist/.openai/` 的。
