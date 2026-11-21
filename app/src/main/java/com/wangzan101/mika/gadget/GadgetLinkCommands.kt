package com.wangzan101.mika.gadget

import android.content.Context
import com.darlisonfarich.gadgetlink.commands.CommandCatalog
import com.darlisonfarich.gadgetlink.commands.CommandDispatcher
import com.darlisonfarich.gadgetlink.commands.CommandSpec
import com.darlisonfarich.gadgetlink.commands.InvokeAuditLog
import com.darlisonfarich.gadgetlink.commands.LocalGate
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.settings.FeatureFlags
import java.io.File

/**
 * Os comandos do Gadget Link no app (especificação "Gadget Link sem Tailscale", Etapas 0 e 1): o catálogo
 * anunciado no link.register, o despachante dos link.invoke e o portão local que ele consulta.
 *
 * O portão é o MESMO estado que já manda no app — nada novo para o dono aprender, nada contornado:
 *  - "Sair totalmente" do Mika (a parada do app, [FeatureFlags.quitRequested]) nega tudo;
 *  - comandos phone.* exigem o "acesso remoto" das configurações de computador ligado,
 *    e os de escrita exigem também a escrita direta liberada — os mesmos interruptores que o servidor
 *    mika consulta ([com.wangzan101.mika.settings.McpSettings]); os demais portões (confirmações,
 *    superfícies proibidas, tela principal em uso) ficam no executor de cada comando quando ele existir.
 * linktest.* não tocam no aparelho: só a parada vale para eles.
 */
object GadgetLinkCommands {
    private const val PREFS = "gadget_link"
    private const val KEY_STUBS = "link_measure_stubs"

    /** A medição da Etapa 1 (anunciar os stubs phone.* restantes). Desligada por padrão; vale na próxima conexão. */
    fun measureStubs(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_STUBS, false)

    fun setMeasureStubs(context: Context, on: Boolean) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_STUBS, on).commit()
    }

    fun auditFile(context: Context) = File(context.applicationContext.filesDir, "gadgetlink/link-invoke-audit.log")

    fun catalog(context: Context): CommandCatalog {
        val mcp = (context.applicationContext as MikaApp).mcp
        return CommandCatalog.museManus(
            includeStubs = measureStubs(context),
            readCommands = GadgetPhoneReadCommands.specs(mcp::gadgetReadScope, mcp::readPhoneForGadget),
        )
    }

    fun gate(context: Context): LocalGate {
        val app = context.applicationContext
        return LocalGate { command: CommandSpec ->
            when {
                FeatureFlags.get(app).quitRequested() -> "app stopped (quit requested)"
                command.name.startsWith("phone.") -> {
                    val settings = (app as MikaApp).mcp.settings
                    when {
                        !settings.enabled.value -> "remote_access_disabled"
                        command.writes && !settings.directWritesEnabled.value -> "write_disabled"
                        else -> null
                    }
                }
                else -> null
            }
        }
    }

    /** A auditoria roda no executor próprio do despachante (fila limitada): disco lento ou falhando nunca segura um comando. */
    fun dispatcher(context: Context, catalog: CommandCatalog): CommandDispatcher {
        val audit = InvokeAuditLog(auditFile(context))
        return CommandDispatcher(catalog, gate(context), audit = { entry ->
            audit.append(entry)
            GadgetLink.log("link: invoke ${entry.command} ${entry.outcome} ${entry.durationMs}ms" + if (entry.deduplicated) " (dedup)" else "")
        })
    }
}
