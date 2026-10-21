package wms.backend.impressao

import com.lowagie.text.Element
import com.lowagie.text.Image
import com.lowagie.text.pdf.Barcode
import com.lowagie.text.pdf.Barcode128
import com.lowagie.text.pdf.BaseFont
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Peças comuns do DANFE (NF-e) e do DANFE NFC-e no modelo do Sankhya — medidas tiradas dos PDFs que o próprio
 * Sankhya gera (TGFPDF.PDFDANFE, notas 72063 e 68794): Courier New, caixas arredondadas, coordenadas em pontos
 * a partir do topo da folha A4.
 */
object ModeloSankhya {
    val courier: BaseFont = BaseFont.createFont(BaseFont.COURIER, BaseFont.CP1252, BaseFont.NOT_EMBEDDED)
    val courierNegrito: BaseFont = BaseFont.createFont(BaseFont.COURIER_BOLD, BaseFont.CP1252, BaseFont.NOT_EMBEDDED)
    val arial: BaseFont = BaseFont.createFont(BaseFont.HELVETICA, BaseFont.CP1252, BaseFont.NOT_EMBEDDED)
    val arialNegrito: BaseFont = BaseFont.createFont(BaseFont.HELVETICA_BOLD, BaseFont.CP1252, BaseFont.NOT_EMBEDDED)

    /** Texto com o topo da linha em [top] (como o bbox do PDF de referência), não a linha de base. */
    fun Desenho.t(x: Float, top: Float, w: Float, s: String?, tam: Float, bold: Boolean = false, alinhamento: Int = Element.ALIGN_LEFT) =
        texto(x, top + tam * 0.8f, w, s, tam, alinhamento, bold, minimo = tam * 0.6f)

    /** Rótulo pequeno de campo (Courier 5) e valor (Courier 8) dentro de uma caixa arredondada. */
    fun Desenho.campoSk(
        x0: Float, top: Float, x1: Float, bottom: Float, rotulo: String, valor: String?,
        alinhamento: Int = Element.ALIGN_LEFT, tamValor: Float = 8f, boldRotulo: Boolean = false, boldValor: Boolean = false,
    ) {
        caixaArredondada(x0, top, x1 - x0, bottom - top)
        t(x0 + 2f, top + 2f, x1 - x0 - 4f, rotulo, 5f, boldRotulo)
        val xv = if (alinhamento == Element.ALIGN_RIGHT) x0 + 2f else x0 + 3f
        t(xv, top + 7f, x1 - x0 - 6f, valor, tamValor, boldValor, alinhamento)
    }

    fun codigoBarras(d: Desenho, codigo: String, x: Float, top: Float, w: Float, h: Float) {
        if (codigo.isBlank()) return
        val barcode = Barcode128().apply {
            codeType = Barcode.CODE128
            code = codigo
            font = null
            barHeight = h
        }
        val img = barcode.createImageWithBarcode(d.cb, null, null)
        img.scaleAbsolute(w, h)
        img.setAbsolutePosition(x, d.y(top + h))
        d.cb.addImage(img)
    }

    fun logo(d: Desenho, png: ByteArray?, x: Float, top: Float, w: Float, h: Float) {
        if (png == null) return
        runCatching {
            val img = Image.getInstance(png)
            img.scaleToFit(w, h)
            img.setAbsolutePosition(x, d.y(top + img.scaledHeight))
            d.cb.addImage(img)
        }
    }

    /** "dd-MM-yyyy" (data do destinatário no DANFE do Sankhya). */
    fun dataTraco(iso: String?): String = Formato.data(iso).replace('/', '-')

    /** "dd/MM/yyyy HH:mm:ss" de um dhEmi/dhRecbto ISO com fuso. */
    fun dataHora(iso: String?): String {
        val s = iso?.trim().orEmpty()
        if (s.length < 19) return Formato.data(s)
        return "${Formato.data(s)} ${s.substring(11, 19)}"
    }

    /** CEP "03.526-000". */
    fun cep(s: String?): String {
        val d = s?.filter { it.isDigit() }.orEmpty()
        return if (d.length == 8) "${d.substring(0, 2)}.${d.substring(2, 5)}-${d.substring(5)}" else d
    }

    /** "62.698" — número da nota com milhar. */
    fun numero(s: String?): String = s?.trim()?.toLongOrNull()?.let { "%,d".format(it).replace(',', '.') } ?: s.orEmpty()

    fun quantidade(v: BigDecimal?, casas: Int): String = Formato.quantidade(v ?: BigDecimal.ZERO, casas, casas)

    /** Valor em reais por extenso ("trinta e nove reais e sessenta centavos"). */
    fun extenso(valor: BigDecimal): String {
        val v = valor.setScale(2, RoundingMode.HALF_UP)
        val reais = v.toBigInteger().toLong()
        val centavos = v.remainder(BigDecimal.ONE).movePointRight(2).toInt()
        val partes = mutableListOf<String>()
        if (reais > 0) partes += inteiro(reais) + if (reais == 1L) " real" else if (reais % 1_000_000 == 0L) " de reais" else " reais"
        if (centavos > 0) partes += inteiro(centavos.toLong()) + if (centavos == 1) " centavo" else " centavos"
        return if (partes.isEmpty()) "zero reais" else partes.joinToString(" e ")
    }

    private val unidades = listOf("", "um", "dois", "três", "quatro", "cinco", "seis", "sete", "oito", "nove",
        "dez", "onze", "doze", "treze", "quatorze", "quinze", "dezesseis", "dezessete", "dezoito", "dezenove")
    private val dezenas = listOf("", "", "vinte", "trinta", "quarenta", "cinquenta", "sessenta", "setenta", "oitenta", "noventa")
    private val centenas = listOf("", "cento", "duzentos", "trezentos", "quatrocentos", "quinhentos", "seiscentos", "setecentos", "oitocentos", "novecentos")

    private fun ate999(n: Int): String {
        if (n == 0) return ""
        if (n == 100) return "cem"
        val c = n / 100
        val r = n % 100
        val resto = if (r < 20) unidades[r] else listOf(dezenas[r / 10], unidades[r % 10]).filter { it.isNotEmpty() }.joinToString(" e ")
        return listOf(centenas[c], resto).filter { it.isNotEmpty() }.joinToString(" e ")
    }

    private fun inteiro(n: Long): String {
        if (n == 0L) return "zero"
        val milhoes = (n / 1_000_000).toInt()
        val milhares = ((n / 1000) % 1000).toInt()
        val resto = (n % 1000).toInt()
        val grupos = mutableListOf<String>()
        if (milhoes > 0) grupos += ate999(milhoes) + if (milhoes == 1) " milhão" else " milhões"
        if (milhares > 0) grupos += if (milhares == 1) "mil" else ate999(milhares) + " mil"
        if (resto > 0) grupos += ate999(resto)
        // "mil e cem", "mil e vinte"; "mil duzentos e trinta" (sem "e" quando o resto tem centena e dezena).
        return grupos.foldIndexed("") { i, acc, g ->
            when {
                i == 0 -> g
                i == grupos.lastIndex && (resto < 100 || resto % 100 == 0) -> "$acc e $g"
                else -> "$acc $g"
            }
        }
    }
}
