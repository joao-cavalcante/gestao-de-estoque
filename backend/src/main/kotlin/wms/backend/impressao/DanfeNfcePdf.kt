package wms.backend.impressao

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.lowagie.text.Document
import com.lowagie.text.Element
import com.lowagie.text.PageSize
import com.lowagie.text.pdf.PdfWriter

/**
 * DANFE NFC-e (modelo 65) no leiaute de cupom (80 mm), centralizado numa folha A4 — a impressora da
 * expedição é A4 de rede. Segue as divisões do Manual do DANFE NFC-e: emitente, itens, totais,
 * pagamento, consulta pela chave, consumidor, identificação/protocolo e QR Code.
 */
object DanfeNfcePdf {
    private const val LARG = 226f
    private val ESQ = (PageSize.A4.width - LARG) / 2
    private const val LIMITE = 815f

    private val formasPagamento = mapOf(
        "01" to "Dinheiro", "02" to "Cheque", "03" to "Cartão de Crédito", "04" to "Cartão de Débito", "05" to "Crédito Loja",
        "10" to "Vale Alimentação", "11" to "Vale Refeição", "12" to "Vale Presente", "13" to "Vale Combustível",
        "15" to "Boleto Bancário", "16" to "Depósito Bancário", "17" to "PIX", "18" to "Transferência",
        "19" to "Programa de Fidelidade", "90" to "Sem pagamento", "99" to "Outros",
    )

    fun desenhar(documento: Document, writer: PdfWriter, n: NotaNfe) {
        var d = novaFolha(documento, writer)
        var t = 20f
        fun garantir(altura: Float) {
            if (t + altura > LIMITE) {
                d = novaFolha(documento, writer)
                t = 20f
            }
        }
        fun centro(s: String, tamanho: Float, bold: Boolean = false) {
            d.quebrar(s, LARG, tamanho, bold).forEach { garantir(tamanho + 2); t += tamanho + 1.5f; d.texto(ESQ, t, LARG, it, tamanho, Element.ALIGN_CENTER, bold) }
        }
        fun separador() { t += 4f; d.linha(ESQ, t, ESQ + LARG, t, 0.3f, tracejada = true); t += 2f }
        fun parValor(rotulo: String, valor: String, bold: Boolean = false) {
            garantir(9f); t += 8.5f
            d.texto(ESQ, t, LARG * 0.65f, rotulo, 7f, bold = bold)
            d.texto(ESQ + LARG * 0.65f, t, LARG * 0.35f, valor, 7f, Element.ALIGN_RIGHT, bold)
        }

        val e = n.emitente
        val end = e.endereco
        centro(e.nome, 8f, true)
        centro("CNPJ: ${Formato.cnpjCpf(e.cnpjCpf)}  IE: ${e.ie}", 6.5f)
        centro(
            listOf(end.logradouro, end.numero).filter { it.isNotBlank() }.joinToString(", ") +
                " - ${end.bairro} - ${end.municipio} - ${end.uf}", 6.5f,
        )
        separador()
        centro("DANFE NFC-e - Documento Auxiliar da Nota Fiscal de Consumidor Eletrônica", 7f, true)
        separador()

        t += 7f
        d.texto(ESQ, t, 40f, "Código", 6f, bold = true)
        d.texto(ESQ + 40f, t, 110f, "Descrição", 6f, bold = true)
        d.texto(ESQ + 120f, t, 106f, "Qtde UN x Vl.Unit = Vl.Total", 6f, Element.ALIGN_RIGHT, true)
        n.itens.forEach { item ->
            val linhas = d.quebrar("${item.codigo}  ${item.descricao}", LARG, 6.5f)
            garantir(linhas.size * 8f + 9f)
            linhas.forEach { l -> t += 8f; d.texto(ESQ, t, LARG, l, 6.5f) }
            t += 8f
            d.texto(
                ESQ, t, LARG,
                "${Formato.quantidade(item.quantidade, 4)} ${item.unidade} x ${Formato.quantidade(item.valorUnitario, 4, 2)} = ${Formato.dinheiro(item.valorTotal)}",
                6.5f, Element.ALIGN_RIGHT,
            )
        }
        separador()
        val tt = n.totais
        parValor("QTD. TOTAL DE ITENS", n.itens.size.toString())
        parValor("VALOR TOTAL R$", Formato.dinheiro(tt["vProd"]))
        if ((tt["vDesc"]?.signum() ?: 0) > 0) parValor("DESCONTO R$", Formato.dinheiro(tt["vDesc"]))
        val acrescimos = listOfNotNull(tt["vFrete"], tt["vSeg"], tt["vOutro"]).fold(java.math.BigDecimal.ZERO) { a, b -> a + b }
        if (acrescimos.signum() > 0) parValor("ACRÉSCIMOS R$", Formato.dinheiro(acrescimos))
        parValor("VALOR A PAGAR R$", Formato.dinheiro(tt["vNF"]), bold = true)
        parValor("FORMA DE PAGAMENTO", "VALOR PAGO R$")
        n.pagamentos.forEach { p -> parValor(formasPagamento[p.forma] ?: p.forma, Formato.dinheiro(p.valor)) }
        n.troco?.takeIf { it.signum() > 0 }?.let { parValor("TROCO R$", Formato.dinheiro(it)) }
        separador()

        centro("Consulte pela Chave de Acesso em", 6.5f, true)
        centro(n.urlChave, 6.5f)
        centro(Formato.chave(n.chave), 6.5f)
        separador()

        val dest = n.destinatario
        if (dest == null || dest.cnpjCpf.isBlank()) {
            centro("CONSUMIDOR NÃO IDENTIFICADO", 6.5f, true)
        } else {
            centro("CONSUMIDOR - CNPJ/CPF ${Formato.cnpjCpf(dest.cnpjCpf)}", 6.5f, true)
            if (dest.nome.isNotBlank()) centro(dest.nome, 6.5f)
            val ed = dest.endereco
            if (ed.logradouro.isNotBlank()) centro(listOf(ed.logradouro, ed.numero, ed.bairro, ed.municipio).filter { it.isNotBlank() }.joinToString(", "), 6.5f)
        }
        separador()

        centro("NFC-e nº ${n.numero}  Série ${n.serie}  ${Formato.data(n.emissao)} ${Formato.hora(n.emissao)}", 7f, true)
        centro("Protocolo de autorização: ${n.protocolo}", 6.5f)
        centro("Data de autorização: ${Formato.data(n.dataProtocolo)} ${Formato.hora(n.dataProtocolo)}", 6.5f)
        if (n.ambiente == "2") centro("EMITIDA EM AMBIENTE DE HOMOLOGAÇÃO - SEM VALOR FISCAL", 7f, true)

        if (n.qrCode.isNotBlank()) {
            val lado = 120f
            garantir(lado + 8f)
            t += 6f
            qrCode(d, n.qrCode, ESQ + (LARG - lado) / 2, t, lado)
            t += lado
        }
        if (n.infCpl.isNotBlank()) {
            separador()
            d.quebrar(n.infCpl, LARG, 6f).forEach { garantir(8f); t += 7.5f; d.texto(ESQ, t, LARG, it, 6f) }
        }
    }

    private fun novaFolha(documento: Document, writer: PdfWriter): Desenho {
        documento.setPageSize(PageSize.A4)
        documento.newPage()
        writer.isPageEmpty = false
        return Desenho(writer.directContent, PageSize.A4.height)
    }

    /** QR Code desenhado como vetor (um quadrado por módulo) — nítido em qualquer impressora. */
    private fun qrCode(d: Desenho, conteudo: String, x: Float, top: Float, lado: Float) {
        val matriz = QRCodeWriter().encode(
            conteudo, BarcodeFormat.QR_CODE, 0, 0,
            mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 0),
        )
        val modulo = lado / matriz.width
        val cb = d.cb
        cb.saveState()
        cb.setGrayFill(0f)
        for (yy in 0 until matriz.height) for (xx in 0 until matriz.width) {
            if (matriz.get(xx, yy)) cb.rectangle(x + xx * modulo, d.y(top + (yy + 1) * modulo), modulo, modulo)
        }
        cb.fill()
        cb.restoreState()
    }
}
