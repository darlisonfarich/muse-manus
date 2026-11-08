# Remoção de login em contas de modelos

A versão Muse Manus remove os fluxos OAuth para usar assinaturas ChatGPT, xAI ou OpenRouter como modelos de conversa. O aplicativo utiliza somente uma conexão personalizada OpenAI-compatible (Chat Completions), configurada pelo usuário.

**Importante:** autenticação de servidores MCP é independente. O utilitário PKCE do MCP permanece disponível. Nenhum login de assinatura de IA é executado ou retomado ao iniciar o aplicativo.

Veja [modelos](../models.md).
