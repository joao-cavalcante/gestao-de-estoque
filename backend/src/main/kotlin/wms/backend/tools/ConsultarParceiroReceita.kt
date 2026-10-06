package wms.backend.tools

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
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
 * Atualização de parceiro pela Receita (FONTE_DADOS=RF) — com as credenciais do tenant, sem tirá-las do
 * servidor. Sem rota HTTP (mesmo esquema do SankhyaQueryKt).
 *
 *   ... ConsultarParceiroReceitaKt negri 811 50743864000171            → um parceiro: só mostra o que mudaria
 *   ... ConsultarParceiroReceitaKt negri 811 50743864000171 --gravar   → um parceiro: grava só o que mudou
 *   ... ConsultarParceiroReceitaKt negri --sem-endereco [--gravar]     → lote: parceiros com CNPJ sem endereço
 *
 * Saída do lote: uma linha por parceiro, separada por ';' (CODPARC;CNPJ;SITUACAO;CAMPO=antigo->novo|...),
 * que serve de backup pra desfazer.
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

/** Lote: só endereço, telefone e situação — a Receita põe o nome fantasia no NOMEPARC, não serve em massa. */
private val GRAVAVEIS_LOTE = listOf("CEP", "CODEND", "NUMEND", "COMPLEMENTO", "CODBAI", "CODCID", "TELEFONE", "SITCADRF")

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

private data class Resultado(val situacao: String, val diff: List<Triple<String, String?, String>>, val obs: String = "")

/** Um parceiro: lê, consulta a Receita, compara e (se [gravar]) grava só o que mudou. Lança erro nas travas. */
private suspend fun processar(
    tenant: String, codparc: Long, cnpj: String, gravar: Boolean, gravaveis: List<String> = GRAVAVEIS,
): Resultado {
    require(codparc > 1) { "codparc $codparc bloqueado" }
    val colunas = (listOf("CODPARC", "CGC_CPF") + GRAVAVEIS).joinToString(",")
    val atual = SankhyaDbExplorerClient.executarQuery(tenant, "SELECT $colunas FROM TGFPAR WHERE CODPARC = $codparc").firstOrNull()
        ?: error("parceiro $codparc não encontrado")
    if (digitos(atual["CGC_CPF"]) != cnpj) error("CNPJ do cadastro é ${atual["CGC_CPF"]}, não $cnpj")

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
    val linha = ((resp["result"] as? JsonArray)?.firstOrNull() as? JsonArray) ?: error("Receita sem resultado")
    val receita = CAMPOS.mapIndexed { i, c -> c to (linha.getOrNull(i) as? JsonPrimitive)?.contentOrNull }.toMap()
    if (digitos(receita["CGC_CPF"]).isNotEmpty() && digitos(receita["CGC_CPF"]) != cnpj) {
        error("a Receita devolveu outro CNPJ (${receita["CGC_CPF"]})")
    }

    val diff = gravaveis.mapNotNull { c ->
        val novo = receita[c]
        if (novo.isNullOrBlank()) return@mapNotNull null
        // no lote, telefone só preenche vazio/zerado — o da Receita costuma ser do contador
        if (c == "TELEFONE" && gravaveis === GRAVAVEIS_LOTE && digitos(atual[c]).trim('0').isNotEmpty()) return@mapNotNull null
        if (normalizar(c, novo) == normalizar(c, atual[c])) null else Triple(c, atual[c], novo)
    }
    if (diff.isEmpty()) return Resultado("SEM_MUDANCA", diff)
    if (!gravar) return Resultado("PREVIA", diff)

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

    val depois = SankhyaDbExplorerClient.executarQuery(tenant, "SELECT $colunas FROM TGFPAR WHERE CODPARC = $codparc").first()
    val divergentes = diff.filter { (c, _, n) -> normalizar(c, depois[c]) != normalizar(c, n) }.map { it.first }
    return Resultado(if (divergentes.isEmpty()) "GRAVADO" else "GRAVADO_PARCIAL", diff, if (divergentes.isEmpty()) "" else "nao conferem: $divergentes")
}

private fun formatar(diff: List<Triple<String, String?, String>>) =
    diff.joinToString("|") { (c, a, n) -> "$c=${a.orEmpty()}->$n" }

fun main(args: Array<String>) {
    val tenant = args.getOrNull(0)
    if (tenant == null) {
        System.err.println("uso: ConsultarParceiroReceitaKt <tenant> (<codparc> <cnpj> | --sem-endereco) [--gravar]")
        exitProcess(2)
    }
    val gravar = "--gravar" in args
    Database.init()

    // ─── Lote: parceiros com CNPJ e sem endereço (CODEND 0/nulo) ───
    if (args.getOrNull(1) == "--sem-endereco") {
        // --intervalo=<seg> entre consultas (a Receita limita a taxa); --limite=<n> pra testar com poucos
        val intervalo = args.firstNotNullOfOrNull { it.removePrefix("--intervalo=").takeIf { v -> v != it }?.toLongOrNull() } ?: 10
        val limite = args.firstNotNullOfOrNull { it.removePrefix("--limite=").takeIf { v -> v != it }?.toIntOrNull() }
        val todos = runBlocking {
            SankhyaDbExplorerClient.executarQuery(
                tenant,
                "SELECT CODPARC, CGC_CPF FROM TGFPAR WHERE (CODEND = 0 OR CODEND IS NULL) AND CODPARC > 1 " +
                    "AND LENGTH(REGEXP_REPLACE(CGC_CPF, '[^0-9]', '')) = 14 ORDER BY CODPARC",
            )
        }.mapNotNull { r -> r["CODPARC"]?.toBigDecimalOrNull()?.toLong()?.let { it to digitos(r["CGC_CPF"]) } }
        val alvos = if (limite != null) todos.take(limite) else todos
        System.err.println("${alvos.size} parceiro(s), ${intervalo}s entre consultas — ${if (gravar) "GRAVANDO" else "só prévia"}")
        println("CODPARC;CNPJ;SITUACAO;ALTERACOES;OBS")
        val contagem = mutableMapOf<String, Int>()
        runBlocking {
            for ((codparc, cnpj) in alvos) {
                val linha = try {
                    val r = processar(tenant, codparc, cnpj, gravar, GRAVAVEIS_LOTE)
                    contagem.merge(r.situacao, 1, Int::plus)
                    "$codparc;$cnpj;${r.situacao};${formatar(r.diff)};${r.obs}"
                } catch (e: Exception) {
                    contagem.merge("ERRO", 1, Int::plus)
                    "$codparc;$cnpj;ERRO;;${e.message?.replace(";", ",")?.replace("\n", " ")}"
                }
                println(linha)
                System.out.flush()
                delay(intervalo * 1000) // a Receita só responde poucas consultas por minuto
            }
        }
        System.err.println("Resumo: $contagem")
        exitProcess(0)
    }

    // ─── Um parceiro ───
    val codparc = args.getOrNull(1)?.toLongOrNull()
    val cnpj = args.getOrNull(2)?.filter { it.isDigit() }
    if (codparc == null || codparc <= 1 || cnpj == null || cnpj.length != 14) {
        System.err.println("uso: ConsultarParceiroReceitaKt <tenant> <codparc (>1)> <cnpj 14 dígitos> [--gravar]")
        exitProcess(2)
    }
    try {
        val r = runBlocking { processar(tenant, codparc, cnpj, gravar) }
        println("Parceiro $codparc ($cnpj) — ${r.situacao}: ${r.diff.size} campo(s)")
        r.diff.forEach { (c, a, n) -> println("  $c: '${a.orEmpty()}' -> '$n'") }
        if (r.obs.isNotEmpty()) println("  ${r.obs}")
        if (!gravar && r.diff.isNotEmpty()) println("(só consulta — use --gravar pra aplicar)")
    } catch (e: Exception) {
        System.err.println("ERRO: ${e.message}")
        exitProcess(1)
    }
    exitProcess(0)
}
