package wms.backend.tools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import wms.backend.Database
import wms.backend.erp.SankhyaDbExplorerClient
import wms.backend.erp.SankhyaSpClient
import kotlin.system.exitProcess

/**
 * Atualização de parceiro pela Receita (FONTE_DADOS=RF) — validação do projeto atualiza-parceiros-sankhya,
 * com as credenciais do tenant, sem tirá-las do servidor. Sem rota HTTP (mesmo esquema do SankhyaQueryKt).
 *
 *   ... ConsultarParceiroReceitaKt negri 811 50743864000171            → só consulta e mostra o que mudaria
 *   ... ConsultarParceiroReceitaKt negri 811 50743864000171 --gravar   → grava SÓ os campos que mudaram
 *
 * Travas (incidente 06/10/2026: um DatasetSP.save com pk CODPARC=1 sobrescreveu o parceiro 1 com outro CNPJ):
 *  - codparc > 1; CNPJ do cadastro (lido na hora) == CNPJ informado == CNPJ devolvido pela Receita;
 *  - gravação é ALTERAÇÃO do próprio codparc (pk), só dos campos alterados; vazio da Receita não apaga.
 */
private val CAMPOS = listOf(
    "CODPARC", "NOMEPARC", "RAZAOSOCIAL", "CGC_CPF", "IDENTINSCESTAD", "CEP", "CODEND", "Endereco.NOMEEND", "NUMEND",
    "COMPLEMENTO", "CODBAI", "Bairro.NOMEBAI", "CODCID", "Cidade.AD_UF", "TELEFONE", "SITCADRF", "INDCREDNFE",
    "INDCREDCTE", "DTINIATIV", "DTULTSIT", "DTBAIXA", "REGAPUR",
)

/** Campos que podem ser gravados (colunas reais de TGFPAR — os "X.Y" são só descrição). */
private val GRAVAVEIS = listOf(
    "NOMEPARC", "RAZAOSOCIAL", "IDENTINSCESTAD", "CEP", "CODEND", "NUMEND", "COMPLEMENTO", "CODBAI", "CODCID",
    "TELEFONE", "SITCADRF", "INDCREDNFE", "INDCREDCTE", "DTINIATIV", "DTULTSIT", "DTBAIXA", "REGAPUR",
)

private fun digitos(s: String?) = s.orEmpty().filter { it.isDigit() }

private fun normalizar(campo: String, v: String?): String {
    val s = v.orEmpty().trim()
    return when {
        campo.startsWith("DT") -> digitos(s).take(8)
        campo in setOf("CEP", "TELEFONE", "IDENTINSCESTAD", "CODEND", "CODBAI", "CODCID") -> digitos(s).trimStart('0')
        else -> s.uppercase().replace(Regex("\\s+"), " ")
    }
}

private val EVENTOS = buildJsonObject {
    putJsonArray("clientEvent") {
        addJsonObject { put("$", "parceiro.mostra.mensagem.criticaie") }
        addJsonObject { put("$", "br.com.sankhya.mgecore.ie.repetida.transportadora") }
    }
}

fun main(args: Array<String>) {
    val tenant = args.getOrNull(0)
    val codparc = args.getOrNull(1)?.toLongOrNull()
    val cnpj = args.getOrNull(2)?.filter { it.isDigit() }
    val gravar = args.getOrNull(3) == "--gravar"
    if (tenant == null || codparc == null || codparc <= 1 || cnpj == null || cnpj.length != 14) {
        System.err.println("uso: ConsultarParceiroReceitaKt <tenant> <codparc (>1)> <cnpj 14 dígitos> [--gravar]")
        exitProcess(2)
    }
    Database.init()
    try {
        runBlocking {
            // 1. Cadastro atual + trava de CNPJ
            val colunas = (listOf("CODPARC", "CGC_CPF") + GRAVAVEIS).joinToString(",")
            val atual = SankhyaDbExplorerClient.executarQuery(tenant, "SELECT $colunas FROM TGFPAR WHERE CODPARC = $codparc").firstOrNull()
                ?: error("parceiro $codparc não encontrado")
            if (digitos(atual["CGC_CPF"]) != cnpj) error("CNPJ do parceiro $codparc é ${atual["CGC_CPF"]}, não $cnpj — nada feito")

            // 2. Receita (resposta: result = [[valores na ordem de CAMPOS]])
            val corpo = buildJsonObject {
                putJsonArray("fieldsFilter") {
                    addJsonObject { put("FONTE_DADOS", "RF") }
                    addJsonObject { put("CGC_CPF", cnpj) }
                    addJsonObject { put("CODPARC", codparc) }
                }
                putJsonObject("loadRecordsRequest") {
                    put("dataSetID", "05B"); put("entityName", "Parceiro"); put("standAlone", true)
                    putJsonArray("fields") { CAMPOS.forEach { add(it) } }
                    put("tryJoinedFields", true)
                }
                put("clientEventList", EVENTOS)
            }
            val resp = SankhyaSpClient.chamarRaw(tenant, "ParceiroSP.importarDadosParceiroToJson", "mge", corpo, retentar = false)
            val linha = ((resp["result"] as? JsonArray)?.firstOrNull() as? JsonArray) ?: error("resposta sem result: $resp")
            val receita = CAMPOS.mapIndexed { i, c -> c to (linha.getOrNull(i) as? JsonPrimitive)?.contentOrNull }.toMap()
            if (digitos(receita["CGC_CPF"]).isNotEmpty() && digitos(receita["CGC_CPF"]) != cnpj) {
                error("a Receita devolveu outro CNPJ (${receita["CGC_CPF"]}) — nada feito")
            }

            // 3. Diferenças (vazio da Receita não apaga)
            val diff = GRAVAVEIS.mapNotNull { c ->
                val novo = receita[c]
                if (novo.isNullOrBlank()) return@mapNotNull null
                if (normalizar(c, novo) == normalizar(c, atual[c])) null else Triple(c, atual[c], novo)
            }
            println("Parceiro $codparc ($cnpj) — ${diff.size} campo(s) diferente(s) da Receita:")
            diff.forEach { (c, a, n) -> println("  $c: '${a ?: ""}' -> '$n'") }
            if (diff.isEmpty() || !gravar) {
                if (!gravar) println("(só consulta — use --gravar pra aplicar)")
                return@runBlocking
            }

            // 4. Gravação: ALTERAÇÃO do próprio codparc, só os campos alterados
            val fields = listOf("CODPARC") + diff.map { it.first }
            val save = buildJsonObject {
                put("dataSetID", "00X"); put("entityName", "Parceiro"); put("standAlone", false)
                putJsonArray("fields") { fields.forEach { add(it) } }
                putJsonArray("records") {
                    addJsonObject {
                        putJsonObject("pk") { put("CODPARC", codparc.toString()) }
                        putJsonObject("values") { diff.forEachIndexed { i, d -> put((i + 1).toString(), d.third) } }
                    }
                }
                put("crudListener", "br.com.sankhya.modelcore.crudlisteners.ParceiroCrudListener")
                put("ignoreListenerMethods", "")
                put("clientEventList", EVENTOS)
            }
            SankhyaSpClient.chamarRaw(tenant, "DatasetSP.save", "mge", save, retentar = false)

            // 5. Confere o resultado
            val depois = SankhyaDbExplorerClient.executarQuery(tenant, "SELECT $colunas FROM TGFPAR WHERE CODPARC = $codparc").first()
            println("GRAVADO. Depois:")
            diff.forEach { (c, _, n) -> println("  $c = '${depois[c] ?: ""}' (esperado '$n')") }
            println("  CGC_CPF = '${depois["CGC_CPF"]}' | NOMEPARC = '${depois["NOMEPARC"]}'")
        }
    } catch (e: Exception) {
        System.err.println("ERRO: ${e.message}")
        exitProcess(1)
    }
    exitProcess(0)
}
