package wms.backend.tools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import wms.backend.Database
import wms.backend.erp.SankhyaDbExplorerClient
import wms.backend.erp.SankhyaSpClient
import kotlin.system.exitProcess

/**
 * Manutenção: "Marcar como não pendente" (Portal de Vendas) nos itens que sobraram pendentes em pedidos JÁ
 * FATURADOS — resíduo do corte com PROCEDCORTE='I' (09/10/2026, conferidos entre 12:37 e 14:25). Chama o MESMO
 * serviço do botão nativo (CACSP.marcarPedidosComoNaoPendentes — payload capturado da tela, pedido 65417), nada
 * de UPDATE direto. Sem rota HTTP de propósito.
 *
 *   docker exec wms-backend-prod sh -c 'java -cp "/app/lib/[todos os jars]" wms.backend.tools.MarcarNaoPendenteKt negri [--aplicar] 65320 65407'
 *
 * Sem --aplicar só mostra o que faria. Recusa pedido que não tem nota CONFIRMADA (STATUSNOTA 'L') gerada dele.
 */
fun main(args: Array<String>) {
    val tenant = args.getOrNull(0)
    val aplicar = "--aplicar" in args
    val pedidos = args.drop(1).filter { it != "--aplicar" }.mapNotNull { it.toLongOrNull() }.distinct()
    if (tenant == null || pedidos.isEmpty()) {
        System.err.println("uso: MarcarNaoPendenteKt <tenant> [--aplicar] <nunota> [nunota...]")
        exitProcess(2)
    }

    Database.init()
    var falhas = 0
    runBlocking {
        for (nunota in pedidos) {
            val notaConfirmada = SankhyaDbExplorerClient.executarQuery(
                tenant,
                "SELECT MAX(N.NUNOTA) AS NOTA FROM TGFVAR V JOIN TGFCAB N ON N.NUNOTA = V.NUNOTA " +
                    "WHERE V.NUNOTAORIG = $nunota AND N.TIPMOV = 'V' AND N.STATUSNOTA = 'L'",
            ).firstOrNull()?.get("NOTA")?.takeIf { it.isNotBlank() }
            if (notaConfirmada == null) {
                println("Pedido $nunota: sem nota confirmada gerada dele — não mexi")
                falhas++
                continue
            }
            val itens = SankhyaDbExplorerClient.executarQuery(
                tenant,
                "SELECT SEQUENCIA, CODPROD, QTDNEG, QTDENTREGUE FROM TGFITE WHERE NUNOTA = $nunota AND PENDENTE = 'S' ORDER BY SEQUENCIA",
            )
            if (itens.isEmpty()) {
                println("Pedido $nunota: nenhum item pendente")
                continue
            }
            val desc = itens.joinToString { "seq ${it["SEQUENCIA"]} prod ${it["CODPROD"]} ${it["QTDNEG"]}/${it["QTDENTREGUE"]}" }
            if (!aplicar) {
                println("Pedido $nunota (nota $notaConfirmada): marcaria não pendente -> $desc")
                continue
            }
            val erro = runCatching {
                SankhyaSpClient.chamarRaw(
                    tenant, "CACSP.marcarPedidosComoNaoPendentes", "mgecom",
                    buildJsonObject {
                        putJsonObject("nuNotas") { putJsonArray("nuNota") { add(buildJsonObject { put("\$", nunota) }) } }
                        putJsonObject("clientEventList") {
                            putJsonArray("clientEvent") {
                                listOf(
                                    "br.com.sankhya.actionbutton.clientconfirm",
                                    "br.com.sankhya.mgecom.msg.nao.possui.itens.pendentes",
                                    "br.com.sankhya.comercial.recalcula.pis.cofins",
                                    "br.com.sankhya.financeiro.alert.mudanca.titulo.baixa",
                                ).forEach { e -> add(buildJsonObject { put("\$", e) }) }
                            }
                        }
                    },
                    retentar = false,
                )
            }.exceptionOrNull()
            val depois = SankhyaDbExplorerClient.executarQuery(
                tenant,
                "SELECT (SELECT PENDENTE FROM TGFCAB WHERE NUNOTA = $nunota) AS CAB, " +
                    "(SELECT COUNT(*) FROM TGFITE WHERE NUNOTA = $nunota AND PENDENTE = 'S') AS ITENS FROM DUAL",
            ).firstOrNull()
            val ok = depois?.get("ITENS")?.trim() == "0"
            if (!ok) falhas++
            println(
                "Pedido $nunota: $desc -> itens pendentes ${depois?.get("ITENS")}, cabeçalho PENDENTE=${depois?.get("CAB")} " +
                    if (ok) "OK" else "FALHOU ${erro?.message ?: ""}",
            )
        }
    }
    System.err.println("(${pedidos.size} pedido(s), $falhas falha(s)${if (aplicar) "" else ", simulação"})")
    exitProcess(if (falhas == 0) 0 else 1)
}
