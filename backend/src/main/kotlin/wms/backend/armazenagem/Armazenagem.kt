package wms.backend.armazenagem

import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import wms.backend.auth.ClaimsToken
import wms.backend.auth.exigirAuth
import wms.backend.erp.SankhyaSpClient
import wms.backend.permissoes.EmpresasAcesso
import wms.backend.tenancy.TenantRepository
import wms.backend.usuarios.UsuariosRepository

/**
 * Armazenagem no coletor (endereçamento, etapa 3b — 27/11/2026). As tarefas, o saldo por endereço e os movimentos
 * ficam no Sankhya (addon TOR, `EnderecamentoSP`); a Torre só repassa, com as empresas do usuário e o CODUSU dele
 * (usuário do WMS vinculado ao Sankhya). Guardar é UMA chamada atômica no addon (doca −qtd, endereço +qtd).
 */
@Serializable
data class TarefaArmazenagemDto(
    val nutar: Long,
    val situacao: String,
    val nunota: Long?,
    val numnota: Long?,
    val fornecedor: String?,
    val codemp: Int?,
    val codlocal: Int?,
    val codprod: Int,
    val descricao: String,
    val referencia: String?,
    val controle: String?,
    val unidade: String?,
    val qtd: Double,
    val qtdFeita: Double,
    val origem: String?,
    val sugerido: String?,
    val tipoSugerido: String?,
    val codusu: Int?,
    val criada: String?,
    val barras: List<String>,
)

@Serializable
data class ArmazenarRequest(val endereco: String, val qtd: Double, val motivo: String? = null)

class ArmazenagemException(msg: String) : Exception(msg)

object Armazenagem {
    private const val MODULO_ADDON = "torre"

    private fun disponivel(tenantId: java.util.UUID): Boolean =
        runCatching { TenantRepository.parametros(tenantId).ajustePesoCompra.viaAddon }.getOrDefault(false)

    private fun slug(claims: ClaimsToken): String {
        if (!disponivel(claims.tenantId)) throw ArmazenagemException("A armazenagem exige o addon TOR ligado para este cliente.")
        return TenantRepository.buscarPorId(claims.tenantId)?.slug ?: throw ArmazenagemException("Cliente não encontrado.")
    }

    private fun codusu(claims: ClaimsToken): Int =
        UsuariosRepository.codusuSankhya(claims.tenantId, claims.userId)
            ?: throw ArmazenagemException("Seu usuário não está vinculado a um usuário do Sankhya (cadastro de usuários da Torre).")

    private fun corpo(vararg campos: Pair<String, String?>): JsonObject = buildJsonObject {
        putJsonObject("request") { campos.forEach { (k, v) -> if (v != null) put(k, v) } }
    }

    private fun body(r: JsonObject): JsonObject = (r["body"] as? JsonObject) ?: r

    suspend fun tarefas(claims: ClaimsToken): List<TarefaArmazenagemDto> {
        val s = slug(claims)
        val empresas = EmpresasAcesso.permitidas(claims)
        if (empresas != null && empresas.isEmpty()) return emptyList()
        val r = SankhyaSpClient.chamarRaw(s, "EnderecamentoSP.tarefas", MODULO_ADDON, corpo("EMPRESAS" to empresas?.sorted()?.joinToString(",")))
        return objetos(body(r)["TAREFAS"]).map(::tarefa)
    }

    /** Usuário com restrição de empresa só mexe em tarefa das empresas dele — conferido ANTES de gravar. */
    private suspend fun garantirAcesso(claims: ClaimsToken, nutar: Long) {
        if (EmpresasAcesso.permitidas(claims) == null) return
        if (tarefas(claims).none { it.nutar == nutar }) throw ArmazenagemException("Tarefa $nutar não está aberta para as empresas do seu usuário.")
    }

    suspend fun iniciar(claims: ClaimsToken, nutar: Long): TarefaArmazenagemDto {
        val s = slug(claims)
        garantirAcesso(claims, nutar)
        val r = SankhyaSpClient.chamarRaw(s, "EnderecamentoSP.iniciar", MODULO_ADDON,
            corpo("NUTAR" to nutar.toString(), "CODUSU" to codusu(claims).toString()), retentar = false)
        return tarefaDaResposta(r)
    }

    /** Não repete sozinho: guardar move saldo. Resposta perdida → o coletor relê a tarefa (QTDFEITA diz o que entrou). */
    suspend fun armazenar(claims: ClaimsToken, nutar: Long, req: ArmazenarRequest): TarefaArmazenagemDto {
        val s = slug(claims)
        if (req.endereco.isBlank()) throw ArmazenagemException("Bipe a etiqueta do endereço.")
        if (req.qtd <= 0) throw ArmazenagemException("Informe a quantidade guardada.")
        garantirAcesso(claims, nutar)
        val r = SankhyaSpClient.chamarRaw(s, "EnderecamentoSP.armazenar", MODULO_ADDON, corpo(
            "NUTAR" to nutar.toString(),
            "ENDERECO" to req.endereco.trim().uppercase(),
            "QTD" to java.math.BigDecimal.valueOf(req.qtd).stripTrailingZeros().toPlainString(),
            "MOTIVO" to req.motivo?.trim()?.takeIf { it.isNotEmpty() },
            "CODUSU" to codusu(claims).toString(),
        ), retentar = false)
        return tarefaDaResposta(r)
    }

    private fun tarefaDaResposta(r: JsonObject): TarefaArmazenagemDto =
        tarefa(objetos(body(r)["TAREFA"]).firstOrNull() ?: throw ArmazenagemException("Tarefa não encontrada no Sankhya."))

    // ─── Reserva do job do addon (27/11/2026) ────────────────────────────────────────────────────────────────────
    // O EnderecamentoJob do addon não foi visto rodando na sandbox (o AjustePesoJob também nunca foi — a Torre sempre
    // gerou antes). A Torre pede as tarefas no ciclo e logo depois de aprovar; o addon é idempotente (ENDSIT 'GE').

    private fun sqlPendentes(comCorte: String) = "SELECT NUNOTA, NUCONF FROM (SELECT R.NUNOTA, R.NUCONF FROM TORREC R " +
        "JOIN TGFCAB C ON C.NUNOTA = R.NUNOTA JOIN TORCFG G ON G.CODEMP = C.CODEMP " +
        "WHERE R.SITUACAO = 'AP' AND NVL(G.ENDATIVO, 'N') = 'S' AND C.STATUSNOTA = 'L' AND R.DHAPROV >= $comCorte " +
        "AND (R.ENDSIT IS NULL OR (R.ENDSIT = 'ER' AND R.DHENDER < SYSDATE - 30 / 1440)) " +
        "AND NOT EXISTS (SELECT 1 FROM TORPPV A WHERE A.NUNOTA = R.NUNOTA AND A.NUCONF = R.NUCONF AND A.STATUS IN ('P', 'A', 'E')) " +
        "ORDER BY R.DHAPROV) WHERE ROWNUM <= 10"

    suspend fun gerarPendentes(tenantSlug: String, tenantId: java.util.UUID) {
        if (!disponivel(tenantId)) return
        val linhas = runCatching {
            wms.backend.erp.SankhyaDbExplorerClient.executarQuery(tenantSlug, sqlPendentes("NVL(G.DHINIEND, TRUNC(SYSDATE))"))
        }.recoverCatching {
            // Addon anterior ao 0.5.2 (sem TORCFG.DHINIEND, ou sem as tabelas do endereçamento): corte = hoje.
            if (it.message?.contains("ORA-00942") == true) return
            wms.backend.erp.SankhyaDbExplorerClient.executarQuery(tenantSlug, sqlPendentes("TRUNC(SYSDATE)"))
        }.getOrElse {
            println("AVISO: compras a endereçar (tenant $tenantSlug) não lidas: ${it.message}")
            return
        }
        for (l in linhas) {
            val nunota = l["NUNOTA"]?.trim()?.substringBefore('.') ?: continue
            val nuconf = l["NUCONF"]?.trim()?.substringBefore('.') ?: continue
            runCatching {
                val r = SankhyaSpClient.chamarRaw(tenantSlug, "EnderecamentoSP.gerarTarefas", MODULO_ADDON,
                    corpo("NUNOTA" to nunota, "NUCONF" to nuconf), retentar = false)
                val n = (body(r)["CRIADAS"] as? JsonPrimitive)?.contentOrNull
                println("INFO: tarefas de armazenagem da compra $nunota (conferência $nuconf): ${n ?: "?"} criada(s)")
            }.onFailure { println("AVISO: tarefas de armazenagem da compra $nunota falharam: ${it.message}") }
        }
    }

    // ─── Leitura da resposta do addon (o Studio devolve lista de 1 item às vezes como objeto) ───────────────────────

    internal fun objetos(v: JsonElement?): List<JsonObject> = when (v) {
        is JsonArray -> v.mapNotNull { it as? JsonObject }
        is JsonObject -> listOf(v)
        else -> emptyList()
    }

    private fun JsonObject.txt(c: String): String? {
        val v = this[c] ?: return null
        val p = (v as? JsonObject)?.get("$") ?: v
        return (p as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun JsonObject.num(c: String): Double? = txt(c)?.replace(',', '.')?.toDoubleOrNull()

    internal fun tarefa(o: JsonObject) = TarefaArmazenagemDto(
        nutar = o.num("NUTAR")?.toLong() ?: 0,
        situacao = o.txt("SITUACAO") ?: "AB",
        nunota = o.num("NUNOTA")?.toLong(),
        numnota = o.num("NUMNOTA")?.toLong(),
        fornecedor = o.txt("NOMEPARC"),
        codemp = o.num("CODEMP")?.toInt(),
        codlocal = o.num("CODLOCAL")?.toInt(),
        codprod = o.num("CODPROD")?.toInt() ?: 0,
        descricao = o.txt("DESCRPROD") ?: "",
        referencia = o.txt("REFERENCIA"),
        controle = o.txt("CONTROLE"),
        unidade = o.txt("CODVOL"),
        qtd = o.num("QTD") ?: 0.0,
        qtdFeita = o.num("QTDFEITA") ?: 0.0,
        origem = o.txt("ORIGEM"),
        sugerido = o.txt("SUGERIDO"),
        tipoSugerido = o.txt("TIPOSUGERIDO"),
        codusu = o.num("CODUSU")?.toInt(),
        criada = o.txt("CRIADA"),
        barras = o.txt("BARRAS")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList(),
    )
}

/** Mensagem do Sankhya sem o prefixo técnico do gateway. */
private fun mensagem(e: Throwable): String = (e.message ?: "falha no Sankhya").substringAfterLast("Exception:").trim()

fun Route.armazenagemRoutes() {
    /** Aba Armazenagem do Recebimento: andamento por compra (só leitura no Sankhya). */
    get("/api/armazenagem/compras") {
        val claims = call.exigirAuth() ?: return@get
        try {
            call.respond(ArmazenagemCompras.listar(claims))
        } catch (e: Exception) {
            call.respond(HttpStatusCode.BadGateway, mapOf("erro" to mensagem(e)))
        }
    }

    route("/api/armazenagem/tarefas") {
        get {
            val claims = call.exigirAuth() ?: return@get
            try {
                call.respond(Armazenagem.tarefas(claims))
            } catch (e: ArmazenagemException) {
                call.respond(HttpStatusCode.BadRequest, mapOf("erro" to e.message))
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadGateway, mapOf("erro" to mensagem(e)))
            }
        }

        post("/{nutar}/iniciar") {
            val claims = call.exigirAuth() ?: return@post
            val nutar = call.parameters["nutar"]?.toLongOrNull()
                ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("erro" to "tarefa inválida"))
            try {
                call.respond(Armazenagem.iniciar(claims, nutar))
            } catch (e: ArmazenagemException) {
                call.respond(HttpStatusCode.BadRequest, mapOf("erro" to e.message))
            } catch (e: Exception) {
                call.respond(HttpStatusCode.UnprocessableEntity, mapOf("erro" to mensagem(e)))
            }
        }

        post("/{nutar}/armazenar") {
            val claims = call.exigirAuth() ?: return@post
            val nutar = call.parameters["nutar"]?.toLongOrNull()
                ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("erro" to "tarefa inválida"))
            val body = call.receive<ArmazenarRequest>()
            try {
                call.respond(Armazenagem.armazenar(claims, nutar, body))
            } catch (e: ArmazenagemException) {
                call.respond(HttpStatusCode.BadRequest, mapOf("erro" to e.message))
            } catch (e: Exception) {
                call.respond(HttpStatusCode.UnprocessableEntity, mapOf("erro" to mensagem(e)))
            }
        }
    }
}
