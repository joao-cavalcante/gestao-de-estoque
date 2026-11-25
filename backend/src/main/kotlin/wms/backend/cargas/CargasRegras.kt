package wms.backend.cargas

import java.time.Instant
import java.time.LocalDate

/**
 * Regra da tela Cargas (24/11/2026) — situação de cada Ordem de Carga, sem I/O (testável). DECISÃO DE PRODUTO
 * (usuário, 24/11/2026): a OC "saiu para a entrega" quando alguém clica em Fechar OC.
 *
 *  - pendente: algum pedido a conferir, em conferência, com corte/liberação aguardando ou a carregar;
 *  - pronta:   tudo conferido e carregado — falta só o Fechar OC;
 *  - saiu:     OC fechada; hora e quem fechou = 1º job de "fechamento" da impressão automática (app.impressao_oc).
 */
object CargasRegras {

    /** Situação do pedido dentro da OC (a tela traduz em rótulo e cor). */
    enum class SituacaoPedido(val codigo: String, val pendente: Boolean) {
        CORTE("corte", true),
        A_CONFERIR("a_conferir", true),
        EM_CONFERENCIA("em_conferencia", true),
        A_CARREGAR("a_carregar", true),
        CARREGADO("carregado", false),
        COM_NOTA("com_nota", false),
    }

    data class Pedido(
        val nunota: Long,
        val ordemCarga: Long,
        val statusOperacional: String,
        val codigoParceiro: String?,
        val cliente: String?,
        val pesoKg: Double,
        val volumes: Int?,
        val express: Boolean,
        val retira: Boolean,
        /** DTNEG — só para descartar OC aberta antiga sem pendência. */
        val dataNegociacao: LocalDate?,
        /** Itens conferidos / já checados como carregados (null = sem checklist: sai carregado ao concluir). */
        val carregamentoTotal: Int? = null,
        val carregamentoFeito: Int? = null,
        val nota: NotaResumo? = null,
        /** TGFCAB.AD_TURNOENTREGA — D diurno, N noturno (null = não informado). */
        val turno: String? = null,
    )

    data class NotaResumo(val numero: Long?, val situacao: String, val statusNfe: String?, val confirmada: Boolean)

    data class Fechamento(val ordemCarga: Long, val criadoEm: Instant, val solicitadoPor: String?, val motivo: String, val status: String, val erro: String?, val impressoEm: Instant?)

    data class Transporte(
        val motorista: String?,
        val placa: String?,
        val veiculo: String?,
        val fechada: Boolean,
        val pesoMaxKg: Double?,
        val saidaPrevista: LocalDate?,
        /** TSIREG.NOMEREG da OC (TGFORD.CODREG) — "PINDAMONHANGABA". */
        val regiao: String? = null,
        /** TGFORD.DTALTER — na OC fechada é a hora do fechamento (bate no segundo com o Fechar OC da Torre, 24/11). */
        val alteradaEm: Instant? = null,
    )

    private val EM_CONFERENCIA = setOf("andamento", "recontagem_andamento", "aguardando_finalizacao")
    private val A_CONFERIR = setOf("aguardando", "aguardando_recontagem")
    private val CORTE = setOf("aguardando_corte", "aguardando_liberacao")

    /** OC aberta em que todo pedido já tem nota (faturada fora da Torre) só aparece até este número de dias. */
    const val DIAS_OC_SEM_PENDENCIA = 3L

    fun situacaoPedido(p: Pedido): SituacaoPedido = when {
        p.statusOperacional in CORTE -> SituacaoPedido.CORTE
        p.statusOperacional in EM_CONFERENCIA -> SituacaoPedido.EM_CONFERENCIA
        p.statusOperacional in A_CONFERIR -> SituacaoPedido.A_CONFERIR
        p.carregamentoTotal != null && (p.carregamentoFeito ?: 0) < p.carregamentoTotal -> SituacaoPedido.A_CARREGAR
        p.nota?.confirmada == true -> SituacaoPedido.COM_NOTA
        else -> SituacaoPedido.CARREGADO
    }

    fun montar(
        pedidos: List<Pedido>,
        fechamentos: List<Fechamento>,
        transporte: Map<Long, Transporte>,
        /** Saídas (Fechar OC) a partir deste instante entram — a tela recorta o dia/turno. */
        saidasDesde: Instant,
        hoje: LocalDate,
        /** Quando cada OC ficou pronta (último item carregado / conferência concluída). */
        prontaDesde: Map<Long, Instant> = emptyMap(),
    ): List<CargaDto> {
        val primeiroFechamento = fechamentos.filter { it.motivo == "fechamento" }.groupBy { it.ordemCarga }
            .mapValues { (_, l) -> l.minBy { it.criadoEm } }
        val ultimoJob = fechamentos.groupBy { it.ordemCarga }.mapValues { (_, l) -> l.maxBy { it.criadoEm } }

        val cargas = pedidos.groupBy { it.ordemCarga }.mapNotNull { (oc, doGrupo) ->
            val t = transporte[oc]
            val fechamento = primeiroFechamento[oc]
            val situacoes = doGrupo.associate { it.nunota to situacaoPedido(it) }
            val situacao = when {
                t?.fechada == true || fechamento != null -> {
                    // Fechada direto no Sankhya (sem Fechar OC da Torre): a hora vem do TGFORD.DTALTER.
                    val quando = fechamento?.criadoEm ?: t?.alteradaEm ?: return@mapNotNull null
                    if (quando.isBefore(saidasDesde)) return@mapNotNull null
                    "saiu"
                }
                situacoes.values.any { it.pendente } -> "pendente"
                else -> {
                    val esperandoFechar = situacoes.values.any { it == SituacaoPedido.CARREGADO }
                    val recente = doGrupo.any { p -> p.dataNegociacao?.let { !it.isBefore(hoje.minusDays(DIAS_OC_SEM_PENDENCIA)) } == true }
                    if (!esperandoFechar && !recente) return@mapNotNull null
                    "pronta"
                }
            }
            val job = ultimoJob[oc]
            val notas = doGrupo.mapNotNull { it.nota }
            CargaDto(
                ordemCarga = oc,
                situacao = situacao,
                motorista = t?.motorista,
                placa = t?.placa,
                veiculo = t?.veiculo,
                saidaPrevista = t?.saidaPrevista?.toString(),
                pesoMaxKg = t?.pesoMaxKg,
                pesoKg = arredondar(doGrupo.sumOf { it.pesoKg }),
                volumes = doGrupo.mapNotNull { it.volumes }.takeIf { it.isNotEmpty() }?.sum(),
                clientes = doGrupo.map { it.codigoParceiro ?: it.cliente ?: it.nunota.toString() }.toSet().size,
                prontos = situacoes.values.count { !it.pendente },
                express = doGrupo.any { it.express },
                retira = doGrupo.any { it.retira },
                turnos = doGrupo.mapNotNull { it.turno }.distinct().sorted(),
                faltas = FaltasDto(
                    corte = situacoes.values.count { it == SituacaoPedido.CORTE },
                    aConferir = situacoes.values.count { it == SituacaoPedido.A_CONFERIR },
                    emConferencia = situacoes.values.count { it == SituacaoPedido.EM_CONFERENCIA },
                    aCarregar = situacoes.values.count { it == SituacaoPedido.A_CARREGAR },
                ),
                saiuEm = if (situacao == "saiu") (fechamento?.criadoEm ?: t?.alteradaEm)?.toString() else null,
                fechadaPor = if (situacao == "saiu") fechamento?.solicitadoPor else null,
                fechadaNoSankhya = situacao == "saiu" && fechamento == null,
                regiao = t?.regiao,
                prontaDesde = if (situacao == "pronta") prontaDesde[oc]?.toString() else null,
                impressao = if (situacao == "saiu" && job != null) ImpressaoResumoDto(job.status, job.erro, job.impressoEm?.toString()) else null,
                notas = NotasResumoDto(
                    total = notas.size,
                    aprovadas = notas.count { it.statusNfe == "A" },
                    problema = notas.count { it.statusNfe in setOf("R", "D", "V") },
                    aguardando = notas.count { it.statusNfe !in setOf("A", "R", "D", "V") },
                ),
                pedidos = doGrupo
                    .sortedWith(compareBy<Pedido> { situacoes.getValue(it.nunota).ordinal }.thenBy { it.cliente ?: "" })
                    .map { p ->
                        PedidoCargaDto(
                            nunota = p.nunota,
                            cliente = p.cliente,
                            pesoKg = arredondar(p.pesoKg),
                            volumes = p.volumes,
                            situacao = situacoes.getValue(p.nunota).codigo,
                            express = p.express,
                            retira = p.retira,
                            turno = p.turno,
                            notaNumero = p.nota?.numero,
                            notaSituacao = p.nota?.situacao,
                            notaStatusNfe = p.nota?.statusNfe,
                        )
                    },
            )
        }
        // Pendentes e prontas: saída prevista mais cedo, depois a OC mais antiga. Saíram: a mais recente primeiro.
        return cargas.sortedWith(
            compareBy<CargaDto> { if (it.situacao == "saiu") 1 else 0 }
                .thenBy { if (it.situacao == "saiu") 0L else (it.saidaPrevista?.let { d -> LocalDate.parse(d).toEpochDay() } ?: Long.MAX_VALUE) }
                .thenByDescending { it.saiuEm ?: "" }
                .thenBy { it.ordemCarga },
        )
    }

    private fun arredondar(kg: Double): Double = Math.round(kg * 10.0) / 10.0
}
