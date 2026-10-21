package wms.backend.impressao

import com.lowagie.text.Document
import com.lowagie.text.Element
import com.lowagie.text.PageSize
import com.lowagie.text.pdf.PdfWriter
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class RomaneioNota(
    val numNota: String,
    val nunota: Long,
    val pedido: String,
    val pedidoCliente: String,
    val codParc: String,
    val cliente: String,
    val cidade: String,
    val peso: BigDecimal,
    val volumes: Int,
    val valor: BigDecimal,
    /** Nota ainda não autorizada na hora da impressão — o DANFE dela sai depois, separado. */
    val pendenteSefaz: Boolean = false,
)

data class RomaneioOc(
    val empresa: String,
    val ordemCarga: Long,
    val prevSaida: String,
    val transportadora: String,
    val cnpjTransportadora: String,
    val placa: String,
    val veiculo: String,
    val motorista: String,
    val notas: List<RomaneioNota>,
)

/**
 * Romaneio da OC (A4 deitado) — mesmos campos do relatório "ORDEM CARGA VOLUMES WMS" do Sankhya,
 * mas com os volumes da conferência do WMS (TGFCON2.QTDVOL) em vez da TGWREV (WMS nativo, vazia na Negri).
 */
object RomaneioPdf {
    private val pagina = PageSize.A4.rotate()
    private const val ESQ = 20f
    private val LARG = pagina.width - 40f
    private const val TOPO_TABELA = 92f
    private const val ALT_LINHA = 15f
    private const val ALT_ASSINATURAS = 95f

    private data class Coluna(val rotulo: String, val largura: Float, val alinhamento: Int)

    private val colunas = listOf(
        Coluna("Nro.Nota", 52f, Element.ALIGN_CENTER),
        Coluna("N.Único", 50f, Element.ALIGN_CENTER),
        Coluna("Pedido", 50f, Element.ALIGN_CENTER),
        Coluna("P.Cliente", 60f, Element.ALIGN_CENTER),
        Coluna("Cliente", 292f, Element.ALIGN_LEFT),
        Coluna("Cidade", 140f, Element.ALIGN_LEFT),
        Coluna("Peso", 58f, Element.ALIGN_RIGHT),
        Coluna("Vols.", 36f, Element.ALIGN_CENTER),
        Coluna("Vlr Nota", 64f, Element.ALIGN_RIGHT),
    )

    fun desenhar(documento: Document, writer: PdfWriter, r: RomaneioOc) {
        val cabem = ((pagina.height - 20f - TOPO_TABELA - ALT_LINHA) / ALT_LINHA).toInt()
        // A última folha precisa de espaço pros totais (+ aviso de nota pendente) e as assinaturas.
        val cabemUltima = ((pagina.height - 20f - TOPO_TABELA - 3 * ALT_LINHA - ALT_ASSINATURAS) / ALT_LINHA).toInt()
        val folhas = mutableListOf<List<RomaneioNota>>()
        var resto = r.notas
        while (resto.size > cabemUltima) {
            folhas += resto.take(cabem)
            resto = resto.drop(cabem)
        }
        folhas += resto
        val agora = LocalDateTime.now(ZoneId.of("America/Sao_Paulo")).format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))

        folhas.forEachIndexed { i, notas ->
            documento.setPageSize(pagina)
            documento.newPage()
            writer.isPageEmpty = false
            val d = Desenho(writer.directContent, pagina.height)
            cabecalho(d, r, "Pág ${i + 1} de ${folhas.size}", agora)
            var t = tabelaCabecalho(d)
            notas.forEachIndexed { j, n ->
                if (j % 2 == 1) d.preencher(ESQ, t, LARG, ALT_LINHA, 0.93f)
                val cliente = (if (n.pendenteSefaz) "[AGUARDANDO SEFAZ] " else "") + "${n.codParc} - ${n.cliente}"
                val valores = listOf(
                    n.numNota + (if (n.pendenteSefaz) " *" else ""), n.nunota.toString(), n.pedido, n.pedidoCliente, cliente, n.cidade,
                    Formato.quantidade(n.peso, 2, 2), n.volumes.toString(), Formato.dinheiro(n.valor),
                )
                var x = ESQ
                colunas.forEachIndexed { k, c ->
                    d.texto(x + 3, t + 10.5f, c.largura - 6, valores[k], 8f, c.alinhamento, n.pendenteSefaz)
                    x += c.largura
                }
                t += ALT_LINHA
            }
            d.linha(ESQ, t, ESQ + LARG, t, 0.5f)
            if (i == folhas.lastIndex) {
                totais(d, r, t)
                assinaturas(d, r, pagina.height - 20f - ALT_ASSINATURAS)
            }
        }
    }

    private fun cabecalho(d: Desenho, r: RomaneioOc, folha: String, agora: String) {
        d.texto(ESQ, 32f, 300f, r.empresa, 11f, bold = true)
        d.texto(ESQ, 32f, LARG, "ROMANEIO DE CARGA", 14f, Element.ALIGN_CENTER, true)
        d.texto(ESQ + LARG - 200f, 28f, 200f, folha, 8f, Element.ALIGN_RIGHT)
        d.texto(ESQ + LARG - 200f, 38f, 200f, agora, 8f, Element.ALIGN_RIGHT)
        d.caixa(ESQ, 46f, LARG, 38f)
        fun par(x: Float, top: Float, w: Float, rotulo: String, valor: String) {
            d.texto(x, top, 70f, rotulo, 8f, bold = true)
            d.texto(x + 72f, top, w - 74f, valor, 9f)
        }
        par(ESQ + 6, 60f, 200f, "O.Carga:", r.ordemCarga.toString())
        par(ESQ + 6, 76f, 200f, "Prev.Saída:", Formato.data(r.prevSaida))
        par(ESQ + 210, 60f, 370f, "Transp.:", r.transportadora)
        par(ESQ + 210, 76f, 370f, "CNPJ Transp:", Formato.cnpjCpf(r.cnpjTransportadora))
        par(ESQ + 580, 60f, LARG - 586f, "Veículo:", listOf(r.placa, r.veiculo).filter { it.isNotBlank() }.joinToString(" - "))
        par(ESQ + 580, 76f, LARG - 586f, "Motorista:", r.motorista)
    }

    private fun tabelaCabecalho(d: Desenho): Float {
        d.preencher(ESQ, TOPO_TABELA, LARG, ALT_LINHA, 0.8f)
        d.caixa(ESQ, TOPO_TABELA, LARG, ALT_LINHA)
        var x = ESQ
        colunas.forEach { c ->
            d.texto(x + 3, TOPO_TABELA + 10.5f, c.largura - 6, c.rotulo, 8f, c.alinhamento, true)
            x += c.largura
        }
        return TOPO_TABELA + ALT_LINHA
    }

    private fun totais(d: Desenho, r: RomaneioOc, top: Float) {
        val peso = r.notas.fold(BigDecimal.ZERO) { a, n -> a + n.peso }
        val valor = r.notas.fold(BigDecimal.ZERO) { a, n -> a + n.valor }
        val vols = r.notas.sumOf { it.volumes }
        val xPeso = ESQ + colunas.take(6).sumOf { it.largura.toDouble() }.toFloat()
        d.texto(ESQ + 3, top + 11f, 400f, "TOTAL: ${r.notas.size} nota(s)", 8.5f, bold = true)
        d.texto(xPeso + 3, top + 11f, colunas[6].largura - 6, Formato.quantidade(peso, 2, 2), 8.5f, Element.ALIGN_RIGHT, true)
        d.texto(xPeso + colunas[6].largura + 3, top + 11f, colunas[7].largura - 6, vols.toString(), 8.5f, Element.ALIGN_CENTER, true)
        d.texto(xPeso + colunas[6].largura + colunas[7].largura + 3, top + 11f, colunas[8].largura - 6, Formato.dinheiro(valor), 8.5f, Element.ALIGN_RIGHT, true)
        val pendentes = r.notas.filter { it.pendenteSefaz }
        if (pendentes.isNotEmpty()) {
            d.texto(
                ESQ + 3, top + 25f, LARG - 6,
                "* NF ${pendentes.joinToString(", ") { it.numNota }} aguardando autorização da SEFAZ — o DANFE sai separado assim que autorizar.",
                8.5f, bold = true,
            )
        }
    }

    private fun assinaturas(d: Desenho, r: RomaneioOc, top: Float) {
        d.caixa(ESQ, top, LARG, ALT_ASSINATURAS)
        val w = LARG / 3
        fun bloco(x: Float, titulo: String, linha1: String, linha2: String) {
            d.linha(x + 20f, top + 50f, x + w - 20f, top + 50f, 0.5f)
            d.texto(x, top + 60f, w, titulo, 8f, Element.ALIGN_CENTER, true)
            d.texto(x, top + 71f, w, linha1, 8f, Element.ALIGN_CENTER)
            d.texto(x, top + 82f, w, linha2, 8f, Element.ALIGN_CENTER)
        }
        bloco(ESQ, "Motorista", r.motorista, "CPF: ____________________")
        bloco(ESQ + w, "Transportadora", r.transportadora, "Veículo: ${r.placa}")
        bloco(ESQ + 2 * w, "Responsável da Empresa", "O.Carga: ${r.ordemCarga}", "DATA:      /      /")
    }
}
