package wms.backend.tools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import wms.backend.Database
import wms.backend.erp.SankhyaDbExplorerClient
import wms.backend.erp.SankhyaSpClient
import kotlin.system.exitProcess

/**
 * Manutenção: abre ('A') ou fecha ('F') Ordens de Carga no Sankhya (TGFORD.SITUACAO), pela entidade OrdemCarga
 * (CRUDServiceProvider.saveRecord, chave CODEMP + ORDEMCARGA) — mesmo caminho do "Fechar OC" da fila
 * (FechamentoOcService). Sem rota HTTP de propósito: só quem tem acesso ao container usa.
 *
 *   docker exec wms-backend-prod sh -c 'java -cp "/app/lib/[todos os jars]" wms.backend.tools.SituacaoOrdemCargaKt negri F 235 236 237'
 *
 * Imprime uma linha por OC (antes → depois) e sai com 1 se alguma não ficou na situação pedida.
 */
fun main(args: Array<String>) {
    val tenant = args.getOrNull(0)
    val situacao = args.getOrNull(1)?.uppercase()
    val ocs = args.drop(2).mapNotNull { it.toLongOrNull() }.distinct()
    if (tenant == null || situacao !in setOf("A", "F") || ocs.isEmpty()) {
        System.err.println("uso: SituacaoOrdemCargaKt <tenant> <A|F> <oc> [oc...]")
        exitProcess(2)
    }

    Database.init()
    var falhas = 0
    runBlocking {
        for (oc in ocs) {
            val linhas = SankhyaDbExplorerClient.executarQuery(tenant, "SELECT CODEMP, SITUACAO FROM TGFORD WHERE ORDEMCARGA = $oc")
            if (linhas.isEmpty()) {
                println("OC $oc: não existe no Sankhya")
                falhas++
                continue
            }
            val antes = linhas.joinToString { "${it["CODEMP"]}:${it["SITUACAO"]}" }
            val erro = runCatching {
                linhas.filter { it["SITUACAO"]?.trim() != situacao }.forEach { l ->
                    SankhyaSpClient.chamarRaw(
                        tenant, "CRUDServiceProvider.saveRecord", "mge",
                        buildJsonObject {
                            putJsonObject("dataSet") {
                                put("rootEntity", "OrdemCarga")
                                put("includePresentationFields", "N")
                                putJsonObject("dataRow") {
                                    putJsonObject("localFields") { putJsonObject("SITUACAO") { put("\$", situacao) } }
                                    putJsonObject("key") {
                                        putJsonObject("CODEMP") { put("\$", l["CODEMP"]!!.trim()) }
                                        putJsonObject("ORDEMCARGA") { put("\$", oc.toString()) }
                                    }
                                }
                                putJsonObject("entity") { putJsonObject("fieldset") { put("list", "CODEMP,ORDEMCARGA,SITUACAO") } }
                            }
                        },
                        retentar = false,
                    )
                }
            }.exceptionOrNull()
            val depois = SankhyaDbExplorerClient.executarQuery(tenant, "SELECT CODEMP, SITUACAO FROM TGFORD WHERE ORDEMCARGA = $oc")
            val ok = depois.all { it["SITUACAO"]?.trim() == situacao }
            if (!ok) falhas++
            println("OC $oc: $antes -> ${depois.joinToString { "${it["CODEMP"]}:${it["SITUACAO"]}" }} ${if (ok) "OK" else "FALHOU ${erro?.message ?: ""}"}")
        }
    }
    System.err.println("(${ocs.size} OC(s), $falhas falha(s))")
    exitProcess(if (falhas == 0) 0 else 1)
}
