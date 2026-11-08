# 模型设置（Muse Manus）

简体中文 | [English](models.en.md)

Muse Manus 仅保留**自定义 OpenAI 兼容（Chat Completions）**模型连接。没有 ChatGPT、xAI、OpenRouter 账号登录，也没有 Anthropic Messages、Gemini GenerateContent 或 OpenAI Responses 适配器与预设。

在「设置 › 模型」选择「自定义」，输入可信服务端的 Base URL 和模型 ID。按需要输入 API Key；本机或局域网的服务可无 Key。选择「测试并保存」验证工具调用，之后再用于主模型。可分别选择看图和轻量模型；如接口列出的模型不支持 Chat Completions，必须换模型或接入兼容的网关，应用不会静默改用其他协议或地址。

**旧配置升级**：不支持的接口类型和 OAuth 授权账户将从本机服务商列表与用途选择中剔除；支持的自定义 OpenAI 兼容配置继续保留。为保护隐私，遗留账户的 Key/令牌随被删除的服务商清理；用户可能需要重新选择主模型。

**MUSE 集成**：此模型配置是 Android 客户端的可选模型入口；与主代理的计算环境、电脑、MCP 通道不同。MCP 服务器自身的授权机制没有移除。

## 上下文和思考强度

窗口大小、压缩触发点及读取超时可以在用途卡下调整。只在服务商明确支持并报告了档位时提供思考强度；发送采用 OpenAI-compatible 的参数格式。主模型需要工具调用能力，看图模型需要视觉能力。

## 本地或局域网服务器

支持可信的 OpenAI-compatible 地址，例如 `http://192.168.1.10:11434/v1`。公网服务推荐 HTTPS，不要将 API Key 发送到未经验证的地址。模型列表返回的其他 URL 不会覆盖用户配置的 Base URL。

## 网络代理

「设置 › 网络代理」可设置 HTTP/SOCKS 代理以连接模型、浏览器与下载服务；本机与局域网通常直连。凭据在手机上加密保存。有关授权、支付与设备接管，继续遵守应用原有安全门控。
