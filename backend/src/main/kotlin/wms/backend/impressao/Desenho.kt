package wms.backend.impressao

import com.lowagie.text.Element
import com.lowagie.text.pdf.BaseFont
import com.lowagie.text.pdf.PdfContentByte
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/**
 * Desenho absoluto em cima do PdfContentByte com coordenadas "de cima pra baixo" (top = distância do topo
 * da página), que é como o layout do DANFE/romaneio é pensado. Fontes Helvetica (embutidas no PDF base 14).
 */
class Desenho(val cb: PdfContentByte, private val alturaPagina: Float) {
    val normal: BaseFont = BaseFont.createFont(BaseFont.HELVETICA, BaseFont.CP1252, BaseFont.NOT_EMBEDDED)
    val negrito: BaseFont = BaseFont.createFont(BaseFont.HELVETICA_BOLD, BaseFont.CP1252, BaseFont.NOT_EMBEDDED)

    fun y(top: Float) = alturaPagina - top

    fun caixa(x: Float, top: Float, w: Float, h: Float, espessura: Float = 0.5f) {
        cb.setLineWidth(espessura)
        cb.rectangle(x, y(top + h), w, h)
        cb.stroke()
    }

    fun linha(x1: Float, top1: Float, x2: Float, top2: Float, espessura: Float = 0.5f, tracejada: Boolean = false) {
        cb.setLineWidth(espessura)
        if (tracejada) cb.setLineDash(3f, 2f, 0f)
        cb.moveTo(x1, y(top1))
        cb.lineTo(x2, y(top2))
        cb.stroke()
        if (tracejada) cb.setLineDash(0f)
    }

    fun preencher(x: Float, top: Float, w: Float, h: Float, cinza: Float) {
        cb.saveState()
        cb.setGrayFill(cinza)
        cb.rectangle(x, y(top + h), w, h)
        cb.fill()
        cb.restoreState()
    }

    /** Texto numa linha só, encolhendo a fonte até [minimo] e cortando com "…" se ainda não couber em [w]. */
    fun texto(
        x: Float, baseTop: Float, w: Float, s: String?, tamanho: Float,
        alinhamento: Int = Element.ALIGN_LEFT, bold: Boolean = false, minimo: Float = 4.5f,
    ) {
        var t = (s ?: "").replace('\n', ' ').trim()
        if (t.isEmpty()) return
        val fonte = if (bold) negrito else normal
        var tam = tamanho
        while (fonte.getWidthPoint(t, tam) > w && tam > minimo) tam -= 0.25f
        if (fonte.getWidthPoint(t, tam) > w) {
            while (t.isNotEmpty() && fonte.getWidthPoint("$t…", tam) > w) t = t.dropLast(1)
            t = "$t…"
        }
        val px = when (alinhamento) {
            Element.ALIGN_RIGHT -> x + w
            Element.ALIGN_CENTER -> x + w / 2
            else -> x
        }
        cb.beginText()
        cb.setFontAndSize(fonte, tam)
        cb.showTextAligned(alinhamento, t, px, y(baseTop), 0f)
        cb.endText()
    }

    /** Quebra [s] em linhas que cabem em [w] (por palavra; palavra maior que a linha é cortada). */
    fun quebrar(s: String?, w: Float, tamanho: Float, bold: Boolean = false): List<String> {
        val fonte = if (bold) negrito else normal
        val linhas = mutableListOf<String>()
        for (paragrafo in (s ?: "").split('\n')) {
            var atual = ""
            for (palavra in paragrafo.split(' ').filter { it.isNotEmpty() }) {
                val tentativa = if (atual.isEmpty()) palavra else "$atual $palavra"
                if (fonte.getWidthPoint(tentativa, tamanho) <= w) {
                    atual = tentativa
                } else {
                    if (atual.isNotEmpty()) linhas += atual
                    var resto = palavra
                    while (fonte.getWidthPoint(resto, tamanho) > w && resto.length > 1) {
                        var corte = resto.length
                        while (corte > 1 && fonte.getWidthPoint(resto.take(corte), tamanho) > w) corte--
                        linhas += resto.take(corte)
                        resto = resto.drop(corte)
                    }
                    atual = resto
                }
            }
            if (atual.isNotEmpty()) linhas += atual
        }
        return linhas
    }

    /** Várias linhas a partir de [top], [entrelinha] pontos entre bases; devolve quantas couberam. */
    fun paragrafo(x: Float, top: Float, w: Float, h: Float, s: String?, tamanho: Float, entrelinha: Float = tamanho + 1.5f, bold: Boolean = false): Int {
        val linhas = quebrar(s, w, tamanho, bold)
        val cabem = ((h) / entrelinha).toInt().coerceAtLeast(0)
        linhas.take(cabem).forEachIndexed { i, l -> texto(x, top + tamanho + i * entrelinha, w, l, tamanho, bold = bold) }
        return minOf(cabem, linhas.size)
    }

    /** Campo padrão do DANFE: caixa com rótulo pequeno em cima e valor embaixo. */
    fun campo(
        x: Float, top: Float, w: Float, h: Float, rotulo: String, valor: String?,
        alinhamento: Int = Element.ALIGN_LEFT, tamanho: Float = 8f, bold: Boolean = false,
    ) {
        caixa(x, top, w, h)
        texto(x + 2, top + 6f, w - 4, rotulo, 5f)
        texto(x + 2, top + h - 3f, w - 4, valor, tamanho, alinhamento, bold)
    }

    fun titulo(x: Float, top: Float, s: String) = texto(x, top + 7f, 400f, s, 6.5f, bold = true)
}

object Formato {
    private val simbolos = DecimalFormatSymbols(Locale.forLanguageTag("pt-BR"))

    fun dinheiro(v: BigDecimal?): String = DecimalFormat("#,##0.00", simbolos).format((v ?: BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP))

    /** Quantidade com até [casas] decimais, sem zeros à direita (mínimo [minimo] casas). */
    fun quantidade(v: BigDecimal?, casas: Int = 4, minimo: Int = 0): String {
        val f = DecimalFormat("#,##0." + "0".repeat(minimo) + "#".repeat((casas - minimo).coerceAtLeast(0)), simbolos)
        return f.format(v ?: BigDecimal.ZERO).removeSuffix(",")
    }

    fun cnpjCpf(s: String?): String {
        val d = s?.filter { it.isDigit() } ?: return ""
        return when (d.length) {
            14 -> "${d.substring(0, 2)}.${d.substring(2, 5)}.${d.substring(5, 8)}/${d.substring(8, 12)}-${d.substring(12)}"
            11 -> "${d.substring(0, 3)}.${d.substring(3, 6)}.${d.substring(6, 9)}-${d.substring(9)}"
            else -> s
        }
    }

    fun cep(s: String?): String {
        val d = s?.filter { it.isDigit() } ?: return ""
        return if (d.length == 8) "${d.substring(0, 5)}-${d.substring(5)}" else d
    }

    fun fone(s: String?): String {
        val d = s?.filter { it.isDigit() } ?: return ""
        return when (d.length) {
            10 -> "(${d.substring(0, 2)}) ${d.substring(2, 6)}-${d.substring(6)}"
            11 -> "(${d.substring(0, 2)}) ${d.substring(2, 7)}-${d.substring(7)}"
            else -> d
        }
    }

    /** "2026-10-20T00:44:24-03:00" -> "20/10/2026"; "2026-10-27" -> "27/10/2026". */
    fun data(iso: String?): String {
        val s = iso?.trim() ?: return ""
        if (s.length < 10) return s
        return "${s.substring(8, 10)}/${s.substring(5, 7)}/${s.substring(0, 4)}"
    }

    fun hora(iso: String?): String {
        val s = iso?.trim() ?: return ""
        return if (s.length >= 19) s.substring(11, 19) else ""
    }

    fun chave(s: String): String = s.chunked(4).joinToString(" ")
}
