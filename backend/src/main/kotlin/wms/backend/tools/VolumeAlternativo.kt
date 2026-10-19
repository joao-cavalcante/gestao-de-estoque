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
 * Manutenção: corrige o fator de um volume alternativo (TGFVOA) já cadastrado, pela entidade VolumeAlternativo
 * (CRUDServiceProvider.saveRecord, chave CODPROD + CODVOL + CONTROLE). Caso de uso (19/10/2026): Pratigel
 * faturado em KG com KG = 1 PT, quando o pacote tem 1,02 KG / 2 KG — QTDNEG fracionado em PT (DECQTD 0)
 * fechava a conferência divergente e lançava pacote a mais no estoque (nota 71101).
 *
 *   docker exec wms-backend-prod sh -c 'java -cp "/app/lib/[todos os jars]" wms.backend.tools.VolumeAlternativoKt negri KG D 1.02 236 237'
 *   ... acrescente --executar pra valer; sem ele só mostra o antes/depois (nada muda).
 *
 * D = padrão = alternativa / fator ; M = padrão = alternativa * fator. Só altera linha existente sem controle.
 */
fun main(args: Array<String>) {
    val executar = "--executar" in args
    val a = args.filterNot { it == "--executar" }
    val tenant = a.getOrNull(0)
    val codvol = a.getOrNull(1)?.uppercase()
    val dm = a.getOrNull(2)?.uppercase()
    val fator = a.getOrNull(3)?.toBigDecimalOrNull()
    val produtos = a.drop(4).mapNotNull { it.toIntOrNull() }.distinct()
    if (tenant == null || codvol.isNullOrBlank() || dm !in setOf("D", "M") || fator == null || fator.signum() <= 0 || produtos.isEmpty()) {
        System.err.println("uso: VolumeAlternativoKt <tenant> <codvol> <D|M> <fator> <codprod> [codprod...] [--executar]")
        exitProcess(2)
    }

    Database.init()
    var falhas = 0
    runBlocking {
        for (codprod in produtos) {
            suspend fun ler() = SankhyaDbExplorerClient.executarQuery(
                tenant,
                "SELECT DIVIDEMULTIPLICA, QUANTIDADE FROM TGFVOA WHERE CODPROD = $codprod AND CODVOL = '$codvol' AND NVL(TRIM(CONTROLE), ' ') = ' '",
            ).firstOrNull()?.let { "${it["DIVIDEMULTIPLICA"]}${it["QUANTIDADE"]}" }
            val antes = ler()
            if (antes == null) {
                println("prod $codprod: sem volume $codvol cadastrado — ignorado")
                falhas++
                continue
            }
            if (!executar) {
                println("prod $codprod $codvol: $antes -> $dm$fator (simulação)")
                continue
            }
            val erro = runCatching {
                SankhyaSpClient.chamarRaw(
                    tenant, "CRUDServiceProvider.saveRecord", "mge",
                    buildJsonObject {
                        putJsonObject("dataSet") {
                            put("rootEntity", "VolumeAlternativo")
                            put("includePresentationFields", "N")
                            putJsonObject("dataRow") {
                                putJsonObject("localFields") {
                                    putJsonObject("DIVIDEMULTIPLICA") { put("\$", dm) }
                                    putJsonObject("QUANTIDADE") { put("\$", fator.toPlainString()) }
                                }
                                putJsonObject("key") {
                                    putJsonObject("CODPROD") { put("\$", codprod.toString()) }
                                    putJsonObject("CODVOL") { put("\$", codvol) }
                                    putJsonObject("CONTROLE") { put("\$", " ") }
                                }
                            }
                            putJsonObject("entity") { putJsonObject("fieldset") { put("list", "CODPROD,CODVOL,CONTROLE,DIVIDEMULTIPLICA,QUANTIDADE") } }
                        }
                    },
                    retentar = false,
                )
            }.exceptionOrNull()
            val depois = ler()
            val ok = depois == "$dm${fator.stripTrailingZeros().toPlainString()}" || depois?.replace(',', '.') == "$dm${fator.stripTrailingZeros().toPlainString()}"
            if (!ok) falhas++
            println("prod $codprod $codvol: $antes -> $depois ${if (ok) "OK" else "FALHOU ${erro?.message ?: ""}"}")
        }
    }
    System.err.println("(${produtos.size} produto(s), $falhas falha(s))")
    exitProcess(if (falhas == 0) 0 else 1)
}
