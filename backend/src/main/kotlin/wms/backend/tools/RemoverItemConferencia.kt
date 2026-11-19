package wms.backend.tools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import wms.backend.Database
import wms.backend.erp.SankhyaDbExplorerClient
import wms.backend.erp.SankhyaSpClient
import kotlin.system.exitProcess

/**
 * Manutenção: remove UMA linha de item conferido (TGFCOI2 / entidade DetalhesConferencia) de uma conferência
 * ABERTA, quando o produto da linha não está mais no pedido. Sem isso o `ConferenciaSP.cortar` falha com
 * "O produto ... não consta no documento conferido. Não é possível aumentar sua quantidade."
 * Caso de uso (13/11/2026): pedido 83514, conferência 9065 — Burrata 3569 trocada por 9111 no pedido com a
 * conferência aberta; a recontagem copiou a linha da 3569 da conferência anterior.
 *
 * Travas: conferência com STATUS 'A'; a linha existe e é do produto informado; o produto NÃO está em TGFITE do
 * pedido de origem; nenhuma linha filha (TGFSLT/TGFCBC/TGFCONSER/TGFPCON). Sem --executar só mostra.
 *
 *   java -cp "/tmp/t:/app/lib/[jars]" wms.backend.tools.RemoverItemConferenciaKt negri 9065 7 3569 [--executar]
 */
fun main(args: Array<String>) {
    val executar = "--executar" in args
    val a = args.filterNot { it == "--executar" }
    val tenant = a.getOrNull(0)
    val nuconf = a.getOrNull(1)?.toIntOrNull()
    val seqconf = a.getOrNull(2)?.toIntOrNull()
    val codprod = a.getOrNull(3)?.toIntOrNull()
    if (tenant == null || nuconf == null || seqconf == null || codprod == null) {
        System.err.println("uso: RemoverItemConferenciaKt <tenant> <nuconf> <seqconf> <codprod> [--executar]"); exitProcess(2)
    }
    Database.init()
    val codigo = try {
        runBlocking {
            suspend fun q(sql: String) = SankhyaDbExplorerClient.executarQuery(tenant, sql)
            val conf = q("SELECT NUNOTAORIG, STATUS FROM TGFCON2 WHERE NUCONF = $nuconf").firstOrNull()
                ?: error("conferência $nuconf não existe")
            val nunota = conf["NUNOTAORIG"]?.trim() ?: error("conferência sem NUNOTAORIG")
            if (conf["STATUS"]?.trim() != "A") error("conferência $nuconf está '${conf["STATUS"]}', só mexo em aberta (A)")
            val linha = q("SELECT CODPROD, CODVOL, QTDCONF FROM TGFCOI2 WHERE NUCONF = $nuconf AND SEQCONF = $seqconf").firstOrNull()
                ?: error("linha $nuconf/$seqconf não existe")
            if (linha["CODPROD"]?.trim() != codprod.toString()) error("linha $nuconf/$seqconf é do produto ${linha["CODPROD"]}, não $codprod")
            val noPedido = q("SELECT COUNT(*) QTD FROM TGFITE WHERE NUNOTA = $nunota AND CODPROD = $codprod").first()["QTD"]?.trim()
            if (noPedido != "0") error("produto $codprod ESTÁ no pedido $nunota — não removo")
            var filhos = 0
            for (t in listOf("TGFSLT", "TGFCBC", "TGFCONSER", "TGFPCON")) filhos +=
                q("SELECT COUNT(*) QTD FROM $t WHERE NUCONF = $nuconf AND SEQCONF = $seqconf").first()["QTD"]?.trim()?.toInt() ?: 0
            if (filhos > 0) error("linha tem $filhos registro(s) filho(s) — não removo")
            println("Pedido $nunota, conferência $nuconf (A): linha $seqconf = prod $codprod ${linha["QTDCONF"]} ${linha["CODVOL"]}; fora do pedido; sem filhos.")
            if (!executar) { println("SIMULAÇÃO — nada foi alterado. Rode de novo com --executar."); return@runBlocking }

            val resp = SankhyaSpClient.chamarRaw(
                tenant, "DatasetSP.removeRecord", "mge",
                buildJsonObject {
                    put("entityName", "DetalhesConferencia")
                    putJsonArray("pks") { addJsonObject { put("NUCONF", nuconf.toString()); put("SEQCONF", seqconf.toString()) } }
                },
            )
            println("Resposta: $resp")
            val depois = q("SELECT COUNT(*) QTD FROM TGFCOI2 WHERE NUCONF = $nuconf AND SEQCONF = $seqconf").first()["QTD"]?.trim()
            if (depois != "0") error("a linha continua lá depois do removeRecord")
            println("OK — linha $nuconf/$seqconf removida. Itens restantes:")
            q("SELECT SEQCONF, CODPROD, CODVOL, QTDCONF FROM TGFCOI2 WHERE NUCONF = $nuconf ORDER BY SEQCONF").forEach { println("  $it") }
        }
        0
    } catch (e: Exception) {
        println("ERRO: ${e.message}"); 1
    }
    exitProcess(codigo)
}
