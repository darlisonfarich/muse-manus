# Model configuration — Muse Manus

[简体中文](models.md) | English

Muse Manus supports **Custom OpenAI-compatible (Chat Completions) endpoints only**. There are no account logins for ChatGPT, xAI or OpenRouter, no third-party provider presets, and no Anthropic Messages, Gemini GenerateContent or OpenAI Responses model adapters.

In **Settings › Models**, choose Custom, enter a trusted server Base URL and model ID, then an API key if required. Use **Test and save** to verify the model can use tools. Choose a main model, with optional vision and lightweight models. If a listed model supports a different protocol only, choose an OpenAI-compatible model or your own trusted compatibility gateway. The app never silently switches to another adapter or host.

## Migration of saved providers

On upgrade, unsupported protocol kinds and OAuth account connections are removed from local provider settings without crashing startup. Custom OpenAI-compatible configurations are retained. Old credentials for removed providers are purged; the main-model selection might need to be configured again.

## Context, images, and reasoning

The main model needs tool-calling support; a vision model needs image support. Context window, compaction threshold and read timeout are configured per model. Reasoning effort is available only if a compatible provider explicitly reports supported levels.

## Local servers and proxy

For a model running on another device on your LAN, enter an OpenAI-compatible URL such as `http://192.168.1.10:11434/v1`. Prefer HTTPS for public servers. The remote model list cannot override the configured Base URL. Settings also offer a network proxy for supported remote traffic, while LAN addresses usually connect directly.

## MUSE integration

The Android model option does not replace the MUSE agent's own computer, tools or runtime. MCP and its independent authorization controls remain available to MUSE.
