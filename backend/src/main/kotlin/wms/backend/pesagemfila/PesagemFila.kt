package wms.backend.pesagemfila

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.javatime.timestamp
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import wms.backend.auth.ClaimsToken
import wms.backend.auth.exigirAuth
import wms.backend.paletes.ChaveProduto
import wms.backend.paletes.FecharPaleteRequest
import wms.backend.paletes.PaleteDto
import wms.backend.paletes.PaleteException
import wms.backend.paletes.Paletes
import wms.backend.separacao.SeparacaoRepository
import wms.backend.separacao.SeparacaoStatus
import wms.backend.tarefas.TarefasRepository
import wms.backend.tenancy.TenantTx
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.util.UUID

object PesagemFilaTable : Table("app.pesagem_fila") {
    val id = uuid("id")
    val tenantId = uuid("tenant_id")
    val sessaoId = uuid("sessao_id")
    val nunota = integer("nunota")
    val codprod = integer("codprod")
    val controle = text("controle")
    val descricao = text("descricao").nullable()
    val caixas = decimal("caixas", 15, 5)
    val unidadeComercial = text("unidade_comercial").nullable()
    val codvol = text("codvol").nullable()
    val codigoBarra = text("codigo_barra").nullable()
    val conferenteUserId = uuid("conferente_user_id")
    val conferenteNome = text("conferente_nome").nullable()
    val criadoEm = timestamp("criado_em")
    val status = text("status")
    val pesoBruto = decimal("peso_bruto", 15, 5).nullable()
    val tara = decimal("tara", 15, 5).nullable()
    val pesoLiquido = decimal("peso_liquido", 15, 5).nullable()
    val origemPeso = text("origem_peso").nullable()
    val pesadoPor = text("pesado_por").nullable()
    val pesadoEm = timestamp("pesado_em").nullable()
    val paleteId = uuid("palete_id").nullable()
    override val primaryKey = PrimaryKey(id)
}

@Serializable
data class EnviarBalancaRequest(
    val sessaoId: String,
    val codprod: Int,
    val controle: String = " ",
    val caixas: Double,
    val unidadeComercial: String? = null,
    val codvol: String? = null,
    val codigoBarra: String? = null,
)

@Serializable
data class PesarRequest(val pesoBruto: Double, val tara: Double = 0.0, val origemPeso: String = "MANUAL", val fecharPalete: Boolean = true)

@Serializable
data class ItemFilaDto(
    val id: String,
    val sessaoId: String,
    val nunota: Int,
    val numNota: Long?,
    val fornecedor: String?,
    val codprod: Int,
    val controle: String,
    val descricao: String?,
    val caixas: Double,
    val unidadeComercial: String?,
    val conferente: String?,
    val criadoEm: String,
)

@Serializable
data class PesadoDto(val item: ItemFilaDto, val pesoLiquido: Double, val palete: PaleteDto?)

/**
 * Fila de pesagem (V84, 27/11/2026) — computador da balança, exclusivo do pesador. O conferente conta as caixas do
 * pesável no coletor e manda para a balança; aqui o pesador pesa (bruto − tara = líquido) e a leitura entra na
 * conferência NO NOME DO CONFERENTE (quantidade = peso líquido, caixas = quantidade comercial, como no pop-up de
 * peso). Com "fechar palete", o palete daquela pesagem sai fechado para imprimir a etiqueta ali mesmo.
 */
object PesagemFila {
    private fun c(s: String?) = s?.takeIf { it.isNotBlank() } ?: " "

    fun enviar(claims: ClaimsToken, req: EnviarBalancaRequest): ItemFilaDto {
        val sessaoId = runCatching { UUID.fromString(req.sessaoId) }.getOrNull() ?: throw PaleteException("Conferência inválida.")
        val s = SeparacaoRepository.buscarSessao(claims.tenantId, sessaoId) ?: throw PaleteException("Conferência não encontrada.", HttpStatusCode.NotFound)
        if (s.status != SeparacaoStatus.PRONTA) throw PaleteException("Esta conferência já foi encerrada.", HttpStatusCode.Conflict)
        if (!(req.caixas > 0)) throw PaleteException("Informe quantas caixas vão para a balança.")
        val nome = runCatching { wms.backend.galpao.nomeUsuario(claims) }.getOrNull()
        val desc = Paletes.descricao(claims.tenantId, sessaoId, req.codprod, c(req.controle))
            ?: throw PaleteException("O produto ${req.codprod} não está nesta conferência.")
        val id = UUID.randomUUID()
        TenantTx.run(claims.tenantId) {
            PesagemFilaTable.insert {
                it[PesagemFilaTable.id] = id
                it[tenantId] = claims.tenantId
                it[PesagemFilaTable.sessaoId] = sessaoId
                it[nunota] = s.nunota.toInt()
                it[codprod] = req.codprod
                it[controle] = c(req.controle)
                it[descricao] = desc
                it[caixas] = req.caixas.toBigDecimal()
                it[unidadeComercial] = req.unidadeComercial?.trim()?.takeIf { u -> u.isNotEmpty() }
                it[codvol] = req.codvol?.trim()?.takeIf { u -> u.isNotEmpty() }
                it[codigoBarra] = req.codigoBarra?.trim()?.takeIf { u -> u.isNotEmpty() }
                it[conferenteUserId] = claims.userId
                it[conferenteNome] = nome
                it[criadoEm] = Instant.now()
                it[status] = "PENDENTE"
            }
        }
        return listar(claims.tenantId).first { it.id == id.toString() }
    }

    fun listar(tenantId: UUID): List<ItemFilaDto> {
        val linhas = TenantTx.run(tenantId) {
            PesagemFilaTable.selectAll()
                .where { (PesagemFilaTable.tenantId eq tenantId) and (PesagemFilaTable.status eq "PENDENTE") }
                .orderBy(PesagemFilaTable.criadoEm to SortOrder.ASC)
                .toList()
        }
        val notas = linhas.map { it[PesagemFilaTable.nunota].toLong() }.distinct().associateWith {
            TarefasRepository.buscarNumNotaLocal(tenantId, it) to TarefasRepository.buscarNomeParceiroLocal(tenantId, it)
        }
        return linhas.map { r ->
            val n = notas[r[PesagemFilaTable.nunota].toLong()]
            ItemFilaDto(
                r[PesagemFilaTable.id].toString(), r[PesagemFilaTable.sessaoId].toString(), r[PesagemFilaTable.nunota], n?.first, n?.second,
                r[PesagemFilaTable.codprod], r[PesagemFilaTable.controle], r[PesagemFilaTable.descricao], r[PesagemFilaTable.caixas].toDouble(),
                r[PesagemFilaTable.unidadeComercial], r[PesagemFilaTable.conferenteNome], r[PesagemFilaTable.criadoEm].toString(),
            )
        }
    }

    fun pendentesDaSessao(tenantId: UUID, sessaoId: UUID): Int = TenantTx.run(tenantId) {
        PesagemFilaTable.selectAll()
            .where { (PesagemFilaTable.tenantId eq tenantId) and (PesagemFilaTable.sessaoId eq sessaoId) and (PesagemFilaTable.status eq "PENDENTE") }
            .count().toInt()
    }

    fun pesar(claims: ClaimsToken, id: UUID, req: PesarRequest): PesadoDto {
        val tenantId = claims.tenantId
        val item = listar(tenantId).firstOrNull { it.id == id.toString() }
            ?: throw PaleteException("Este item já foi pesado ou cancelado.", HttpStatusCode.Conflict)
        val bruto = req.pesoBruto.toBigDecimal().setScale(3, RoundingMode.HALF_UP)
        val tara = req.tara.toBigDecimal().setScale(3, RoundingMode.HALF_UP)
        if (bruto.signum() <= 0) throw PaleteException("Informe o peso bruto.")
        if (tara.signum() < 0) throw PaleteException("Tara inválida.")
        val liquido = bruto - tara
        if (liquido.signum() <= 0) throw PaleteException("A tara é maior ou igual ao peso.")
        val sessaoId = UUID.fromString(item.sessaoId)
        val s = SeparacaoRepository.buscarSessao(tenantId, sessaoId)
        if (s == null || s.status != SeparacaoStatus.PRONTA) throw PaleteException("A conferência desta compra já foi encerrada.", HttpStatusCode.Conflict)
        // Reserva o item (dois computadores não pesam o mesmo).
        val (conferente, linha) = TenantTx.run(tenantId) {
            val n = PesagemFilaTable.update({ (PesagemFilaTable.tenantId eq tenantId) and (PesagemFilaTable.id eq id) and (PesagemFilaTable.status eq "PENDENTE") }) {
                it[status] = "PESANDO"
            }
            if (n == 0) throw PaleteException("Este item já está sendo pesado.", HttpStatusCode.Conflict)
            val r = PesagemFilaTable.selectAll().where { (PesagemFilaTable.tenantId eq tenantId) and (PesagemFilaTable.id eq id) }.single()
            r[PesagemFilaTable.conferenteUserId] to r
        }
        val pesador = runCatching { wms.backend.galpao.nomeUsuario(claims) }.getOrNull()
        val origem = req.origemPeso.uppercase().takeIf { it in setOf("BALANCA", "MANUAL") } ?: "MANUAL"
        try {
            SeparacaoRepository.conferirItem(
                tenantId, sessaoId, item.codprod, item.controle, liquido, SeparacaoRepository.permiteQtdMaior(tenantId, sessaoId), liquido,
                codvolEscanado = linha[PesagemFilaTable.codvol], codigoBarra = linha[PesagemFilaTable.codigoBarra],
                qtdComercial = linha[PesagemFilaTable.caixas].takeIf { linha[PesagemFilaTable.unidadeComercial] != null },
                unidadeComercial = linha[PesagemFilaTable.unidadeComercial],
                pesoBruto = bruto, tara = tara, origemPeso = origem,
                conferenteUserId = conferente, conferenteNome = linha[PesagemFilaTable.conferenteNome],
            ) ?: throw PaleteException("O produto não está mais nesta conferência.")
        } catch (e: Exception) {
            TenantTx.run(tenantId) {
                PesagemFilaTable.update({ (PesagemFilaTable.tenantId eq tenantId) and (PesagemFilaTable.id eq id) }) { it[status] = "PENDENTE" }
            }
            if (e is SeparacaoRepository.QuantidadeExcedeException) {
                throw PaleteException("Peso acima do pendente da nota (máximo ${e.maximo.stripTrailingZeros().toPlainString()} kg).", HttpStatusCode.Conflict)
            }
            throw e
        }
        val palete = if (req.fecharPalete) runCatching {
            Paletes.fechar(
                tenantId, sessaoId, FecharPaleteRequest(listOf(ChaveProduto(item.codprod, item.controle))),
                linha[PesagemFilaTable.conferenteNome] ?: pesador ?: "Pesagem", conferente,
                qtdExata = (item.codprod to item.controle) to liquido,
            ).firstOrNull()
        }.onFailure { println("AVISO: pesagem $id gravada, mas o palete não fechou: ${it.message}") }.getOrNull() else null
        TenantTx.run(tenantId) {
            PesagemFilaTable.update({ (PesagemFilaTable.tenantId eq tenantId) and (PesagemFilaTable.id eq id) }) {
                it[status] = "PESADO"
                it[pesoBruto] = bruto
                it[PesagemFilaTable.tara] = tara
                it[pesoLiquido] = liquido
                it[origemPeso] = origem
                it[pesadoPor] = pesador
                it[pesadoEm] = Instant.now()
                it[paleteId] = palete?.id?.let { p -> UUID.fromString(p) }
            }
        }
        return PesadoDto(item, liquido.toDouble(), palete)
    }

    fun cancelar(claims: ClaimsToken, id: UUID) {
        val n = TenantTx.run(claims.tenantId) {
            PesagemFilaTable.update({ (PesagemFilaTable.tenantId eq claims.tenantId) and (PesagemFilaTable.id eq id) and (PesagemFilaTable.status eq "PENDENTE") }) {
                it[status] = "CANCELADO"
            }
        }
        if (n == 0) throw PaleteException("Este item já foi pesado ou cancelado.", HttpStatusCode.Conflict)
    }
}

private suspend fun ApplicationCall.filaErro(e: Exception) = when (e) {
    is PaleteException -> respond(e.status, mapOf("erro" to e.message))
    else -> {
        println("AVISO: fila de pesagem: ${e.message}")
        respond(HttpStatusCode.InternalServerError, mapOf("erro" to (e.message ?: "falha na fila de pesagem")))
    }
}

fun Route.pesagemFilaRoutes() {
    route("/api/pesagem-fila") {
        get {
            val claims = call.exigirAuth() ?: return@get
            call.respond(PesagemFila.listar(claims.tenantId))
        }
        post {
            val claims = call.exigirAuth() ?: return@post
            try { call.respond(PesagemFila.enviar(claims, call.receive())) } catch (e: Exception) { call.filaErro(e) }
        }
        post("/{id}/pesar") {
            val claims = call.exigirAuth() ?: return@post
            val id = runCatching { UUID.fromString(call.parameters["id"]) }.getOrNull() ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("erro" to "item inválido"))
            try { call.respond(PesagemFila.pesar(claims, id, call.receive())) } catch (e: Exception) { call.filaErro(e) }
        }
        post("/{id}/cancelar") {
            val claims = call.exigirAuth() ?: return@post
            val id = runCatching { UUID.fromString(call.parameters["id"]) }.getOrNull() ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("erro" to "item inválido"))
            try {
                PesagemFila.cancelar(claims, id)
                call.respond(HttpStatusCode.NoContent)
            } catch (e: Exception) { call.filaErro(e) }
        }
    }
}
