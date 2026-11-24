package wms.backend.cargas

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.selectAll
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
import java.time.ZoneId
import java.time.format.DateTimeFormatter
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

    fun painel(claims: ClaimsToken, tenantId: UUID, tenantSlug: String, diaSaida: LocalDate): CargasDto {
        val agora = Instant.now()
        val hoje = LocalDate.now(ZONA)
        val ocAtual = NotasFiscaisPedido.ocAtualDoCache(tenantId)
        val restritas = if (PermissoesRecurso.acessoTotal(claims)) emptyMap() else PermissoesRecurso.topsRestritas(tenantId)

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

        // Só o que pode aparecer: OC não fechada (situação do cache) ou fechada pela Torre nas últimas 48 h.
        val transporteCache = TransporteOrdemCarga.doCache(tenantSlug, tenantId, linhas.map { it.ordemCarga })
        val candidatos = linhas.filter { p ->
            val fechada = transporteCache[p.ordemCarga]?.situacao == "F"
            if (fechada || p.ordemCarga in comFechamento) p.ordemCarga in comFechamento
            else p.statusOperacional !in CONFERIDOS || p.dataNegociacao?.let { !it.isBefore(hoje.minusDays(DIAS_PRONTA)) } == true
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
                pesoMaxKg = cap?.first,
                saidaPrevista = cap?.second,
            )
        }

        return CargasDto(
            atualizadoEm = agora.toString(),
            diaSaida = diaSaida.toString(),
            cargas = CargasRegras.montar(pedidos, fechamentos, transporte, diaSaida, hoje, ZONA),
        )
    }

    fun dia(param: String?): LocalDate {
        val hoje = LocalDate.now(ZONA)
        return when (param) {
            null, "", "hoje" -> hoje
            "ontem" -> hoje.minusDays(1)
            else -> runCatching { LocalDate.parse(param) }.getOrDefault(hoje)
        }
    }

    private val CONFERIDOS = setOf("concluido", "concluido_divergente", "recontagem_concluida", "recontagem_concluida_divergente")

    /**
     * TGFORD.PESOMAX e DTPREVSAIDA por OC — cache de 10 min; o que falta é buscado em segundo plano (aparece no
     * próximo poll). Falha no Sankhya só deixa a tela sem capacidade/saída prevista.
     */
    private object Capacidade {
        private val TTL: Duration = Duration.ofMinutes(10)
        private val escopo = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val cache = ConcurrentHashMap<Pair<UUID, Long>, Pair<Instant, Pair<Double?, LocalDate?>>>()
        private val emVoo = ConcurrentHashMap.newKeySet<Pair<UUID, Long>>()

        fun doCache(tenantSlug: String, tenantId: UUID, ocs: List<Long>): Map<Long, Pair<Double?, LocalDate?>> {
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
                                "SELECT ORDEMCARGA, MAX(PESOMAX) AS PESOMAX, TO_CHAR(MIN(DTPREVSAIDA), 'YYYY-MM-DD') AS SAIDA " +
                                    "FROM TGFORD WHERE ORDEMCARGA IN (${lote.joinToString()}) GROUP BY ORDEMCARGA",
                            ).forEach { r ->
                                val oc = r["ORDEMCARGA"]?.toBigDecimalOrNull()?.toLong() ?: return@forEach
                                val peso = r["PESOMAX"]?.toDoubleOrNull()?.takeIf { it > 0 }
                                val saida = r["SAIDA"]?.trim()?.takeIf { it.isNotEmpty() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                                cache[tenantId to oc] = agora to (peso to saida)
                                achadas += oc
                            }
                            (lote - achadas).forEach { cache[tenantId to it] = agora to (null to null) }
                        }
                    } catch (e: Exception) {
                        println("AVISO: Cargas - capacidade/saída prevista das OCs $faltando (tenant $tenantSlug): ${e.message}")
                    } finally {
                        faltando.forEach { emVoo.remove(tenantId to it) }
                    }
                }
            }
            return ocs.mapNotNull { oc -> cache[tenantId to oc]?.second?.let { oc to it } }.toMap()
        }
    }
}
