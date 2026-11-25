package wms.backend.entrada

import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.javatime.timestamp
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.upsert
import wms.backend.erp.SankhyaDbExplorerClient
import wms.backend.tarefas.AjustePesoDto
import wms.backend.separacao.AjustePesoCompra
import wms.backend.tarefas.TarefasRepository
import wms.backend.tenancy.TenantTx
import java.time.Instant
import java.util.UUID

/** app.recebimento_auditoria (V79) — histórico das compras conferidas pela Torre. */
object RecebimentoAuditoriaTable : Table("app.recebimento_auditoria") {
    val tenantId = uuid("tenant_id")
    val nunota = integer("nunota")
    val nuconf = integer("nuconf").nullable()
    val numeroNota = long("numero_nota").nullable()
    val fornecedor = text("fornecedor").nullable()
    val empresa = text("empresa").nullable()
    val tipoOperacao = text("tipo_operacao").nullable()
    val dataMovimento = text("data_movimento").nullable()
    val conferente = text("conferente").nullable()
    val conferenciaInicio = text("conferencia_inicio").nullable()
    val conferenciaFim = text("conferencia_fim").nullable()
    val statusNota = text("status_nota").nullable()
    val confirmadoCodusu = integer("confirmado_codusu").nullable()
    val confirmadoNome = text("confirmado_nome").nullable()
    val confirmadoOrigem = text("confirmado_origem").nullable()
    val confirmadoEm = text("confirmado_em").nullable()
    val registradoAddon = bool("registrado_addon")
    val devolucaoNunota = long("devolucao_nunota").nullable()
    val devolucaoNumero = long("devolucao_numero").nullable()
    val devolucaoConfirmada = bool("devolucao_confirmada").nullable()
    val complementoNunota = long("complemento_nunota").nullable()
    val complementoNumero = long("complemento_numero").nullable()
    val complementoConfirmado = bool("complemento_confirmado").nullable()
    val encerrado = bool("encerrado")
    val lidoEm = timestamp("lido_em").nullable()
    val criadoEm = timestamp("criado_em")
    val atualizadoEm = timestamp("atualizado_em")

    override val primaryKey = PrimaryKey(tenantId, nunota)
}

@Serializable
data class AlertaAuditoriaDto(val tipo: String, val texto: String)

@Serializable
data class RecebimentoAuditoriaDto(
    val nunota: Long,
    val nuconf: Int? = null,
    val numeroNota: Long? = null,
    val fornecedor: String? = null,
    val empresa: String? = null,
    val tipoOperacao: String? = null,
    val dataMovimento: String? = null,
    val conferente: String? = null,
    val conferenciaInicio: String? = null,
    val conferenciaFim: String? = null,
    /** A = aberta, L = confirmada, X = não existe mais no Sankhya; null = ainda não lida. */
    val statusNota: String? = null,
    val confirmadoPor: String? = null,
    /** T = Aprovar da Torre; S = direto no Sankhya. */
    val confirmadoOrigem: String? = null,
    val confirmadoEm: String? = null,
    val ajustes: List<AjustePesoDto> = emptyList(),
    val devolucao: NotaDerivadaDto? = null,
    val complemento: NotaDerivadaDto? = null,
    val alertas: List<AlertaAuditoriaDto> = emptyList(),
    val encerrado: Boolean = false,
    val lidoEm: String? = null,
)

@Serializable
data class AuditoriaRecebimentoDetalheDto(
    val recebimento: RecebimentoAuditoriaDto,
    val itens: List<ItemMesaDto>,
    val recontagem: Boolean = false,
)

/**
 * Auditoria de Recebimento (25/11/2026, pedido do usuário: "uma tela de auditoria na entrada… se ficar travado alguma
 * geração precisa mostrar"). Grava o histórico no ciclo de sincronização e calcula os pontos de atenção na leitura.
 */
object AuditoriaRecebimento {

    /** Compra confirmada e ajuste ainda não emitido depois disto vira atenção. */
    private const val AJUSTE_ATRASADO_SEG = 10L * 60
    /** Conferida e não confirmada depois disto vira atenção. */
    private const val PARADA_SEG = 24L * 60 * 60

    // ─── Ciclo de sincronização ──────────────────────────────────────────────────────────────────────

    /** Grava as compras conferidas da Entrada que ainda não estão no histórico e relê no Sankhya as não encerradas. */
    suspend fun sincronizar(tenantSlug: String, tenantId: UUID) {
        registrarNovas(tenantId)
        val abertas = TenantTx.run(tenantId) {
            RecebimentoAuditoriaTable.selectAll()
                .where { (RecebimentoAuditoriaTable.tenantId eq tenantId) and (RecebimentoAuditoriaTable.encerrado eq false) }
                .limit(500)
                .associate { it[RecebimentoAuditoriaTable.nunota].toLong() to it[RecebimentoAuditoriaTable.nuconf] }
        }
        if (abertas.isEmpty()) return
        val situacoes = HashMap<Long, EntradaSituacao.Situacao>()
        for (lote in abertas.keys.chunked(500)) situacoes.putAll(EntradaSituacao.lerSituacoes(tenantSlug, lote))
        val confirmacoes = runCatching { lerTorrec(tenantSlug, abertas.keys.toList()) }.getOrElse { emptyMap() }
        val viaAddon = AjustePesoCompra.parametros(tenantId).viaAddon
        val ajustes = AjustePesoCompra.situacaoPorNota(tenantId, abertas.keys)
        val agora = Instant.now()
        TenantTx.run(tenantId) {
            for ((nunota, nuconf) in abertas) {
                val s = situacoes[nunota]
                val c = confirmacoes[nunota]?.takeIf { nuconf == null || it.nuconf == nuconf }
                val ajustePendente = ajustes[nunota].orEmpty().any { it.status !in setOf("confirmado", "cancelado") }
                val statusNota = s?.statusNota ?: "X"
                val encerrar = statusNota == "X" || (
                    statusNota == "L" && !ajustePendente &&
                        (s?.devolucao == null || s.devolucao.confirmada) &&
                        (s?.complemento == null || s.complemento.confirmada) &&
                        (!viaAddon || c?.situacao == "AP")
                    )
                RecebimentoAuditoriaTable.update({ (RecebimentoAuditoriaTable.tenantId eq tenantId) and (RecebimentoAuditoriaTable.nunota eq nunota.toInt()) }) {
                    it[RecebimentoAuditoriaTable.statusNota] = statusNota
                    if (s?.nuconfAtual != null) it[RecebimentoAuditoriaTable.nuconf] = s.nuconfAtual
                    it[registradoAddon] = c != null
                    if (c != null && c.situacao == "AP") {
                        it[confirmadoCodusu] = c.codusu
                        it[confirmadoNome] = c.nome
                        it[confirmadoOrigem] = c.origem
                        it[confirmadoEm] = c.dhAprov
                    }
                    it[devolucaoNunota] = s?.devolucao?.nunota
                    it[devolucaoNumero] = s?.devolucao?.numero
                    it[devolucaoConfirmada] = s?.devolucao?.confirmada
                    it[complementoNunota] = s?.complemento?.nunota
                    it[complementoNumero] = s?.complemento?.numero
                    it[complementoConfirmado] = s?.complemento?.confirmada
                    it[encerrado] = encerrar
                    it[lidoEm] = agora
                    it[atualizadoEm] = agora
                }
            }
        }
    }

    /** Compras conferidas pela Torre (Entrada) que ainda não estão no histórico. */
    private fun registrarNovas(tenantId: UUID) {
        val tarefas = TarefasRepository.listar(tenantId)
            .filter { EntradaService.ehEntrada(it.tipMov) && EntradaService.conferenciaConcluida(it.statusOperacional) }
        if (tarefas.isEmpty()) return
        val existentes = TenantTx.run(tenantId) {
            RecebimentoAuditoriaTable.selectAll()
                .where { (RecebimentoAuditoriaTable.tenantId eq tenantId) and (RecebimentoAuditoriaTable.nunota inList tarefas.map { it.nunota.toInt() }) }
                .associate { it[RecebimentoAuditoriaTable.nunota].toLong() to it[RecebimentoAuditoriaTable.conferenciaFim] }
        }
        val agora = Instant.now()
        for (t in tarefas) {
            // Já gravada com a conferência: não regrava (recontagem atualiza pelo ciclo via NUCONF).
            if (existentes.containsKey(t.nunota) && existentes[t.nunota] != null) continue
            val conf = EntradaService.conferenciaResumo(tenantId, t.nunota) ?: continue // não conferida pela Torre
            TenantTx.run(tenantId) {
                RecebimentoAuditoriaTable.upsert {
                    it[RecebimentoAuditoriaTable.tenantId] = tenantId
                    it[nunota] = t.nunota.toInt()
                    it[nuconf] = conf.nuconf
                    it[numeroNota] = t.numeroNota
                    it[fornecedor] = t.nomeParceiro
                    it[empresa] = t.nomeEmpresa ?: t.codigoEmpresa
                    it[tipoOperacao] = listOfNotNull(t.codigoTipoOperacao, t.descricaoTipoOperacao).joinToString(" · ").ifBlank { null }
                    it[dataMovimento] = t.dataMovimento
                    it[conferente] = conf.conferente
                    it[conferenciaInicio] = conf.inicio
                    it[conferenciaFim] = conf.fim
                    it[registradoAddon] = false
                    it[encerrado] = false
                    it[criadoEm] = agora
                    it[atualizadoEm] = agora
                }
            }
        }
    }

    private data class Confirmacao(val nuconf: Int?, val situacao: String?, val codusu: Int?, val nome: String?, val origem: String?, val dhAprov: String?)

    /** TORREC do addon (quem confirmou e por onde). Cliente sem addon: a tabela não existe e a leitura falha (vazio). */
    private suspend fun lerTorrec(tenantSlug: String, nunotas: List<Long>): Map<Long, Confirmacao> {
        val r = HashMap<Long, Confirmacao>()
        for (lote in nunotas.chunked(500)) {
            SankhyaDbExplorerClient.executarQuery(
                tenantSlug,
                "SELECT R.NUNOTA, R.NUCONF, R.SITUACAO, R.CODUSUAPROV, U.NOMEUSU, R.ORIGEM, " +
                    "TO_CHAR(R.DHAPROV, 'DD/MM/YYYY HH24:MI') AS DHAPROV FROM TORREC R " +
                    "LEFT JOIN TSIUSU U ON U.CODUSU = R.CODUSUAPROV WHERE R.NUNOTA IN (${lote.joinToString(",")})",
            ).forEach { l ->
                val n = EntradaSituacao.longo(l["NUNOTA"]) ?: return@forEach
                val nova = Confirmacao(
                    EntradaSituacao.longo(l["NUCONF"])?.toInt(), l["SITUACAO"]?.trim(),
                    l["CODUSUAPROV"]?.trim()?.replace(',', '.')?.toBigDecimalOrNull()?.toInt(),
                    l["NOMEUSU"]?.trim()?.takeIf { it.isNotEmpty() }, l["ORIGEM"]?.trim()?.takeIf { it.isNotEmpty() },
                    l["DHAPROV"]?.trim()?.takeIf { it.isNotEmpty() },
                )
                // Mais de uma conferência: fica a de NUCONF maior (a atual).
                val atual = r[n]
                if (atual == null || (nova.nuconf ?: 0) >= (atual.nuconf ?: 0)) r[n] = nova
            }
        }
        return r
    }

    // ─── Leitura (tela) ──────────────────────────────────────────────────────────────────────────────

    fun listar(tenantId: UUID): List<RecebimentoAuditoriaDto> {
        val linhas = TenantTx.run(tenantId) {
            RecebimentoAuditoriaTable.selectAll().where { RecebimentoAuditoriaTable.tenantId eq tenantId }.toList()
        }
        val ajustes = runCatching { AjustePesoCompra.situacaoPorNota(tenantId, linhas.map { it[RecebimentoAuditoriaTable.nunota].toLong() }) }
            .getOrElse { emptyMap() }
        val viaAddon = runCatching { AjustePesoCompra.parametros(tenantId).viaAddon }.getOrDefault(false)
        val agora = Instant.now()
        return linhas.map { dto(it, ajustes[it[RecebimentoAuditoriaTable.nunota].toLong()].orEmpty(), viaAddon, agora) }
            .sortedWith(compareByDescending<RecebimentoAuditoriaDto> { it.alertas.isNotEmpty() }.thenByDescending { it.nunota })
    }

    fun detalhe(tenantId: UUID, nunota: Long): AuditoriaRecebimentoDetalheDto? {
        val r = listar(tenantId).firstOrNull { it.nunota == nunota } ?: return null
        val conf = EntradaService.conferenciaResumo(tenantId, nunota)
        return AuditoriaRecebimentoDetalheDto(r, conf?.itens.orEmpty(), conf?.recontagem ?: false)
    }

    /** "dd/MM/yyyy HH:mm" do TORREC (horário de Brasília) → Instant; ilegível = null. */
    private fun confirmadoEm(texto: String?): Instant? = runCatching {
        java.time.LocalDateTime.parse(texto!!.trim(), java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))
            .atZone(java.time.ZoneId.of("America/Sao_Paulo")).toInstant()
    }.getOrNull()

    private fun dto(row: org.jetbrains.exposed.sql.ResultRow, ajustes: List<AjustePesoCompra.SituacaoAjuste>, viaAddon: Boolean, agora: Instant): RecebimentoAuditoriaDto {
        val T = RecebimentoAuditoriaTable
        val statusNota = row[T.statusNota]
        val devolucao = row[T.devolucaoNunota]?.let { NotaDerivadaDto(it, row[T.devolucaoNumero], row[T.devolucaoConfirmada] == true) }
        val complemento = row[T.complementoNunota]?.let { NotaDerivadaDto(it, row[T.complementoNumero], row[T.complementoConfirmado] == true) }
        val alertas = buildList {
            if (statusNota == "L") {
                val pendentes = ajustes.filter { it.status !in setOf("confirmado", "cancelado") }
                // Desde a confirmação (quem confirmou, do addon); sem ela, desde que a compra entrou no histórico.
                val desde = confirmadoEm(row[T.confirmadoEm]) ?: row[T.criadoEm]
                val atrasado = pendentes.any { it.status == "erro" } ||
                    (pendentes.isNotEmpty() && agora.epochSecond - desde.epochSecond >= AJUSTE_ATRASADO_SEG)
                if (atrasado) add(AlertaAuditoriaDto("ajuste", "Ajuste de peso não emitido" + (pendentes.firstNotNullOfOrNull { it.erro }?.let { ": $it" } ?: "")))
            }
            if (viaAddon && statusNota != "X" && !row[T.registradoAddon]) {
                add(AlertaAuditoriaDto("addon", "Conferência não registrada no addon — se confirmada pela Central, não fica registrado quem confirmou"))
            }
            if (devolucao != null && !devolucao.confirmada) add(AlertaAuditoriaDto("devolucao", "Devolução ${devolucao.numero ?: devolucao.nunota} não confirmada"))
            if (complemento != null && !complemento.confirmada) add(AlertaAuditoriaDto("complemento", "Complemento ${complemento.numero ?: complemento.nunota} não confirmado"))
            if (statusNota == "A" && agora.epochSecond - row[T.criadoEm].epochSecond >= PARADA_SEG) {
                add(AlertaAuditoriaDto("parada", "Conferida e não confirmada há mais de 24 horas"))
            }
        }
        return RecebimentoAuditoriaDto(
            nunota = row[T.nunota].toLong(),
            nuconf = row[T.nuconf],
            numeroNota = row[T.numeroNota],
            fornecedor = row[T.fornecedor],
            empresa = row[T.empresa],
            tipoOperacao = row[T.tipoOperacao],
            dataMovimento = row[T.dataMovimento],
            conferente = row[T.conferente],
            conferenciaInicio = row[T.conferenciaInicio],
            conferenciaFim = row[T.conferenciaFim],
            statusNota = statusNota,
            confirmadoPor = row[T.confirmadoNome] ?: row[T.confirmadoCodusu]?.toString(),
            confirmadoOrigem = row[T.confirmadoOrigem],
            confirmadoEm = row[T.confirmadoEm],
            ajustes = ajustes.map { AjustePesoDto(it.sentido, it.status, it.nunotaAjuste, it.erro) },
            devolucao = devolucao,
            complemento = complemento,
            alertas = alertas,
            encerrado = row[T.encerrado],
            lidoEm = row[T.lidoEm]?.toString(),
        )
    }
}
