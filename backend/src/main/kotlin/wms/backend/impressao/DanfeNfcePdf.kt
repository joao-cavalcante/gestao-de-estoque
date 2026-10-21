package wms.backend.impressao

import com.lowagie.text.Document
import com.lowagie.text.Element
import com.lowagie.text.PageSize
import com.lowagie.text.pdf.PdfWriter
import wms.backend.impressao.ModeloSankhya.t

/**
 * DANFE NFC-e (modelo 65) no layout A4 do Sankhya — copiado do PDF que o Sankhya gera (TGFPDF, nota 68794):
 * canhoto com NUNOTA, quadro da empresa com logo, itens, totais/pagamento, peso/volumes, "OC: n" + observação,
 * chave de consulta, consumidor e QR Code. Muitos itens: continuam nas folhas seguintes e o rodapé fica na última.
 */
object DanfeNfcePdf {
    private const val ESQ = 14f
    private const val DIR = 580f
    private const val LARG = DIR - ESQ
    private const val ALT_ITEM = 10f
    private const val TOPO_ITENS = 206f
    /** Os quadros de baixo (observação, chave, consumidor, QR) começam aqui na última folha. */
    private const val TOPO_RODAPE = 524f
    /** Totais + pagamento + peso ocupam até ~70 pt abaixo do último item. */
    private const val ALT_TOTAIS = 74f
    private const val FIM_FOLHA = 815f

    private val formasPagamento = mapOf(
        "01" to "Dinheiro", "02" to "Cheque", "03" to "Cartão de Crédito", "04" to "Cartão de Débito", "05" to "Crédito Loja",
        "10" to "Vale Alimentação", "11" to "Vale Refeição", "12" to "Vale Presente", "13" to "Vale Combustível",
        "15" to "Boleto Bancário", "16" to "Depósito Bancário", "17" to "PIX", "18" to "Transferência",
        "19" to "Programa de Fidelidade", "90" to "Sem pagamento", "99" to "Outros",
    )

    fun desenhar(documento: Document, writer: PdfWriter, n: NotaNfe) {
        // Itens por folha: a última precisa caber totais + rodapé; as do meio vão até o pé.
        val cabemUltima = ((TOPO_RODAPE - ALT_TOTAIS - TOPO_ITENS) / ALT_ITEM).toInt()
        val cabemCheia = ((FIM_FOLHA - TOPO_ITENS) / ALT_ITEM).toInt()
        val folhas = mutableListOf<List<ItemNfe>>()
        var resto = n.itens
        while (resto.size > cabemUltima) {
            folhas += resto.take(cabemCheia)
            resto = resto.drop(cabemCheia)
        }
        folhas += resto

        folhas.forEachIndexed { i, itens ->
            documento.setPageSize(PageSize.A4)
            documento.newPage()
            writer.isPageEmpty = false
            val d = Desenho(writer.directContent, PageSize.A4.height, ModeloSankhya.courier, ModeloSankhya.courierNegrito)
            cabecalho(d, n)
            var y = TOPO_ITENS
            itens.forEach { item ->
                d.t(ESQ, y, 81f, item.codigo, 7f, alinhamento = Element.ALIGN_RIGHT)
                d.t(102f, y, 340f, item.descricao, 7f)
                d.t(420f, y, 51f, Formato.quantidade(item.quantidade, 2, 2), 7f, alinhamento = Element.ALIGN_RIGHT)
                d.t(482f, y, 18f, item.unidade, 7f)
                d.t(495f, y, 40f, Formato.quantidade(item.valorUnitario, 2, 2), 7f, alinhamento = Element.ALIGN_RIGHT)
                d.t(535f, y, 45f, Formato.dinheiro(item.valorTotal), 7f, alinhamento = Element.ALIGN_RIGHT)
                y += ALT_ITEM
            }
            if (i == folhas.lastIndex) {
                totais(d, n, y + 2f)
                rodape(d, n)
            }
        }
    }

    private fun cabecalho(d: Desenho, n: NotaNfe) {
        val e = n.emitente
        val ed = e.endereco
        // Canhoto
        d.caixaArredondada(ESQ, 22f, LARG, 48f)
        d.caixaArredondada(ESQ, 22f, 390f, 32f)
        d.t(18f, 26f, 384f, "RECEBEMOS DE ${e.nome} OS PRODUTOS CONSTANTES DA NFC-e. NUNOTA: ${n.extras?.nunota ?: ""}", 5f)
        d.caixaArredondada(ESQ, 54f, 120f, 16f)
        d.t(16f, 57f, 116f, "DATA DE RECEBIMENTO", 5f)
        d.caixaArredondada(134f, 54f, 270f, 16f)
        d.t(136f, 57f, 266f, "IDENTIFICAÇÃO E ASSINATURA DO RECEBEDOR", 5f)
        d.caixaArredondada(404f, 22f, 90f, 48f)
        ModeloSankhya.codigoBarras(d, n.extras?.nunota?.toString() ?: n.numero, 409f, 27f, 80f, 22f)
        d.caixaArredondada(494f, 22f, 86f, 48f)
        d.t(494f, 26f, 86f, "NFC-e", 7f, true, Element.ALIGN_CENTER)
        d.t(494f, 52f, 86f, "N. ${n.numero}", 6f, true, Element.ALIGN_CENTER)
        d.t(494f, 58f, 86f, "SÉRIE ${n.serie}", 6f, true, Element.ALIGN_CENTER)
        d.linha(ESQ, 72.5f, DIR, 72.5f, 1f, tracejada = true)

        // Empresa
        d.caixaArredondada(ESQ, 74f, LARG, 75f)
        ModeloSankhya.logo(d, n.extras?.logo, 16f, 75f, 56f, 53f)
        fun rotulo(top: Float, s: String) = d.t(70f, top, 75f, s, 5f, true, Element.ALIGN_RIGHT)
        rotulo(80f, "EMPRESA:")
        d.t(147f, 78f, 420f, e.nome, 8f)
        rotulo(90f, "CNPJ:")
        d.t(147f, 88f, 420f, Formato.cnpjCpf(e.cnpjCpf), 8f)
        rotulo(100f, "INSCR. EST.:")
        d.t(147f, 98f, 420f, e.ie, 8f)
        rotulo(110f, "ENDEREÇO:")
        d.t(147f, 114f, 420f, "${ed.logradouro} N.${ed.numero} -${ed.complemento.takeIf { it.isNotBlank() }?.let { " $it" } ?: ""}", 8f)
        d.t(147f, 123f, 420f, "Bairro ${ed.bairro},${ed.municipio}, ${ed.uf}", 8f)
        d.t(147f, 132f, 420f, "Fone: ${Formato.fone(ed.fone)}, CEP:${ed.cep}", 8f)

        d.caixaArredondada(ESQ, 151f, LARG, 20f)
        d.t(ESQ, 154f, LARG, "DANFE NFC-e - Documento Auxiliar da Nota Fiscal Eletrônica para Consumidor Final", 7f, true, Element.ALIGN_CENTER)
        d.t(ESQ, 162f, LARG, "Não permite aproveitamento de crédito de ICMS", 7f, true, Element.ALIGN_CENTER)

        d.caixaArredondada(ESQ, 173f, LARG, 22f)
        d.t(ESQ, 177f, 61f, "Data da Emissão:", 5f, true, Element.ALIGN_RIGHT)
        d.t(77f, 176f, 200f, ModeloSankhya.dataHora(n.emissao), 7f)
        d.t(ESQ, 187f, 61f, "Nro. NFC-e:", 5f, true, Element.ALIGN_RIGHT)
        d.t(77f, 186f, 200f, n.numero, 7f)

        d.t(ESQ, 198f, 81f, "Código", 7f, true, Element.ALIGN_RIGHT)
        d.t(102f, 198f, 200f, "Descrição", 7f, true)
        d.t(420f, 198f, 51f, "Qtde", 7f, true, Element.ALIGN_RIGHT)
        d.t(482f, 198f, 18f, "UN", 7f, true)
        d.t(495f, 198f, 40f, "Vlr Unit", 7f, true, Element.ALIGN_RIGHT)
        d.t(535f, 198f, 45f, "Vlr Total", 7f, true, Element.ALIGN_RIGHT)
    }

    private fun totais(d: Desenho, n: NotaNfe, top: Float) {
        val tt = n.totais
        val xFim = 234f
        d.t(21f, top + 4f, 160f, "Qtd. Total de Itens", 10f, true)
        d.t(150f, top + 4f, xFim - 150f, n.itens.size.toString(), 10f, true, Element.ALIGN_RIGHT)
        d.t(21f, top + 16f, 114f, "Valor Total", 10f, true, Element.ALIGN_RIGHT)
        d.t(150f, top + 16f, xFim - 150f, Formato.dinheiro(tt["vNF"]), 10f, true, Element.ALIGN_RIGHT)
        d.t(21f, top + 29f, 128f, "Forma de Pagamento", 7f, true, Element.ALIGN_RIGHT)
        d.t(150f, top + 29f, xFim - 150f, "Valor Pago", 7f, true, Element.ALIGN_RIGHT)
        var y = top + 39f
        n.pagamentos.forEach { p ->
            d.t(21f, y, 114f, formasPagamento[p.forma] ?: p.forma, 7f, alinhamento = Element.ALIGN_RIGHT)
            d.t(150f, y, xFim - 150f, Formato.dinheiro(p.valor), 7f, alinhamento = Element.ALIGN_RIGHT)
            y += 10f
        }
        d.t(21f, y, 114f, "Troco", 7f, true, Element.ALIGN_RIGHT)
        d.t(150f, y, xFim - 150f, Formato.dinheiro(n.troco), 7f, alinhamento = Element.ALIGN_RIGHT)
        val ex = n.extras
        val pesoB = ex?.pesoBruto ?: n.pesoBruto
        val pesoL = ex?.pesoLiquido ?: n.pesoLiquido
        val vols = ex?.volumes?.ifBlank { null } ?: n.volQtd
        d.t(ESQ, y + 16f, LARG, "Peso bruto (kg): ${pesoB?.let { ModeloSankhya.quantidade(it, 3) } ?: ""} | " +
            "Peso líquido (kg): ${pesoL?.let { ModeloSankhya.quantidade(it, 3) } ?: ""} | Volumes: $vols", 7f)
    }

    private fun rodape(d: Desenho, n: NotaNfe) {
        // Observação: "OC: n" + informações complementares, centralizadas.
        d.caixaArredondada(ESQ, TOPO_RODAPE, LARG, 36f)
        var y = TOPO_RODAPE + 2f
        val linhas = listOfNotNull(n.extras?.ordemCarga?.let { "OC: $it" }, n.infCpl.ifBlank { null })
            .flatMap { d.quebrar(it, LARG - 8f, 7f) }
        linhas.take(4).forEach { l ->
            d.t(ESQ, y, LARG, l, 7f, alinhamento = Element.ALIGN_CENTER)
            y += 8f
        }

        d.caixaArredondada(ESQ, 562f, LARG, 42f)
        d.t(100f, 576f, 70f, "Número:", 5f, true, Element.ALIGN_RIGHT)
        d.t(171f, 575f, 50f, n.numero, 7f)
        d.t(180f, 576f, 70f, "Serie:", 5f, true, Element.ALIGN_RIGHT)
        d.t(251f, 575f, 50f, n.serie, 7f)
        d.t(260f, 576f, 70f, "Emissão:", 5f, true, Element.ALIGN_RIGHT)
        d.t(331f, 575f, 120f, ModeloSankhya.dataHora(n.emissao), 7f)
        d.t(453f, 576f, 100f, "Via Consumidor", 5f, true)
        d.t(143f, 586f, 110f, "Consulte pela Chave de Acesso em:", 5f, true)
        d.t(251f, 585f, 320f, n.urlChave, 7f)
        d.t(143f, 596f, 50f, "CHAVE DE ACESSO", 5f, true)
        d.t(191f, 595f, 380f, Formato.chave(n.chave), 7f)

        d.caixaArredondada(ESQ, 606f, LARG, 85f)
        d.t(ESQ, 609f, LARG, "CONSUMIDOR", 7f, true, Element.ALIGN_CENTER)
        val dest = n.destinatario
        val ed = dest?.endereco
        fun linha(top: Float, rotulo: String, valor: String?) {
            d.t(ESQ, top + 1f, 32f, rotulo, 5f, true, Element.ALIGN_RIGHT)
            d.t(51f, top, 520f, valor, 7f)
        }
        if (dest == null || dest.cnpjCpf.isBlank()) {
            d.t(ESQ, 625f, LARG, "CONSUMIDOR NÃO IDENTIFICADO", 7f, alinhamento = Element.ALIGN_CENTER)
        } else {
            linha(617f, "CPF/CNPJ:", Formato.cnpjCpf(dest.cnpjCpf))
            linha(627f, "Nome:", n.nomeDestinatario)
            linha(647f, "Endereço:", ed?.let { "${it.logradouro} N. ${it.numero}" })
            linha(657f, "Bairro:", ed?.bairro)
            linha(667f, "Cidade:", ed?.municipio)
            linha(677f, "CEP:", ModeloSankhya.cep(ed?.cep))
        }

        d.caixaArredondada(ESQ, 715f, LARG, 102f)
        d.t(ESQ, 717f, LARG, "Consulta via leitor de QR Code", 7f, true, Element.ALIGN_CENTER)
        if (n.qrCode.isNotBlank()) d.qrCode(n.qrCode, 260f, 728f, 73f)
        d.t(ESQ, 807f, LARG, "Protocolo de Autorização ${n.protocolo} ${ModeloSankhya.dataHora(n.dataProtocolo)}", 7f, alinhamento = Element.ALIGN_CENTER)
        if (n.ambiente == "2") d.t(ESQ, 698f, LARG, "EMITIDA EM AMBIENTE DE HOMOLOGAÇÃO - SEM VALOR FISCAL", 8f, true, Element.ALIGN_CENTER)
    }
}
