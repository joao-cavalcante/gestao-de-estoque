package wms.backend.cargas

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import wms.backend.reconferencia.ReconferenciaChecksTable
import wms.backend.separacao.SeparacaoSessoesTable
import wms.backend.separacao.SeparacaoStatus
import wms.backend.tenancy.SyncEstadoTable
import wms.backend.auth.ClaimsToken
import wms.backend.erp.SankhyaDbExplorerClient
import wms.backend.impressao.ImpressaoOcRepository
import wms.backend.mapaseparacao.TransporteOrdemCarga
import wms.backend.permissoes.PermissoesRecurso
import wms.backend.tarefas.NotasFiscaisPedido
import wms.backend.tarefas.TarefasRepository
import wms.backend.tarefas.TarefasTable
import wms.backend.tenancy.TenantTx
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Tela Cargas (24/11/2026): monta a situação das OCs com o que a Torre já tem — espelho local dos pedidos
 * (app.tarefas), carregamento, nota fiscal do pedido (cache do sync), motorista/veículo/situação da OC (cache de
 * transporte) e o registro do Fechar OC (app.impressao_oc). A requisição não espera o Sankhya: capacidade e saída
 * prevista da OC (TGFORD.PESOMAX / DTPREVSAIDA) vêm de um cache próprio, atualizado em segundo plano.
 */
object CargasService {
    private val ZONA = ZoneId.of("America/Sao_Paulo")
    private val DTNEG = DateTimeFormatter.ofPattern("dd/MM/yyyy")
    private const val DIAS_CARREGAMENTO = 7L
    private const val HORAS_FECHAMENTOS = 48L
    /** OC aberta só com pedidos carregados/sem nota aparece em "prontas" se tiver pedido destes últimos dias. */
    private const val DIAS_PRONTA = 15L
    /** OC fechada direto no Sankhya entra se tiver pedido destes últimos dias (limita a consulta ao TGFORD). */
    private const val DIAS_FECHADA_SANKHYA = 4L
    /** Saídas devolvidas: últimas 36 h — cobre "hoje" e o turno da noite que começou ontem às 22h. */
    private const val HORAS_SAIDAS = 36L

    fun painel(claims: ClaimsToken, tenantId: UUID, tenantSlug: String): CargasDto {
        val agora = Instant.now()
        val hoje = LocalDate.now(ZONA)
        val ocAtual = NotasFiscaisPedido.ocAtualDoCache(tenantId)
        val restritas = if (PermissoesRecurso.acessoTotal(claims)) emptyMap() else PermissoesRecurso.topsRestritas(tenantId)
        val empresas = wms.backend.permissoes.EmpresasAcesso.permitidas(claims)

        val linhas = TenantTx.run(tenantId) {
            TarefasTable.selectAll().where { TarefasTable.tenantId eq tenantId }.map { r ->
                Triple(r[TarefasTable.nunota].toLong(), r[TarefasTable.statusOperacional], r[TarefasTable.dados])
            }
        }.mapNotNull { (nunota, status, bruto) ->
            val dados = runCatching { Json.parseToJsonElement(bruto) as JsonObject }.getOrNull() ?: return@mapNotNull null
            fun campo(nome: String) = dados[nome]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
            val tipMov = campo("TIPMOV")?.uppercase()
            if (tipMov == "C" || tipMov == "O") return@mapNotNull null // compra fica na Entrada
            // OC atual do pedido (o espelho guarda a antiga se o pedido trocou de OC depois de conferido).
            val oc = ocAtual[nunota]?.takeIf { it > 0 } ?: if (ocAtual.containsKey(nunota)) null else TarefasRepository.normalizarOrdemCarga(campo("ORDEMCARGA"))
            if (oc == null) return@mapNotNull null
            if (restritas.isNotEmpty() && !PermissoesRecurso.podeUsarTop(claims, restritas, campo("CODTIPOPER")?.toIntOrNull())) return@mapNotNull null
            if (!wms.backend.permissoes.EmpresasAcesso.permite(empresas, wms.backend.permissoes.EmpresasAcesso.codemp(campo("CODEMP")))) return@mapNotNull null
            CargasRegras.Pedido(
                nunota = nunota,
                ordemCarga = oc,
                statusOperacional = status,
                codigoParceiro = campo("CODPARC"),
                cliente = campo("Parceiro.NOMEPARC"),
                pesoKg = TarefasRepository.pesoBrutoKg(dados),
                volumes = campo("QTDVOL")?.let { it.toDoubleOrNull() ?: it.replace(".", "").replace(',', '.').toDoubleOrNull() }?.toInt(),
                express = campo("AD_EXPRESS")?.uppercase() == "S",
                retira = campo("AD_RETIRA")?.uppercase() == "S",
                turno = campo("AD_TURNOENTREGA")?.uppercase()?.takeIf { it == "D" || it == "N" },
                dataNegociacao = campo("DTNEG")?.take(10)?.let { runCatching { LocalDate.parse(it, DTNEG) }.getOrNull() },
            )
        }

        val fechamentos = ImpressaoOcRepository.recentes(tenantId, HORAS_FECHAMENTOS, 500).map {
            CargasRegras.Fechamento(
                ordemCarga = it.ordemCarga,
                criadoEm = Instant.parse(it.criadoEm),
                solicitadoPor = it.solicitadoPor,
                motivo = it.motivo,
                status = it.status,
                erro = it.erro,
                impressoEm = it.impressoEm?.let { i -> runCatching { Instant.parse(i) }.getOrNull() },
            )
        }
        val comFechamento = fechamentos.filter { it.motivo == "fechamento" }.map { it.ordemCarga }.toSet()

        // Só o que pode aparecer: OC aberta, fechada pela Torre nas últimas 48 h ou fechada direto no Sankhya com
        // pedido recente (a hora do fechamento vem do TGFORD.DTALTER; limitado para não consultar a história toda).
        val transporteCache = TransporteOrdemCarga.doCache(tenantSlug, tenantId, linhas.map { it.ordemCarga })
        val candidatos = linhas.filter { p ->
            val fechada = transporteCache[p.ordemCarga]?.situacao == "F"
            val recente = p.dataNegociacao?.let { !it.isBefore(hoje.minusDays(DIAS_PRONTA)) } == true
            when {
                p.ordemCarga in comFechamento -> true
                fechada -> p.dataNegociacao?.let { !it.isBefore(hoje.minusDays(DIAS_FECHADA_SANKHYA)) } == true
                else -> p.statusOperacional !in CONFERIDOS || recente
            }
        }

        val conferidos = candidatos.filter { it.statusOperacional in CONFERIDOS }.map { it.nunota }
        val carregamento = if (conferidos.isEmpty()) emptyMap()
        else wms.backend.reconferencia.ReconferenciaService.resumoCarregamento(tenantId, conferidos, DIAS_CARREGAMENTO)
        val notas = NotasFiscaisPedido.doCache(tenantId)
        val pedidos = candidatos.map { p ->
            val c = carregamento[p.nunota]
            val n = notas[p.nunota]
            p.copy(
                carregamentoTotal = c?.total,
                carregamentoFeito = c?.carregados,
                nota = n?.let { CargasRegras.NotaResumo(it.numero, it.situacao, it.statusNfe, it.confirmada) },
            )
        }

        val ocs = pedidos.map { it.ordemCarga }.distinct()
        val capacidade = Capacidade.doCache(tenantSlug, tenantId, ocs)
        val transporte = ocs.associateWith { oc ->
            val t = transporteCache[oc]
            val cap = capacidade[oc]
            CargasRegras.Transporte(
                motorista = t?.motorista,
                placa = t?.placa,
                veiculo = t?.veiculo,
                fechada = t?.situacao == "F",
                pesoMaxKg = cap?.pesoMax,
                saidaPrevista = cap?.saida,
                regiao = cap?.regiao,
                alteradaEm = cap?.alteradaEm,
            )
        }

        val saidasDesde = agora.minus(Duration.ofHours(HORAS_SAIDAS))
        return CargasDto(
            atualizadoEm = agora.toString(),
            saidasDesde = saidasDesde.toString(),
            sincronizadoEm = runCatching {
                transaction { SyncEstadoTable.selectAll().where { SyncEstadoTable.tenantId eq tenantId }.singleOrNull() }
                    ?.get(SyncEstadoTable.ultimoSyncSucessoEm)?.toString()
            }.getOrNull(),
            cargas = CargasRegras.montar(pedidos, fechamentos, transporte, saidasDesde, hoje, prontaDesde(tenantId, pedidos)),
        )
    }

    /**
     * Desde quando cada OC está pronta: o último "carregado" do checklist ou a última conferência concluída dos pedidos
     * dela (o que vier por último). Só leitura local.
     */
    private fun prontaDesde(tenantId: UUID, pedidos: List<CargasRegras.Pedido>): Map<Long, Instant> {
        val conferidos = pedidos.filter { it.statusOperacional in CONFERIDOS }
        if (conferidos.isEmpty()) return emptyMap()
        val ocDoPedido = conferidos.associate { it.nunota to it.ordemCarga }
        return TenantTx.run(tenantId) {
            val sessoes = SeparacaoSessoesTable.selectAll()
                .where {
                    (SeparacaoSessoesTable.tenantId eq tenantId) and (SeparacaoSessoesTable.status inList listOf(SeparacaoStatus.CONCLUIDA, SeparacaoStatus.PRONTA)) and
                        (SeparacaoSessoesTable.nunota inList ocDoPedido.keys.map { it.toInt() })
                }
                .map { Triple(it[SeparacaoSessoesTable.id], it[SeparacaoSessoesTable.nunota].toLong(), it[SeparacaoSessoesTable.atualizadoEm]) }
            val porSessao = sessoes.associate { it.first to it.second }
            val r = HashMap<Long, Instant>()
            fun marcar(nunota: Long, em: Instant) {
                val oc = ocDoPedido[nunota] ?: return
                if (r[oc]?.isBefore(em) != false) r[oc] = em
            }
            sessoes.forEach { (_, nunota, em) -> marcar(nunota, em) }
            if (porSessao.isNotEmpty()) {
                ReconferenciaChecksTable.selectAll()
                    .where { (ReconferenciaChecksTable.tenantId eq tenantId) and (ReconferenciaChecksTable.sessaoId inList porSessao.keys) }
                    .forEach { c -> porSessao[c[ReconferenciaChecksTable.sessaoId]]?.let { marcar(it, c[ReconferenciaChecksTable.checadoEm]) } }
            }
            r
        }
    }

    private val CONFERIDOS = setOf("concluido", "concluido_divergente", "recontagem_concluida", "recontagem_concluida_divergente")

    data class DadosOc(val pesoMax: Double?, val saida: LocalDate?, val regiao: String?, val alteradaEm: Instant?)

    /**
     * TGFORD (PESOMAX, DTPREVSAIDA, DTALTER) + TSIREG.NOMEREG por OC — cache de 10 min; o que falta é buscado em
     * segundo plano (aparece no próximo poll). Falha no Sankhya só deixa a tela sem esses dados.
     */
    private object Capacidade {
        private val TTL: Duration = Duration.ofMinutes(10)
        private val escopo = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val cache = ConcurrentHashMap<Pair<UUID, Long>, Pair<Instant, DadosOc>>()
        private val emVoo = ConcurrentHashMap.newKeySet<Pair<UUID, Long>>()
        private val DATA_HORA = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

        fun doCache(tenantSlug: String, tenantId: UUID, ocs: List<Long>): Map<Long, DadosOc> {
            val limite = Instant.now().minus(TTL)
            val faltando = ocs.filter { oc -> cache[tenantId to oc]?.first?.isAfter(limite) != true }
                .filter { emVoo.add(tenantId to it) }
            if (faltando.isNotEmpty()) {
                escopo.launch {
                    try {
                        for (lote in faltando.chunked(500)) {
                            val agora = Instant.now()
                            val achadas = mutableSetOf<Long>()
                            SankhyaDbExplorerClient.executarQuery(
                                tenantSlug,
                                "SELECT O.ORDEMCARGA, MAX(O.PESOMAX) AS PESOMAX, TO_CHAR(MIN(O.DTPREVSAIDA), 'YYYY-MM-DD') AS SAIDA, " +
                                    "MAX(R.NOMEREG) AS REGIAO, TO_CHAR(MAX(O.DTALTER), 'YYYY-MM-DD HH24:MI:SS') AS ALTERADA " +
                                    "FROM TGFORD O LEFT JOIN TSIREG R ON R.CODREG = O.CODREG " +
                                    "WHERE O.ORDEMCARGA IN (${lote.joinToString()}) GROUP BY O.ORDEMCARGA",
                            ).forEach { r ->
                                val oc = r["ORDEMCARGA"]?.toBigDecimalOrNull()?.toLong() ?: return@forEach
                                cache[tenantId to oc] = agora to DadosOc(
                                    pesoMax = r["PESOMAX"]?.toDoubleOrNull()?.takeIf { it > 0 },
                                    saida = r["SAIDA"]?.trim()?.takeIf { it.isNotEmpty() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
                                    regiao = r["REGIAO"]?.trim()?.takeIf { it.isNotEmpty() },
                                    // DTALTER sai na hora local do Sankhya (= Brasília; conferido com o Fechar OC em 24/11).
                                    alteradaEm = r["ALTERADA"]?.trim()?.takeIf { it.isNotEmpty() }
                                        ?.let { runCatching { LocalDateTime.parse(it, DATA_HORA).atZone(ZONA).toInstant() }.getOrNull() },
                                )
                                achadas += oc
                            }
                            (lote - achadas).forEach { cache[tenantId to it] = agora to DadosOc(null, null, null, null) }
                        }
                    } catch (e: Exception) {
                        println("AVISO: Cargas - dados das OCs $faltando (tenant $tenantSlug): ${e.message}")
                    } finally {
                        faltando.forEach { emVoo.remove(tenantId to it) }
                    }
                }
            }
            return ocs.mapNotNull { oc -> cache[tenantId to oc]?.second?.let { oc to it } }.toMap()
        }
    }
}
