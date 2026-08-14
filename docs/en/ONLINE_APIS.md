# Online translation API configuration

[Back to the user guide](USER_GUIDE.md) · [简体中文](../Zh-cn/ONLINE_APIS.md) · [繁體中文](../Zh-tw/ONLINE_APIS.md)

Version 1.3.1 supports `offline`, `libretranslate`, `baidu`, `tencent-tmt`, `tencent-hunyuan`,
`aliyun-mt`, `youdao`, `volcengine-mt`, `iflytek-niutrans`, `huawei-cloud-mt`, `deepseek`,
`dashscope`, `volcengine-ark`, `zhipu`, `openai-compatible`, and `custom-http-json` in
`config/universal-translator.properties`.

The canonical list of provider-specific property names and safe custom-JSON examples is maintained in
the [Simplified Chinese API guide](../Zh-cn/ONLINE_APIS.md). Never publish real credentials. Remote
endpoints must use HTTPS; HTTP is allowed only for the exact loopback hosts `127.0.0.1`, `localhost`,
and `::1`. The custom provider accepts only POST or PUT, applies bounded templates and headers,
and never executes scripts or arbitrary code.

Common reliability settings are:

```properties
api-connect-timeout-ms=5000
api-read-timeout-ms=120000
api-max-attempts=3
api-min-request-interval-ms=60
```

Only network failures, timeouts, rate limits, and server errors are retried. Authentication and malformed
request errors fail immediately. Online providers send enabled visible text to the selected service; use
the separate chat and non-chat privacy switches to control that transmission.
