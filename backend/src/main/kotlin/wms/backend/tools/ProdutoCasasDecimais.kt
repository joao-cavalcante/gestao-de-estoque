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
 * Manutenção: grava as casas decimais da quantidade (TGFPRO.DECQTD) pela entidade Produto
 * (CRUDServiceProvider.saveRecord, chave CODPROD). Caso de uso (20/10/2026): produtos com unidade padrão KG
 * ou volume alternativo KG (TGFVOA) estavam com DECQTD 0/3 e o Sankhya arredondava a quantidade da entrada
 * em KG — o usuário pediu 4 casas pra todos. A TGFVOA não tem casas decimais da quantidade; a conversão
 * usa DECQTD do produto (e TSIPAR DECVLRVOLALT, já 9).
 *
 *   docker exec wms-backend-prod sh -c 'java -cp "/app/lib/[todos os jars]" wms.backend.tools.ProdutoCasasDecimaisKt negri 4 --kg'
 *   ... ou códigos explícitos no lugar de --kg; acrescente --executar pra valer (sem ele só mostra o antes/depois).
 *
 * --kg = todo produto com CODVOL 'KG' ou com volume alternativo 'KG', cujo DECQTD ainda é diferente do pedido.
 */
fun main(args: Array<String>) {
    val executar = "--executar" in args
    val kg = "--kg" in args
    val a = args.filterNot { it == "--executar" || it == "--kg" }
    val tenant = a.getOrNull(0)
    val decimais = a.getOrNull(1)?.toIntOrNull()
    val informados = a.drop(2).mapNotNull { it.toIntOrNull() }.distinct()
    if (tenant == null || decimais == null || decimais !in 0..9 || (informados.isEmpty() && !kg)) {
        System.err.println("uso: ProdutoCasasDecimaisKt <tenant> <decimais> (--kg | <codprod> [codprod...]) [--executar]")
        exitProcess(2)
    }

    Database.init()
    var falhas = 0
    var total = 0
    runBlocking {
        val produtos = informados + if (kg) {
            SankhyaDbExplorerClient.executarQuery(
                tenant,
                "SELECT P.CODPROD FROM TGFPRO P " +
                    "WHERE (P.CODVOL = 'KG' OR EXISTS (SELECT 1 FROM TGFVOA V WHERE V.CODPROD = P.CODPROD AND V.CODVOL = 'KG')) " +
                    "AND NVL(P.DECQTD, -1) <> $decimais ORDER BY P.CODPROD",
            ).mapNotNull { it["CODPROD"]?.toIntOrNull() }
        } else {
            emptyList()
        }
        total = produtos.distinct().size
        for (codprod in produtos.distinct()) {
            suspend fun ler() = SankhyaDbExplorerClient.executarQuery(
                tenant, "SELECT DECQTD, CODVOL, DESCRPROD FROM TGFPRO WHERE CODPROD = $codprod",
            ).firstOrNull()
            val antes = ler()
            if (antes == null) {
                println("prod $codprod: não encontrado — ignorado")
                falhas++
                continue
            }
            val rotulo = "prod $codprod ${antes["CODVOL"]} ${antes["DESCRPROD"]}"
            if (!executar) {
                println("$rotulo: DECQTD ${antes["DECQTD"]} -> $decimais (simulação)")
                continue
            }
            val erro = runCatching {
                SankhyaSpClient.chamarRaw(
                    tenant, "CRUDServiceProvider.saveRecord", "mge",
                    buildJsonObject {
                        putJsonObject("dataSet") {
                            put("rootEntity", "Produto")
                            put("includePresentationFields", "N")
                            putJsonObject("dataRow") {
                                putJsonObject("localFields") {
                                    putJsonObject("DECQTD") { put("\$", decimais.toString()) }
                                }
                                putJsonObject("key") {
                                    putJsonObject("CODPROD") { put("\$", codprod.toString()) }
                                }
                            }
                            putJsonObject("entity") { putJsonObject("fieldset") { put("list", "CODPROD,DECQTD") } }
                        }
                    },
                    retentar = false,
                )
            }.exceptionOrNull()
            val depois = ler()?.get("DECQTD")
            val ok = depois == decimais.toString()
            if (!ok) falhas++
            println("$rotulo: DECQTD ${antes["DECQTD"]} -> $depois ${if (ok) "OK" else "FALHOU ${erro?.message ?: ""}"}")
        }
    }
    System.err.println("($total produto(s), $falhas falha(s))")
    exitProcess(if (falhas == 0) 0 else 1)
}
