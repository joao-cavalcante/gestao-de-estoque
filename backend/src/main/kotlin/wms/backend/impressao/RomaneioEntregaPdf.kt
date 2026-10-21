package wms.backend.impressao

import com.lowagie.text.Document
import com.lowagie.text.Element
import com.lowagie.text.PageSize
import com.lowagie.text.pdf.PdfWriter
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class EntregaPedido(
    val pedido: String,
    val notaFiscal: String,
    val pagamento: String,
    val valor: BigDecimal?,
    val peso: BigDecimal?,
    val codParc: String,
    val cliente: String,
    val endereco: String,
    val bairro: String,
    val cidade: String,
    val referencia: String,
    val telefone: String,
    val vendedora: String,
)

data class RomaneioEntrega(
    val ordemCarga: Long,
    val data: String,
    val placa: String,
    val motorista: String,
    val veiculo: String,
    val pesoMaximo: BigDecimal?,
    val pesoTotal: BigDecimal,
    val pedidos: List<EntregaPedido>,
)

/**
 * "Romaneio de Entrega" — mesmo layout do relatório formatado 179 do Sankhya (ROMANEIO_ENTREGA_NEGRI_PORTAL_OC_UNICA.jrxml,
 * botão "Romaneio" da OC): título com itinerário/data/condutor, um bloco por pedido com cliente/endereço/pagamento
 * e assinatura do recebedor (nome legível + RG). Coordenadas copiadas do jrxml (margens 20/18, coluna 555).
 */
object RomaneioEntregaPdf {
    private val pagina = PageSize.A4
    private const val ESQ = 20f
    private const val TOPO = 18f
    private const val LARG = 555f
    private const val ALT_TITULO = 116f
    private const val ALT_PEDIDO = 135f
    private const val ALT_ASSINATURA = 30f
    private const val ALT_RODAPE = 22f
    private val fimUtil = pagina.height - 20f - ALT_RODAPE

    fun desenhar(documento: Document, writer: PdfWriter, r: RomaneioEntrega) {
        val agora = LocalDateTime.now(ZoneId.of("America/Sao_Paulo")).format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss"))
        // Um pedido não quebra entre folhas.
        val folhas = mutableListOf<MutableList<EntregaPedido>>(mutableListOf())
        var t = TOPO + ALT_TITULO
        r.pedidos.forEach { p ->
            if (t + ALT_PEDIDO + ALT_ASSINATURA > fimUtil && folhas.last().isNotEmpty()) {
                folhas += mutableListOf<EntregaPedido>()
                t = TOPO
            }
            folhas.last() += p
            t += ALT_PEDIDO + ALT_ASSINATURA
        }
        folhas.forEachIndexed { i, pedidos ->
            documento.setPageSize(pagina)
            documento.newPage()
            writer.isPageEmpty = false
            val d = Desenho(writer.directContent, pagina.height)
            var top = TOPO
            if (i == 0) {
                titulo(d, r, top)
                top += ALT_TITULO
            }
            if (r.pedidos.isEmpty()) {
                d.texto(ESQ, top + 23f, LARG, "Nenhum pedido encontrado para a Ordem de Carga informada.", 11f, Element.ALIGN_CENTER)
            }
            pedidos.forEach { p ->
                pedido(d, p, top)
                top += ALT_PEDIDO
                assinatura(d, top)
                top += ALT_ASSINATURA
            }
            val rodape = pagina.height - 20f - ALT_RODAPE
            d.linha(ESQ, rodape, ESQ + LARG, rodape, 0.5f)
            d.texto(ESQ, rodape + 13f, 175f, agora, 7f)
            d.texto(ESQ + 430f, rodape + 13f, 125f, "Página ${i + 1} de ${folhas.size}", 7f, Element.ALIGN_RIGHT)
        }
    }

    /** Texto do jrxml: caixa (x, y, w, h) relativa à banda; baseline perto do topo da caixa, como o Jasper. */
    private fun campo(d: Desenho, banda: Float, x: Float, y: Float, w: Float, s: String?, tam: Float, bold: Boolean = false, alinhamento: Int = Element.ALIGN_LEFT) =
        d.texto(ESQ + x, banda + y + tam + 1f, w, s, tam, alinhamento, bold)

    private fun titulo(d: Desenho, r: RomaneioEntrega, b: Float) {
        campo(d, b, 0f, 0f, LARG, "Romaneio de Entrega", 15f, true, Element.ALIGN_CENTER)
        val largTitulo = d.negrito.getWidthPoint("Romaneio de Entrega", 15f)
        d.linha(ESQ + (LARG - largTitulo) / 2, b + 19f, ESQ + (LARG + largTitulo) / 2, b + 19f, 0.8f)
        campo(d, b, 0f, 36f, 110f, "Itinerário:", 11f, true)
        campo(d, b, 65f, 36f, 180f, r.ordemCarga.toString(), 11f, true)
        campo(d, b, 385f, 36f, 50f, "Data:", 11f, true)
        campo(d, b, 440f, 36f, 115f, Formato.data(r.data), 11f, true, Element.ALIGN_RIGHT)
        campo(d, b, 0f, 57f, 140f, "Transportadora / Condutor:", 10f)
        val condutor = buildString {
            append(r.placa)
            if (r.motorista.isNotBlank()) append(" - ").append(r.motorista)
            if (r.veiculo.isNotBlank()) append(" - ").append(r.veiculo)
            r.pesoMaximo?.let { append(" / Hr ").append(Formato.quantidade(it, 2)).append(" Kg") }
        }
        campo(d, b, 142f, 57f, 413f, condutor, 10f)
        campo(d, b, 0f, 79f, 105f, "Placa do Veículo:", 10f)
        campo(d, b, 108f, 79f, 220f, r.placa, 10f)
        campo(d, b, 350f, 79f, 105f, "Total de Pedidos:", 10f)
        campo(d, b, 455f, 79f, 100f, r.pedidos.size.toString(), 10f, alinhamento = Element.ALIGN_RIGHT)
        campo(d, b, 350f, 96f, 105f, "Peso Total Kg:", 10f)
        campo(d, b, 455f, 96f, 100f, Formato.quantidade(r.pesoTotal, 2, 2), 10f, alinhamento = Element.ALIGN_RIGHT)
    }

    private fun pedido(d: Desenho, p: EntregaPedido, b: Float) {
        d.linha(ESQ, b, ESQ + LARG, b, 0.8f)
        campo(d, b, 7f, 7f, 185f, "Pedido: ${p.pedido}", 10f, true)
        campo(d, b, 198f, 7f, 230f, "Pagamento: ${p.pagamento}", 10f, true)
        campo(d, b, 429f, 7f, 126f, "Valor: ${p.valor?.let { Formato.dinheiro(it) } ?: ""}", 10f, true, Element.ALIGN_RIGHT)
        campo(d, b, 429f, 27f, 126f, "Nota Fiscal: ${p.notaFiscal}", 9f, true, Element.ALIGN_RIGHT)
        campo(d, b, 429f, 45f, 126f, "Peso: ${p.peso?.let { Formato.quantidade(it, 2, 2) } ?: ""}", 9f, true, Element.ALIGN_RIGHT)
        campo(d, b, 7f, 30f, 65f, "Cliente:", 9f, true)
        campo(d, b, 72f, 30f, 350f, listOf(p.codParc, p.cliente).filter { it.isNotBlank() }.joinToString(" - "), 9f)
        campo(d, b, 7f, 52f, 65f, "Endereço:", 9f, true)
        campo(d, b, 72f, 52f, 350f, p.endereco, 9f)
        campo(d, b, 7f, 74f, 65f, "Bairro:", 9f, true)
        campo(d, b, 72f, 74f, 250f, p.bairro, 9f)
        campo(d, b, 322f, 74f, 100f, "Cidade: ${p.cidade}", 9f)
        campo(d, b, 7f, 96f, 65f, "Referência:", 9f, true)
        campo(d, b, 72f, 96f, 350f, p.referencia, 9f)
        campo(d, b, 7f, 118f, 65f, "Telefone:", 9f, true)
        campo(d, b, 72f, 118f, 155f, p.telefone, 9f)
        campo(d, b, 235f, 118f, 65f, "Vendedora:", 9f, true)
        campo(d, b, 300f, 118f, 122f, p.vendedora, 9f)
    }

    private fun assinatura(d: Desenho, b: Float) {
        d.linha(ESQ + 72f, b + 5f, ESQ + 282f, b + 5f, 0.5f)
        d.linha(ESQ + 315f, b + 5f, ESQ + 440f, b + 5f, 0.5f)
        campo(d, b, 72f, 7f, 210f, "Nome legível", 8f, alinhamento = Element.ALIGN_CENTER)
        campo(d, b, 315f, 7f, 125f, "R.G.", 8f, alinhamento = Element.ALIGN_CENTER)
    }
}
