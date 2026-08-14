# 線上翻譯 API 設定

[返回使用指南](USER_GUIDE.md) · [简体中文完整設定表](../Zh-cn/ONLINE_APIS.md) · [English](../en/ONLINE_APIS.md)

1.3.1 支援 `offline`、`libretranslate`、`baidu`、`tencent-tmt`、`tencent-hunyuan`、
`aliyun-mt`、`youdao`、`volcengine-mt`、`iflytek-niutrans`、`huawei-cloud-mt`、`deepseek`、
`dashscope`、`volcengine-ark`、`zhipu`、`openai-compatible` 與 `custom-http-json`。

所有服務專用欄位、自訂 JSON 範本及安全限制請參閱
[簡體中文完整 API 設定表](../Zh-cn/ONLINE_APIS.md)。請勿公開真實密鑰。遠端端點必須使用
HTTPS；HTTP 只允許 `127.0.0.1`、`localhost` 與 `::1`。自訂模式只允許 POST 或 PUT，
會限制範本及請求頭大小，而且不會執行腳本或任意程式碼。

```properties
api-connect-timeout-ms=5000
api-read-timeout-ms=120000
api-max-attempts=3
api-min-request-interval-ms=60
```

只有網路錯誤、逾時、限流及伺服器錯誤會重試；鑑權與請求格式錯誤會立即停止。
