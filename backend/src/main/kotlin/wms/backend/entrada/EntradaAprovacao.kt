package wms.backend.entrada

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import wms.backend.erp.SankhyaSpClient
import wms.backend.liberacaocorte.LiberacaoCorteService
import wms.backend.tenancy.TenantRepository
import java.util.UUID

/**
 * Aprovar o recebimento na mesa (etapa 2 do plano "Entrada com endereçamento", 23/11/2026).
 *
 * A responsável entra com usuário e senha do Sankhya (mesma validação da liberação de corte → CODUSU) e o addon TOR
 * (`RecebimentoSP.aprovar`) confirma a compra EM NOME DELA e grava TORREC; só usuários da lista
 * TORCFG.USUAPROVREC aprovam (decisão 23/11/2026). O ajuste de peso continua no ciclo de sincronização
 * (AjustePesoCompra espera a nota confirmada) — outra transação, como pede o plano (B4).
 *
 * "Devolver para recontagem" NÃO está aqui: o Sankhya recusa recontagem de conferência finalizada F (CORE_E01275) e,
 * com MOMENTOCONFERENCIA 'C', de nota confirmada (CORE_E01279) — `ConferenciaHelper.marcarRecontagem`, 5.26.6.
 * Aguardando decisão do usuário.
 */
object EntradaAprovacao {

    class AprovacaoException(msg: String) : Exception(msg)

    private const val MODULO_ADDON = "torre"

    /** O botão só aparece com o addon TOR ligado no cliente. */
    fun disponivel(tenantId: UUID): Boolean =
        runCatching { TenantRepository.parametros(tenantId).ajustePesoCompra.viaAddon }.getOrDefault(false)

    suspend fun aprovar(tenantSlug: String, tenantId: UUID, nunota: Long, usuario: String, senha: String): AprovarRecebimentoResponse {
        if (!disponivel(tenantId)) throw AprovacaoException("Aprovar pela Torre exige o addon TOR ligado para este cliente.")
        val situacao = EntradaSituacao.atualizarUma(tenantSlug, tenantId, nunota)
            ?: throw AprovacaoException("Compra $nunota não encontrada no Sankhya.")
        val nuconf = situacao.nuconfAtual ?: throw AprovacaoException("A compra $nunota não tem conferência.")
        val codusu = try {
            LiberacaoCorteService.validarLiberador(tenantSlug, usuario, senha)
        } catch (e: LiberacaoCorteService.LiberacaoCorteException) {
            throw AprovacaoException(e.message ?: "Usuário ou senha inválidos no Sankhya.")
        }
        // Não repete sozinho: aprovar confirma nota. Se a resposta se perder, nova aprovação devolve JAEXISTIA.
        val resposta = SankhyaSpClient.chamarRaw(tenantSlug, "RecebimentoSP.aprovar", MODULO_ADDON, corpo(nunota, nuconf, codusu), retentar = false)
        val r = lerResposta(resposta)
        EntradaSituacao.atualizarUma(tenantSlug, tenantId, nunota)
        println("INFO: recebimento $nunota (conferência $nuconf) — aprovar por CODUSU $codusu: ${r.situacao}${if (r.liberacoes.isEmpty()) "" else " ${r.liberacoes}"}")
        // Encadeamento decidido em 23/11/2026: com a compra confirmada, o ajuste de peso sai na mesma ação (transação 2,
        // pelo addon) em vez de esperar o próximo ciclo. Falha aqui não desfaz a aprovação — o ciclo tenta de novo.
        if (r.situacao == "APROVADO") {
            runCatching { wms.backend.separacao.AjustePesoCompra.processarPendentes(tenantSlug, tenantId) }
                .onFailure { println("AVISO: ajuste de peso logo após aprovar $nunota falhou (o ciclo tenta de novo): ${it.message}") }
            // D3 (28/11/2026) — validade/fabricação dos lotes dos paletes no estoque do lote (CACSP.salvarInformacoesLote).
            runCatching { wms.backend.separacao.LoteCompra.gravarValidades(tenantSlug, tenantId, nunota) }
                .onFailure { println("AVISO: validade dos lotes da compra $nunota não gravada: ${it.message}") }
            runCatching { wms.backend.armazenagem.Armazenagem.gerarPendentes(tenantSlug, tenantId) }
                .onFailure { println("AVISO: tarefas de armazenagem logo após aprovar $nunota falharam (o ciclo tenta de novo): ${it.message}") }
        }
        return r
    }

    internal fun corpo(nunota: Long, nuconf: Int, codusu: String): JsonObject = buildJsonObject {
        putJsonObject("request") {
            put("NUNOTA", nunota.toString())
            put("NUCONF", nuconf.toString())
            put("CODUSU", codusu.trim().substringBefore('.'))
        }
    }

    /** responseBody.body = {SITUACAO, JAEXISTIA, NUREC, LIBERACOES} (o bean do Studio embrulha em `body`). */
    internal fun lerResposta(resposta: JsonObject): AprovarRecebimentoResponse {
        val corpo = resposta["body"] as? JsonObject ?: resposta
        fun texto(campo: String): String? {
            val v = corpo[campo] ?: return null
            val prim = (v as? JsonObject)?.get("$") ?: v
            return (prim as? JsonPrimitive)?.contentOrNull?.trim()
        }
        val situacao = texto("SITUACAO") ?: throw AprovacaoException("Resposta do addon sem SITUACAO: ${resposta.toString().take(300)}")
        val libs = when (val l = corpo["LIBERACOES"]) {
            is JsonArray -> l.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            is JsonPrimitive -> listOfNotNull(l.contentOrNull)
            else -> emptyList()
        }
        return AprovarRecebimentoResponse(situacao = situacao, jaExistia = texto("JAEXISTIA") == "S", liberacoes = libs)
    }
}
