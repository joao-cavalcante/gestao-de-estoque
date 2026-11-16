package wms.backend.galpao

import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

/**
 * Regras analíticas do Negri Planta Log 3D (Analytics.java / SyncService.analyse) — cálculo puro, sem banco.
 * ABC calcula o total completo antes de qualquer paginação; demanda zero vira N/D; dado ausente não é inventado.
 */
object GalpaoAnalises {

    enum class MetricaAbc { VALOR, QUANTIDADE, FREQUENCIA }

    data class LinhaVenda(val codprod: Int, val valor: Double, val quantidade: Double, val frequencia: Int, val ultimaVenda: LocalDate?)

    data class ResultadoAbc(val classe: String, val participacao: Double?, val acumulado: Double?)

    /** Classe ABC por produto: ordena pela métrica (desc, empate pelo código), acumula e corta em [limiteA]/[limiteB] (%). */
    fun abc(vendas: List<LinhaVenda>, metrica: MetricaAbc, limiteA: Double, limiteB: Double): Map<Int, ResultadoAbc> {
        require(limiteA >= 0 && limiteA < limiteB && limiteB <= 100) { "Limites ABC inválidos" }
        val valor = { v: LinhaVenda ->
            when (metrica) {
                MetricaAbc.VALOR -> v.valor
                MetricaAbc.QUANTIDADE -> v.quantidade
                MetricaAbc.FREQUENCIA -> v.frequencia.toDouble()
            }.coerceAtLeast(0.0)
        }
        val ordenadas = vendas.sortedWith(compareByDescending<LinhaVenda> { valor(it) }.thenBy { it.codprod })
        val total = ordenadas.sumOf { valor(it) }
        var acumulado = 0.0
        return ordenadas.associate { v ->
            val x = valor(v)
            acumulado += x
            val part = if (total > 0) x / total * 100 else null
            val acum = if (total > 0) acumulado / total * 100 else null
            val classe = when {
                acum == null || x == 0.0 -> "N/D"
                acum <= limiteA + 1e-9 -> "A"
                acum <= limiteB + 1e-9 -> "B"
                else -> "C"
            }
            v.codprod to ResultadoAbc(classe, part, acum)
        }
    }

    data class Cobertura(val disponivel: Double, val demandaDia: Double, val dias: Double?, val origem: String)

    /** Cobertura em dias calendário: (estoque − reservado) / (vendido no período / dias). Demanda zero = N/D. */
    fun cobertura(estoque: Double, reservado: Double, vendido: Double, dias: Int): Cobertura {
        require(dias >= 1) { "Período deve ter dias calendário positivos" }
        val demanda = vendido / dias
        val disp = estoque - reservado
        return if (demanda <= 0) Cobertura(disp, demanda, null, "N/D") else Cobertura(disp, demanda, disp / demanda, "DERIVADO")
    }

    /** Faixa de validade: VENCIDO, ATE_30, 31_90, ACIMA_90 ou N/D (sem data — não inventa). */
    fun validade(vencimento: LocalDate?, hoje: LocalDate): String {
        if (vencimento == null) return "N/D"
        val d = ChronoUnit.DAYS.between(hoje, vencimento)
        return when {
            d < 0 -> "VENCIDO"
            d <= 30 -> "ATE_30"
            d <= 90 -> "31_90"
            else -> "ACIMA_90"
        }
    }

    data class Idade(val dias: Long?, val origem: String)

    /** Idade pela entrada do lote; sem ela, pela última compra (selo APROXIMADO); sem nenhuma, N/D. */
    fun idade(entrada: LocalDate?, ultimaCompra: LocalDate?, hoje: LocalDate): Idade {
        val e = entrada?.takeIf { !it.isAfter(hoje) }
        val c = ultimaCompra?.takeIf { !it.isAfter(hoje) }
        return when {
            e != null -> Idade(ChronoUnit.DAYS.between(e, hoje), "EXATO")
            c != null -> Idade(ChronoUnit.DAYS.between(c, hoje), "APROXIMADO — última compra")
            else -> Idade(null, "N/D")
        }
    }

    /** Componentes e pesos da saúde (soma 100). */
    val PESOS_SAUDE = linkedMapOf("CONSISTENCIA" to 25, "COBERTURA" to 25, "RECENCIA" to 15, "VALIDADE" to 20, "ENDERECO" to 15)

    /** Score = 100 × pontos obtidos / pesos disponíveis; componente sem dado sai da conta e baixa a confiança. */
    fun saude(qualidade: Map<String, Double?>): SaudeDto {
        var pontos = 0.0
        var disponivel = 0
        val partes = PESOS_SAUDE.map { (k, peso) ->
            val v = qualidade[k]?.coerceIn(0.0, 1.0)
            if (v != null) {
                pontos += v * peso
                disponivel += peso
            }
            ComponenteSaudeDto(k, peso, v, v?.let { it * peso })
        }
        val score = if (disponivel == 0) null else (pontos * 100 / disponivel).roundToInt()
        val faixa = when {
            score == null -> "N/D"
            score < 40 -> "CRÍTICO"
            score < 70 -> "ATENÇÃO"
            score < 90 -> "SAUDÁVEL"
            else -> "EXCELENTE"
        }
        return SaudeDto(score, disponivel / 100.0, faixa, "100 × pontos obtidos / pesos disponíveis", partes)
    }

    /** Qualidade da cobertura: negativa 0; abaixo do mínimo 0,2; acima do máximo 0,4; dentro da faixa 1. */
    fun qualidadeCobertura(dias: Double?, minimo: Double, maximo: Double): Double? = when {
        dias == null -> null
        dias < 0 -> 0.0
        dias < minimo -> 0.2
        dias > maximo -> 0.4
        else -> 1.0
    }

    /** Qualidade da validade: vencido 0; até 30 dias 0,3; até 90 dias 0,7; depois 1; sem data N/D. */
    fun qualidadeValidade(vencimento: LocalDate?, hoje: LocalDate): Double? = when {
        vencimento == null -> null
        vencimento.isBefore(hoje) -> 0.0
        vencimento.isBefore(hoje.plusDays(30)) -> 0.3
        vencimento.isBefore(hoje.plusDays(90)) -> 0.7
        else -> 1.0
    }
}

@Serializable
data class ComponenteSaudeDto(val componente: String, val peso: Int, val qualidade: Double?, val pontos: Double?)

@Serializable
data class SaudeDto(
    val score: Int?,
    /** Fração dos pesos com dado (0–1). */
    val confianca: Double,
    val faixa: String,
    val formula: String,
    val componentes: List<ComponenteSaudeDto>,
)

/** Uma linha da análise de produtos (tela Produtos e modos de cor da visão). Tudo do empresa/local da planta. */
@Serializable
data class ProdutoAnaliseDto(
    val codprod: Int,
    val descricao: String,
    val ativo: Boolean,
    val codvol: String?,
    val codgrupo: Int?,
    val grupo: String?,
    /** AD_TIPOSEPARACAO do produto (1 Seco, 2 Refrigerado, 3 Congelado); null = sem setor. */
    val setor: Int?,
    val setorOrigem: String,
    val pesoBruto: Double?,
    val m3: Double?,
    val valorVendido: Double,
    val quantidadeVendida: Double,
    val frequencia: Int,
    val ultimaVenda: String?,
    val abc: String,
    val participacao: Double?,
    val acumulado: Double?,
    val abcQtd: String,
    val participacaoQtd: Double?,
    val acumuladoQtd: Double?,
    val abcFreq: String,
    val participacaoFreq: Double?,
    val acumuladoFreq: Double?,
    val estoque: Double,
    val reservado: Double,
    val disponivel: Double,
    /** Soma dos saldos nos endereços desta planta. */
    val enderecado: Double,
    val demandaDia: Double,
    val cobertura: Double?,
    val coberturaOrigem: String,
    val dtval: String?,
    val validade: String,
    /** Tem estoque e não vendeu na janela. */
    val parado: Boolean,
    val idadeDias: Long?,
    val idadeOrigem: String,
    val saude: SaudeDto,
    val origem: String = "DERIVADO",
)

/** Validade de um lote (produto + controle) — a visão usa a menor data entre os lotes do endereço. */
@Serializable
data class ValidadeLoteDto(val codprod: Int, val controle: String, val dtval: String)

@Serializable
data class ParametrosAnaliseDto(
    val dias: Int = 90,
    val limiteA: Double = 80.0,
    val limiteB: Double = 95.0,
    val coberturaMinDias: Double = 7.0,
    val coberturaMaxDias: Double = 90.0,
    /** Produto sem AD_TIPOSEPARACAO herda o do grupo (no Planta Log começa desligado). */
    val setorPeloGrupo: Boolean = false,
    /** TIPMOV das notas usadas como "última compra" na idade aproximada. */
    val tipmovCompra: List<String> = listOf("C"),
    /** Prazo de tratamento dos alertas (ALERT / SLA_HOURS do Planta Log). */
    val slaAlertaHoras: Int = 24,
)

@Serializable
data class AnalisesPlantaDto(
    val lidoEm: String,
    val parametros: ParametrosAnaliseDto,
    val produtos: List<ProdutoAnaliseDto>,
    val validadesLote: List<ValidadeLoteDto>,
    val aviso: String? = null,
)
