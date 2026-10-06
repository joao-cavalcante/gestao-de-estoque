package wms.backend.tools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import wms.backend.Database
import wms.backend.erp.SankhyaSpClient
import kotlin.system.exitProcess

/**
 * Diagnóstico SOMENTE LEITURA: chama ParceiroSP.importarDadosParceiroToJson (dados da Receita, FONTE_DADOS=RF)
 * pra um parceiro e imprime a resposta — com as credenciais do tenant, sem tirá-las do servidor. NÃO grava:
 * esse serviço só devolve os dados que a tela nativa usaria pra preencher o cadastro. Usado pra validar o
 * projeto atualiza-parceiros-sankhya. Sem rota HTTP de propósito (mesmo esquema do SankhyaQueryKt):
 *
 *   docker exec wms-backend-prod sh -c 'java -cp "/app/lib/[jars]" wms.backend.tools.ConsultarParceiroReceitaKt negri 811 50743864000171'
 */
fun main(args: Array<String>) {
    val tenant = args.getOrNull(0)
    val codparc = args.getOrNull(1)?.toLongOrNull()
    val cnpj = args.getOrNull(2)?.filter { it.isDigit() }
    if (tenant == null || codparc == null || codparc <= 1 || cnpj == null || cnpj.length != 14) {
        System.err.println("uso: ConsultarParceiroReceitaKt <tenant> <codparc (>1)> <cnpj 14 dígitos>")
        exitProcess(2)
    }
    Database.init()
    val campos = listOf(
        "CODPARC", "NOMEPARC", "RAZAOSOCIAL", "CGC_CPF", "IDENTINSCESTAD", "CEP", "CODEND", "Endereco.NOMEEND", "NUMEND",
        "COMPLEMENTO", "CODBAI", "Bairro.NOMEBAI", "CODCID", "Cidade.AD_UF", "TELEFONE", "SITCADRF", "INDCREDNFE",
        "INDCREDCTE", "DTINIATIV", "DTULTSIT", "DTBAIXA", "REGAPUR",
    )
    val corpo = buildJsonObject {
        putJsonArray("fieldsFilter") {
            addJsonObject { put("FONTE_DADOS", "RF") }
            addJsonObject { put("CGC_CPF", cnpj) }
            addJsonObject { put("CODPARC", codparc) }
        }
        putJsonObject("loadRecordsRequest") {
            put("dataSetID", "05B")
            put("entityName", "Parceiro")
            put("standAlone", true)
            putJsonArray("fields") { campos.forEach { add(it) } }
            put("tryJoinedFields", true)
        }
        putJsonObject("clientEventList") {
            putJsonArray("clientEvent") {
                addJsonObject { put("$", "parceiro.mostra.mensagem.criticaie") }
                addJsonObject { put("$", "br.com.sankhya.mgecore.ie.repetida.transportadora") }
            }
        }
    }
    val resp = try {
        runBlocking { SankhyaSpClient.chamarRaw(tenant, "ParceiroSP.importarDadosParceiroToJson", "mge", corpo, retentar = false) }
    } catch (e: Exception) {
        System.err.println("ERRO: ${e.message}")
        exitProcess(1)
    }
    println(resp.toString())
    exitProcess(0)
}
