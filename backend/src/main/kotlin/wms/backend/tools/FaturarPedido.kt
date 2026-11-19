package wms.backend.tools

import kotlinx.coroutines.runBlocking
import wms.backend.Database
import wms.backend.erp.SankhyaDbExplorerClient
import wms.backend.separacao.SeparacaoService
import wms.backend.tenancy.TenantRepository
import java.util.UUID
import kotlin.system.exitProcess

/**
 * Manutenção: fatura + confirma UM pedido pelo mesmo caminho do "Fechar OC" (SeparacaoService.faturarPedido —
 * trava por pedido, validações, SelecaoDocumentoSP.faturar, CACSP.confirmarNota, AD_FEEDBACK).
 * Caso de uso (13/11/2026): pedido 83514 depois da correção da conferência 9065.
 *
 *   java -cp "/tmp/t:/app/lib/[jars]" wms.backend.tools.FaturarPedidoKt negri 83514 1101 [--executar]
 */
fun main(args: Array<String>) {
    val executar = "--executar" in args
    val a = args.filterNot { it == "--executar" }
    val slug = a.getOrNull(0)
    val nunota = a.getOrNull(1)?.toLongOrNull()
    val top = a.getOrNull(2)?.toIntOrNull()
    if (slug == null || nunota == null || top == null) {
        System.err.println("uso: FaturarPedidoKt <tenant> <nunota> <codtipoper> [--executar]"); exitProcess(2)
    }
    Database.init()
    val codigo = try {
        runBlocking {
            val tenantId = UUID.fromString(TenantRepository.buscarPorSlug(slug)?.id ?: error("tenant '$slug' não encontrado"))
            val cab = SankhyaDbExplorerClient.executarQuery(
                slug,
                "SELECT C.NUNOTA, C.TIPMOV, C.PENDENTE, C.STATUSNOTA, C.CODPARC, C.VLRNOTA, " +
                    "(SELECT STATUS FROM TGFCON2 WHERE NUCONF = C.NUCONFATUAL) STCONF, " +
                    "(SELECT COUNT(*) FROM TGFVAR V WHERE V.NUNOTAORIG = C.NUNOTA) DESTINOS FROM TGFCAB C WHERE C.NUNOTA = $nunota",
            ).firstOrNull() ?: error("pedido $nunota não encontrado")
            println("Pedido: $cab")
            if (cab["TIPMOV"]?.trim() != "P") error("$nunota não é pedido de venda")
            if (cab["STCONF"]?.trim() != "F") error("conferência não está finalizada (status '${cab["STCONF"]}')")
            if (cab["DESTINOS"]?.trim() != "0") error("pedido já tem nota gerada — não fatura de novo")
            if (!executar) { println("SIMULAÇÃO — nada foi faturado. Rode de novo com --executar."); return@runBlocking }

            println("Faturando $nunota na TOP $top…")
            val (notas, aviso) = SeparacaoService.faturarPedido(slug, tenantId, nunota, top)
            println("Notas geradas: $notas")
            println(if (aviso == null) "OK — nota(s) confirmada(s)." else "ATENÇÃO — gerada(s), mas: $aviso")
            if (notas.isNotEmpty()) SankhyaDbExplorerClient.executarQuery(
                slug,
                "SELECT NUNOTA, NUMNOTA, CODTIPOPER, STATUSNOTA, STATUSNFE, VLRNOTA FROM TGFCAB WHERE NUNOTA IN (${notas.joinToString()})",
            ).forEach { println("  $it") }
        }
        0
    } catch (e: Exception) {
        println("ERRO: ${e.message}"); 1
    }
    exitProcess(codigo)
}
