package wms.backend.impressao

import com.lowagie.text.Document
import com.lowagie.text.Element
import com.lowagie.text.PageSize
import com.lowagie.text.pdf.BarcodeInter25
import com.lowagie.text.pdf.PdfWriter
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Título de cobrança (TGFFIN) já registrado no banco — linha digitável e código de barras vêm do Sankhya. */
data class Boleto(
    val nunota: Long,
    val numNota: String,
    val desdobramento: String,
    val vencimento: String,
    val emissao: String,
    val valor: BigDecimal,
    val nossoNumero: String,
    val linhaDigitavel: String,
    val codigoBarras: String,
    val pixCopiaECola: String?,
    val codBanco: String,
    val nomeBanco: String,
    val agencia: String,
    val codBeneficiario: String,
    val carteira: String,
    val multaPerc: BigDecimal?,
    val jurosPerc: BigDecimal?,
    val diasProtesto: Int?,
    val beneficiario: String,
    val cnpjBeneficiario: String,
    val enderecoBeneficiario: String,
    val pagador: String,
    val docPagador: String,
    val enderecoPagador: String,
)

/**
 * Boleto (A4 em pé, padrão FEBRABAN): recibo do pagador em cima, ficha de compensação embaixo com o código
 * de barras I2/5. Boleto híbrido: com o "PIX copia e cola" (TGFFIN.EMVPIX) sai o QR Code no recibo.
 */
object BoletoPdf {
    private val pagina = PageSize.A4
    private const val ESQ = 30f
    private val LARG = pagina.width - 60f
    private const val H = 22f
    private val COL_DIR = 140f

    fun desenhar(documento: Document, writer: PdfWriter, b: Boleto) {
        documento.setPageSize(pagina)
        documento.newPage()
        writer.isPageEmpty = false
        val d = Desenho(writer.directContent, pagina.height)
        recibo(d, b, 30f)
        d.linha(ESQ, 318f, ESQ + LARG, 318f, 0.6f, tracejada = true)
        d.texto(ESQ, 314f, LARG, "Corte na linha pontilhada", 6f, Element.ALIGN_RIGHT)
        ficha(d, b, 340f)
    }

    /** Código do banco com o DV (módulo 11, pesos 2..4 da direita: 033 -> 033-7). */
    private fun bancoComDv(b: Boleto): String {
        val c = b.codBanco.filter { it.isDigit() }.padStart(3, '0').takeLast(3)
        val soma = c.reversed().mapIndexed { k, ch -> (ch - '0') * (k + 2) }.sum()
        val dv = 11 - soma % 11
        return "$c-${if (dv >= 10) 0 else dv}"
    }

    private fun cabecalho(d: Desenho, b: Boleto, top: Float, direita: String, direitaGrande: Boolean) {
        d.texto(ESQ, top + 18f, 150f, b.nomeBanco, 15f, bold = true)
        d.linha(ESQ + 150f, top + 2f, ESQ + 150f, top + 24f, 1.4f)
        d.texto(ESQ + 150f, top + 19f, 60f, bancoComDv(b), 14f, Element.ALIGN_CENTER, true)
        d.linha(ESQ + 210f, top + 2f, ESQ + 210f, top + 24f, 1.4f)
        d.texto(ESQ + 216f, top + 18f, LARG - 216f, direita, if (direitaGrande) 11.5f else 9f, Element.ALIGN_RIGHT, true)
        d.linha(ESQ, top + 24f, ESQ + LARG, top + 24f, 1.4f)
    }

    private fun documento(b: Boleto) = "${b.numNota}-${b.desdobramento}"

    /** Santander/FEBRABAN: nosso número com o dígito separado. */
    private fun nossoNumero(b: Boleto): String {
        val n = b.nossoNumero.trim()
        return if (n.length > 1) "${n.dropLast(1)}-${n.last()}" else n
    }

    private fun agenciaCodigo(b: Boleto) = "${b.agencia} / ${b.codBeneficiario}"

    private fun instrucoes(b: Boleto): List<String> = buildList {
        add("Instruções de responsabilidade do BENEFICIÁRIO. Qualquer dúvida sobre este boleto, contate o BENEFICIÁRIO.")
        b.multaPerc?.takeIf { it.signum() > 0 }?.let { add("Após o vencimento cobrar multa de ${Formato.quantidade(it, 2, 2)}%.") }
        // Taxa de juros da conta (TSICTA.VALORJUROS) não diz se é ao dia ou ao mês — só avisa que há juros.
        b.jurosPerc?.takeIf { it.signum() > 0 }?.let { add("Após o vencimento cobrar juros de mora.") }
        b.diasProtesto?.takeIf { it > 0 }?.let { add("Sujeito a protesto após $it dias do vencimento.") }
        add("Referente à NF ${b.numNota} - parcela ${b.desdobramento}.")
    }

    private fun recibo(d: Desenho, b: Boleto, top0: Float) {
        cabecalho(d, b, top0, "RECIBO DO PAGADOR", false)
        var t = top0 + 28f
        val temPix = !b.pixCopiaECola.isNullOrBlank()
        val wEsq = if (temPix) LARG - 130f else LARG
        val w3 = (wEsq - COL_DIR) / 2
        d.campo(ESQ, t, wEsq - COL_DIR, H, "Beneficiário", "${b.beneficiario} - CNPJ ${Formato.cnpjCpf(b.cnpjBeneficiario)}", tamanho = 7.5f)
        d.campo(ESQ + wEsq - COL_DIR, t, COL_DIR, H, "Vencimento", Formato.data(b.vencimento), Element.ALIGN_RIGHT, 9f, true)
        t += H
        d.campo(ESQ, t, wEsq - COL_DIR, H, "Pagador", "${b.pagador} - ${Formato.cnpjCpf(b.docPagador)}", tamanho = 7.5f)
        d.campo(ESQ + wEsq - COL_DIR, t, COL_DIR, H, "Agência / Código do Beneficiário", agenciaCodigo(b), Element.ALIGN_RIGHT)
        t += H
        d.campo(ESQ, t, w3, H, "Nº do documento", documento(b))
        d.campo(ESQ + w3, t, w3, H, "Nosso número", nossoNumero(b))
        d.campo(ESQ + wEsq - COL_DIR, t, COL_DIR, H, "(=) Valor do documento", Formato.dinheiro(b.valor), Element.ALIGN_RIGHT, 9f, true)
        t += H
        d.campo(ESQ, t, w3, H, "Data do documento", Formato.data(b.emissao))
        d.campo(ESQ + w3, t, w3, H, "Carteira", b.carteira)
        d.campo(ESQ + wEsq - COL_DIR, t, COL_DIR, H, "(=) Valor cobrado", "")
        t += H
        d.caixa(ESQ, t, wEsq, 70f)
        d.texto(ESQ + 2, t + 6f, 200f, "Linha digitável", 5f)
        d.texto(ESQ + 4, t + 20f, wEsq - 8, b.linhaDigitavel, 10.5f, bold = true)
        d.paragrafo(ESQ + 4, t + 28f, wEsq - 8, 40f, instrucoes(b).joinToString(" "), 6.5f)
        if (temPix) {
            val x = ESQ + LARG - 124f
            d.caixa(x - 6f, top0 + 28f, 130f, 4 * H + 70f)
            d.texto(x, top0 + 40f, 118f, "PAGUE COM PIX", 9f, Element.ALIGN_CENTER, true)
            // Margem branca de ~4 módulos em volta (zona de silêncio) pra leitura do app.
            d.qrCode(b.pixCopiaECola!!, x + 14f, top0 + 56f, 90f)
            d.paragrafo(x, top0 + 160f, 118f, 30f, "Aponte a câmera do app do seu banco para o QR Code.", 6.5f)
        }
        t += 70f
        d.texto(ESQ + LARG - 200f, t + 12f, 200f, "Autenticação mecânica", 6.5f, Element.ALIGN_RIGHT)
    }

    private fun ficha(d: Desenho, b: Boleto, top0: Float) {
        cabecalho(d, b, top0, b.linhaDigitavel, true)
        var t = top0 + 26f
        val wEsq = LARG - COL_DIR
        val xDir = ESQ + wEsq
        d.campo(ESQ, t, wEsq, H, "Local de pagamento", "PAGÁVEL EM QUALQUER BANCO OU LOTÉRICA ATÉ O VENCIMENTO. PREFERENCIALMENTE NO ${b.nomeBanco.uppercase()}.", tamanho = 7f)
        d.campo(xDir, t, COL_DIR, H, "Vencimento", Formato.data(b.vencimento), Element.ALIGN_RIGHT, 9.5f, true)
        t += H
        d.campo(ESQ, t, wEsq, H, "Beneficiário", "${b.beneficiario} - CNPJ ${Formato.cnpjCpf(b.cnpjBeneficiario)} - ${b.enderecoBeneficiario}", tamanho = 7f)
        d.campo(xDir, t, COL_DIR, H, "Agência / Código do Beneficiário", agenciaCodigo(b), Element.ALIGN_RIGHT)
        t += H
        val processamento = LocalDate.now(ZoneId.of("America/Sao_Paulo")).format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))
        val c = listOf(
            Triple("Data do documento", Formato.data(b.emissao), 0.2f),
            Triple("Nº do documento", documento(b), 0.22f),
            Triple("Espécie doc.", "DM", 0.14f),
            Triple("Aceite", "N", 0.12f),
            Triple("Data do processamento", processamento, 0.32f),
        )
        var x = ESQ
        c.forEach { (rot, v, f) -> d.campo(x, t, wEsq * f, H, rot, v); x += wEsq * f }
        d.campo(xDir, t, COL_DIR, H, "Nosso número", nossoNumero(b), Element.ALIGN_RIGHT)
        t += H
        val c2 = listOf(
            Triple("Uso do banco", "", 0.2f),
            Triple("Carteira", b.carteira, 0.22f),
            Triple("Espécie", "R$", 0.14f),
            Triple("Quantidade", "", 0.22f),
            Triple("Valor", "", 0.22f),
        )
        x = ESQ
        c2.forEach { (rot, v, f) -> d.campo(x, t, wEsq * f, H, rot, v); x += wEsq * f }
        d.campo(xDir, t, COL_DIR, H, "(=) Valor do documento", Formato.dinheiro(b.valor), Element.ALIGN_RIGHT, 9.5f, true)
        t += H
        // Instruções à esquerda; descontos/acréscimos à direita.
        d.caixa(ESQ, t, wEsq, 5 * H)
        d.texto(ESQ + 2, t + 6f, 300f, "Instruções (texto de responsabilidade do beneficiário)", 5f)
        var ti = t + 16f
        instrucoes(b).forEach { linha ->
            d.texto(ESQ + 4, ti, wEsq - 8, linha, 7.5f)
            ti += 11f
        }
        listOf("(-) Desconto / Abatimento", "(-) Outras deduções", "(+) Mora / Multa", "(+) Outros acréscimos", "(=) Valor cobrado")
            .forEachIndexed { k, rot -> d.campo(xDir, t + k * H, COL_DIR, H, rot, "") }
        t += 5 * H
        d.caixa(ESQ, t, LARG, 40f)
        d.texto(ESQ + 2, t + 6f, 100f, "Pagador", 5f)
        d.texto(ESQ + 4, t + 17f, LARG - 8, "${b.pagador} - CNPJ/CPF ${Formato.cnpjCpf(b.docPagador)}", 8f, bold = true)
        d.texto(ESQ + 4, t + 28f, LARG - 8, b.enderecoPagador, 7.5f)
        d.texto(ESQ + 4, t + 37f, LARG - 8, "Sacador/Avalista:", 6f)
        t += 40f
        d.texto(ESQ + LARG - 260f, t + 10f, 260f, "Autenticação mecânica - FICHA DE COMPENSAÇÃO", 6.5f, Element.ALIGN_RIGHT, true)
        codigoBarras(d, b.codigoBarras, ESQ, t + 6f)
    }

    /** I2/5 com 44 dígitos — ~103 mm x 13 mm (FEBRABAN). */
    private fun codigoBarras(d: Desenho, codigo: String, x: Float, top: Float) {
        val digitos = codigo.filter { it.isDigit() }
        if (digitos.length != 44) return
        val altura = 37f
        val barcode = BarcodeInter25().apply {
            code = digitos
            font = null
            n = 3f
            this.x = 0.72f
            barHeight = altura
        }
        val tpl = barcode.createTemplateWithBarcode(d.cb, null, null)
        d.cb.addTemplate(tpl, x, d.y(top + altura))
    }
}
