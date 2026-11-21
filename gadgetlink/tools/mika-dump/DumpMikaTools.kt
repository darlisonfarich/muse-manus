// Despeja o tools/list REAL do servidor mika (McpTaskTools.definitions() com as ferramentas de
// aparelho e de telefone ligadas) como JSON: nome, descrição e inputSchema exatos das ferramentas.
// É a fonte do golden src/test/resources/mika_tools.json, que o CommandCatalogTest compara com o
// catálogo phone.* do despachante. Não executa nenhuma ferramenta: as dependências são proxies
// que falham se chamadas. Rodar com dump_mika_tools.sh.
import com.wangzan101.mika.core.device.DeviceAccess
import com.wangzan101.mika.core.device.DeviceAccessPort
import com.wangzan101.mika.core.device.DeviceAccessStore
import com.wangzan101.mika.core.mcp.DirectPhoneHost
import com.wangzan101.mika.core.mcp.McpTaskTools
import com.wangzan101.mika.core.remote.RemoteTaskService
import com.wangzan101.mika.core.remote.RemoteTasks
import java.lang.reflect.Proxy

inline fun <reified T> never(): T = Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, m, _ ->
    error("dump only: ${m.name} must not be called")
} as T

fun main() {
    val tools = McpTaskTools(
        RemoteTaskService(never<RemoteTasks>()),
        DeviceAccess(never<DeviceAccessPort>(), never<DeviceAccessStore>()),
        never<DirectPhoneHost>(),
        "dump",
    )
    println(tools.definitions().toString())
}
