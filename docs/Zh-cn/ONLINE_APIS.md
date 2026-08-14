# 在线翻译 API 配置

[返回使用指南](USER_GUIDE.md) · [English](../en/ONLINE_APIS.md) · [繁體中文](../Zh-tw/ONLINE_APIS.md)

1.3.3 可在同一份 `config/universal-translator.properties` 中选择下列服务。关闭游戏后修改配置，
再启动游戏；支持设置页的版本也可以按 `U` 重新载入。密钥只填在自己的游戏实例中，
不要把真实密钥发到聊天、Issue、截图或仓库。

## 支持的服务

| `provider` | 服务 | 必填配置 |
| --- | --- | --- |
| `offline` | 本机离线模型 | 无 |
| `libretranslate` | LibreTranslate | `libretranslate-endpoint`，按需填写 `api-key` |
| `baidu` | 百度翻译开放平台 | `baidu-app-id`、`baidu-secret` |
| `tencent-tmt` | 腾讯云机器翻译 TMT | `tencent-tmt-secret-id`、`tencent-tmt-secret-key` |
| `tencent-hunyuan` | 腾讯混元旧兼容接口 | `tencent-secret-id`、`tencent-secret-key` |
| `aliyun-mt` | 阿里云机器翻译 | `aliyun-access-key-id`、`aliyun-access-key-secret` |
| `youdao` | 有道智云文本翻译 | `youdao-app-key`、`youdao-secret` |
| `volcengine-mt` | 火山引擎机器翻译 | `volcengine-access-key`、`volcengine-secret-key` |
| `iflytek-niutrans` | 讯飞机器翻译 | `iflytek-app-id`、`iflytek-api-key`、`iflytek-api-secret` |
| `huawei-cloud-mt` | 华为云机器翻译 | `huawei-project-id`、`huawei-auth-token` |
| `deepseek` | DeepSeek 对话模型 | `deepseek-api-key` |
| `dashscope` | 阿里云百炼／通义千问 | `dashscope-api-key` |
| `volcengine-ark` | 火山方舟 | `volcengine-ark-api-key`、`volcengine-ark-model` |
| `zhipu` | 智谱 GLM | `zhipu-api-key` |
| `openai-compatible` | 任意 OpenAI Chat Completions 兼容服务 | `llm-api-endpoint`、`llm-api-model`，按需填写 `llm-api-key` |
| `custom-http-json` | 自定义 HTTP JSON API | 见下文 |

专用机器翻译接口通常延迟和费用更稳定；大模型接口更适合需要上下文润色的内容。
服务的价格、配额和数据政策由服务商决定，请在其控制台设置消费上限，并优先使用最小权限子账号密钥。

## 通用网络配置

```properties
api-connect-timeout-ms=5000
api-read-timeout-ms=120000
api-max-attempts=3
api-min-request-interval-ms=60
```

仅网络错误、超时、限流和服务器端错误会自动重试，鉴权或请求格式错误不会反复重试。
`api-max-attempts` 允许 1–5 次，最小请求间隔允许 0–60000 毫秒。远程地址必须使用
HTTPS；HTTP 只允许 `127.0.0.1`、`localhost` 或 `::1` 回环地址。

## 配置示例

下面只使用占位符，不要把真实密钥复制进文档。

```properties
# 百度
provider=baidu
baidu-app-id=你的APP_ID
baidu-secret=你的密钥

# 腾讯云 TMT
# provider=tencent-tmt
# tencent-tmt-secret-id=你的SecretId
# tencent-tmt-secret-key=你的SecretKey

# 阿里云机器翻译
# provider=aliyun-mt
# aliyun-access-key-id=你的AccessKeyId
# aliyun-access-key-secret=你的AccessKeySecret

# 有道
# provider=youdao
# youdao-app-key=你的应用ID
# youdao-secret=你的应用密钥
# youdao-vocab-id=

# 火山引擎机器翻译
# provider=volcengine-mt
# volcengine-access-key=你的AccessKey
# volcengine-secret-key=你的SecretKey
# volcengine-region=cn-north-1

# 讯飞机器翻译
# provider=iflytek-niutrans
# iflytek-app-id=你的APPID
# iflytek-api-key=你的APIKey
# iflytek-api-secret=你的APISecret

# 华为云：IAM Token 会过期，到期后需重新填写
# provider=huawei-cloud-mt
# huawei-project-id=你的ProjectId
# huawei-auth-token=你的IAM令牌
```

大模型预设使用服务商的 OpenAI 兼容接口：

```properties
# DeepSeek
provider=deepseek
deepseek-api-key=你的APIKey
deepseek-model=deepseek-v4-flash

# 阿里云百炼
# provider=dashscope
# dashscope-api-key=你的APIKey
# dashscope-model=qwen-plus

# 火山方舟的 model 必须填写控制台中的推理接入点 ID 或模型 ID
# provider=volcengine-ark
# volcengine-ark-api-key=你的APIKey
# volcengine-ark-model=你的接入点ID

# 智谱
# provider=zhipu
# zhipu-api-key=你的APIKey
# zhipu-model=glm-5.2
```

若服务商更改模型名，可直接修改相应 `*-model`。端点也可通过相应 `*-endpoint` 配置覆盖。

## OpenAI 兼容服务

```properties
provider=openai-compatible
llm-api-endpoint=https://你的服务域名/v1/chat/completions
llm-api-key=你的APIKey
llm-api-model=模型名
```

本机兼容服务可以使用 `http://127.0.0.1:端口/v1/chat/completions` 并留空密钥。
模组只调用 Chat Completions 文本接口，不会执行工具调用，也不会向模型开放本机文件或游戏控制权。

## 自定义 HTTP JSON API

自定义模式适用于请求和响应都能用固定 JSON 模板表达的翻译服务：

```properties
provider=custom-http-json
custom-api-endpoint=https://api.example.com/translate
custom-api-method=POST
custom-api-content-type=application/json; charset=utf-8
custom-api-key=你的APIKey
custom-api-auth-header=Authorization
custom-api-auth-prefix=Bearer\u0020
custom-api-request-template={"text":${textJson},"source":${sourceJson},"target":${targetJson}}
custom-api-response-path=data.translation
custom-api-header.X-Client=mc-auto-translation-tool
```

可用占位符：

- `${textJson}`、`${sourceJson}`、`${targetJson}`、`${apiKeyJson}`：包含完整 JSON 引号并自动转义，推荐使用。
- `${text}`、`${source}`、`${target}`、`${apiKey}`：只进行 JSON 字符串内容转义，适合放在已有引号内部。
- `custom-api-response-path` 支持 `translatedText`、`data.translation`、`choices[0].message.content` 这类路径。
- 额外请求头使用 `custom-api-header.请求头名称=值`；值中也能使用上述占位符。
- 请求方法仅允许 `POST` 或 `PUT`，模板最大 64 KiB，最多 32 个自定义请求头。

为防止请求走私和误配，自定义模式禁止覆盖 `Host`、`Content-Length`、
`Transfer-Encoding`、`Connection`、`Proxy-Authorization` 与 `Upgrade`。它只做模板替换，
不会运行脚本或任意代码。

## 隐私与排错

在线模式会把你允许翻译的可见文字发送到所选服务商；关闭“聊天内容”或“其他界面”可分别阻止对应内容外发。
玩家名、地址、网址、数字和格式代码仍会先在本机分离。遇到错误时先检查服务商配额、区域、模型名、
系统时间和 HTTPS 端点；日志或 Issue 中请删除密钥、令牌和私人聊天内容。
