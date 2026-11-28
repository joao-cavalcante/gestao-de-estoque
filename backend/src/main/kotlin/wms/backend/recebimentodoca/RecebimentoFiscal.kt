package wms.backend.recebimentodoca

import io.ktor.http.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.javatime.timestamp
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import wms.backend.erp.SankhyaDbExplorerClient
import wms.backend.erp.SankhyaSpClient
import wms.backend.tenancy.Modulos
import wms.backend.tenancy.TenantRepository
import wms.backend.tenancy.TenantTx
import java.time.Instant
import java.util.UUID

object RecebimentoDecisoesTable : Table("app.recebimento_decisoes") {
    val tenantId = uuid("tenant_id")
    val nunota = integer("nunota")
    val recusaTipo = text("recusa_tipo").nullable()
    val recusaMotivo = text("recusa_motivo").nullable()
    val recusaItens = text("recusa_itens").nullable()
    val recusaEm = timestamp("recusa_em").nullable()
    val recusaPor = text("recusa_por").nullable()
    val mdeEvento = text("mde_evento").nullable()
    val mdeEm = timestamp("mde_em").nullable()
    val mdeSituacao = text("mde_situacao").nullable()
    val mdeMensagem = text("mde_mensagem").nullable()
    override val primaryKey = PrimaryKey(tenantId, nunota)
}

@Serializable
data class ItemRecusaDto(val codprod: Int, val qtd: Double, val unidade: String? = null)

@Serializable
data class RecusaRequest(val tipo: String, val motivo: String, val itens: List<ItemRecusaDto> = emptyList())

@Serializable
data class DecisaoDto(
    val recusaTipo: String? = null,
    val recusaMotivo: String? = null,
    val recusaItens: List<ItemRecusaDto> = emptyList(),
    val recusaEm: String? = null,
    val recusaPor: String? = null,
    val mdeEvento: String? = null,
    val mdeEm: String? = null,
    val mdeSituacao: String? = null,
    val mdeMensagem: String? = null,
    /** Módulos ligados no cliente (a tela só mostra o que estiver ligado). */
    val recusaHabilitada: Boolean = false,
    val mdeHabilitada: Boolean = false,
)

/**
 * MD-e e recusa da carga (recebimento na doca, D3 — 28/11/2026). Os dois DESLIGADOS por padrão (Modulos).
 *
 * MD-e pelo serviço nativo `ImportacaoXMLNotasSP.registrarManifestacao` (mge-modelcore 4.35, descompilado em
 * 28/11/2026): `params` com chaveNFe, acao (210200 Confirmação · 210240 Operação não realizada), codEmp, nuNota e
 * justificativa. O Sankhya dá a Ciência antes se faltar, recusa Confirmação de nota não confirmada (com a preferência
 * ligada) e devolve um `mensagem` quando algo não foi feito.
 * Recusa: fica registrada na Torre (comprovante, indicadores). Total + MD-e ligada = "Operação não realizada" na Sefaz.
 * A nota de devolução / exclusão da compra no Sankhya continua com o fiscal do cliente.
 */
object RecebimentoFiscal {
    const val CONFIRMACAO = "210200"
    const val NAO_REALIZADA = "210240"
    private val json = Json { ignoreUnknownKeys = true }

    private fun modulos(tenantId: UUID) = runCatching { TenantRepository.modulosHabilitados(tenantId) }.getOrDefault(emptySet())

    fun decisao(tenantId: UUID, nunota: Int): DecisaoDto {
        val m = modulos(tenantId)
        val r = TenantTx.run(tenantId) {
            RecebimentoDecisoesTable.selectAll()
                .where { (RecebimentoDecisoesTable.tenantId eq tenantId) and (RecebimentoDecisoesTable.nunota eq nunota) }.singleOrNull()
        }
        val base = DecisaoDto(recusaHabilitada = Modulos.RECUSA_CARGA in m, mdeHabilitada = Modulos.MDE_CONFIRMACAO_RECEBIMENTO in m)
        return r?.let {
            base.copy(
                recusaTipo = it[RecebimentoDecisoesTable.recusaTipo], recusaMotivo = it[RecebimentoDecisoesTable.recusaMotivo],
                recusaItens = it[RecebimentoDecisoesTable.recusaItens]?.let { s -> runCatching { json.decodeFromString<List<ItemRecusaDto>>(s) }.getOrNull() }.orEmpty(),
                recusaEm = it[RecebimentoDecisoesTable.recusaEm]?.toString(), recusaPor = it[RecebimentoDecisoesTable.recusaPor],
                mdeEvento = it[RecebimentoDecisoesTable.mdeEvento], mdeEm = it[RecebimentoDecisoesTable.mdeEm]?.toString(),
                mdeSituacao = it[RecebimentoDecisoesTable.mdeSituacao], mdeMensagem = it[RecebimentoDecisoesTable.mdeMensagem],
            )
        } ?: base
    }

    private fun gravar(tenantId: UUID, nunota: Int, bloco: (org.jetbrains.exposed.sql.statements.UpdateBuilder<*>) -> Unit) = TenantTx.run(tenantId) {
        val existe = RecebimentoDecisoesTable.selectAll()
            .where { (RecebimentoDecisoesTable.tenantId eq tenantId) and (RecebimentoDecisoesTable.nunota eq nunota) }.any()
        if (existe) RecebimentoDecisoesTable.update({ (RecebimentoDecisoesTable.tenantId eq tenantId) and (RecebimentoDecisoesTable.nunota eq nunota) }) { bloco(it) }
        else RecebimentoDecisoesTable.insert {
            it[RecebimentoDecisoesTable.tenantId] = tenantId
            it[RecebimentoDecisoesTable.nunota] = nunota
            bloco(it)
        }
    }

    /** Corpo do ImportacaoXMLNotasSP.registrarManifestacao. */
    internal fun corpoManifestacao(chave: String, acao: String, codemp: String, nunota: Long, justificativa: String?): JsonObject = buildJsonObject {
        putJsonObject("params") {
            put("chaveNFe", chave)
            put("acao", acao)
            put("codEmp", codemp)
            put("nuNota", nunota.toString())
            justificativa?.let { put("justificativa", it) }
        }
    }

    /** `mensagem` do retorno = o Sankhya avisou que não fez (ex.: nota não confirmada). Vazio = enviado. */
    internal fun mensagemRetorno(resposta: JsonObject): String? {
        val m = resposta["mensagem"] ?: (resposta["body"] as? JsonObject)?.get("mensagem") ?: return null
        val v = (m as? JsonObject)?.get("$") ?: m
        return (v as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
    }

    /** Envia o evento de MD-e da compra. Só com o módulo ligado; registra o resultado. */
    suspend fun manifestar(tenantSlug: String, tenantId: UUID, nunota: Long, acao: String, justificativa: String? = null): String? {
        if (Modulos.MDE_CONFIRMACAO_RECEBIMENTO !in modulos(tenantId)) return null
        val (situacao, msg) = runCatching {
            val cab = SankhyaDbExplorerClient.executarQuery(tenantSlug, "SELECT CHAVENFE, CODEMP FROM TGFCAB WHERE NUNOTA = $nunota").firstOrNull()
            val chave = cab?.get("CHAVENFE")?.trim()?.takeIf { it.length == 44 } ?: error("compra sem chave de NF-e de 44 dígitos")
            val codemp = cab["CODEMP"]?.trim()?.substringBefore('.') ?: error("compra sem empresa")
            val r = SankhyaSpClient.chamarRaw(tenantSlug, "ImportacaoXMLNotasSP.registrarManifestacao", "mge",
                corpoManifestacao(chave, acao, codemp, nunota, justificativa), retentar = false)
            val aviso = mensagemRetorno(r)
            (if (aviso == null) "OK" else "ERRO") to (aviso ?: "evento enviado")
        }.getOrElse { "ERRO" to (it.message ?: "falha ao enviar a MD-e") }
        println("INFO: MD-e $acao da compra $nunota: $situacao — $msg")
        gravar(tenantId, nunota.toInt()) {
            it[RecebimentoDecisoesTable.mdeEvento] = acao
            it[RecebimentoDecisoesTable.mdeEm] = Instant.now()
            it[RecebimentoDecisoesTable.mdeSituacao] = situacao
            it[RecebimentoDecisoesTable.mdeMensagem] = msg.take(500)
        }
        return if (situacao == "OK") null else msg
    }

    suspend fun recusar(tenantSlug: String, tenantId: UUID, nunota: Int, req: RecusaRequest, nome: String): DecisaoDto {
        if (Modulos.RECUSA_CARGA !in modulos(tenantId)) throw DocaException("Recusa da carga não está ligada para este cliente.", HttpStatusCode.Forbidden)
        val tipo = req.tipo.uppercase()
        if (tipo !in setOf("TOTAL", "PARCIAL")) throw DocaException("Tipo de recusa inválido.")
        val motivo = req.motivo.trim()
        // A Sefaz exige justificativa de 15 a 255 caracteres na "Operação não realizada".
        if (motivo.length < 15) throw DocaException("Descreva o motivo da recusa (mínimo 15 caracteres).")
        if (tipo == "PARCIAL" && req.itens.none { it.qtd > 0 }) throw DocaException("Informe os produtos e as quantidades recusadas.")
        gravar(tenantId, nunota) {
            it[RecebimentoDecisoesTable.recusaTipo] = tipo
            it[RecebimentoDecisoesTable.recusaMotivo] = motivo.take(255)
            it[RecebimentoDecisoesTable.recusaItens] = json.encodeToString(kotlinx.serialization.builtins.ListSerializer(ItemRecusaDto.serializer()), req.itens.filter { i -> i.qtd > 0 })
            it[RecebimentoDecisoesTable.recusaEm] = Instant.now()
            it[RecebimentoDecisoesTable.recusaPor] = nome
        }
        if (tipo == "TOTAL") manifestar(tenantSlug, tenantId, nunota.toLong(), NAO_REALIZADA, motivo.take(255))
        return decisao(tenantId, nunota)
    }
}
