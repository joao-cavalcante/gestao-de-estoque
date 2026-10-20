package wms.backend.impressao

import com.lowagie.text.Document
import com.lowagie.text.Element
import com.lowagie.text.PageSize
import com.lowagie.text.pdf.Barcode128
import com.lowagie.text.pdf.PdfWriter
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * DANFE retrato A4 da NF-e (modelo 55), no layout do Manual de Orientação do Contribuinte (anexo II):
 * canhoto, cabeçalho com código de barras da chave, destinatário, faturas, cálculo do imposto,
 * transportador, produtos (paginados — folhas seguintes repetem o cabeçalho) e dados adicionais.
 */
object DanfePdf {
    private const val ESQ = 15f
    private const val LARG = 565f

    private const val TOPO_TABELA_P1 = 458f
    private const val FIM_TABELA_P1 = 747f
    private const val TOPO_TABELA_PN = 135f
    private const val FIM_TABELA_PN = 827f
    private const val CAB_TABELA = 14f
    private const val FONTE_ITEM = 6f
    private const val ENTRELINHA_ITEM = 7f

    private data class Coluna(val rotulo1: String, val rotulo2: String, val largura: Float, val alinhamento: Int)

    private val colunas = listOf(
        Coluna("CÓDIGO", "PRODUTO", 45f, Element.ALIGN_LEFT),
        Coluna("DESCRIÇÃO DO PRODUTO / SERVIÇO", "", 165f, Element.ALIGN_LEFT),
        Coluna("NCM/SH", "", 38f, Element.ALIGN_CENTER),
        Coluna("O/CST", "", 22f, Element.ALIGN_CENTER),
        Coluna("CFOP", "", 22f, Element.ALIGN_CENTER),
        Coluna("UN", "", 18f, Element.ALIGN_CENTER),
        Coluna("QUANT.", "", 38f, Element.ALIGN_RIGHT),
        Coluna("VALOR", "UNIT.", 40f, Element.ALIGN_RIGHT),
        Coluna("VALOR", "TOTAL", 42f, Element.ALIGN_RIGHT),
        Coluna("B.CÁLC.", "ICMS", 40f, Element.ALIGN_RIGHT),
        Coluna("VALOR", "ICMS", 32f, Element.ALIGN_RIGHT),
        Coluna("VALOR", "IPI", 25f, Element.ALIGN_RIGHT),
        Coluna("ALÍQ.", "ICMS", 20f, Element.ALIGN_RIGHT),
        Coluna("ALÍQ.", "IPI", 18f, Element.ALIGN_RIGHT),
    )

    private val modalidadesFrete = mapOf(
        "0" to "0-Por conta do Remet.", "1" to "1-Por conta do Dest.", "2" to "2-Por conta de Terceiros",
        "3" to "3-Próprio p/ conta Remet.", "4" to "4-Próprio p/ conta Dest.", "9" to "9-Sem Transporte",
    )

    /** Acrescenta as folhas do DANFE da [nota] ao [documento] aberto. */
    fun desenhar(documento: Document, writer: PdfWriter, nota: NotaNfe) {
        val d0 = Desenho(writer.directContent, PageSize.A4.height)
        // Linhas de cada item (descrição + informação adicional do produto quebradas na coluna).
        val linhasItens = nota.itens.map { item ->
            val texto = if (item.infAdProd.isBlank()) item.descricao else "${item.descricao}\n${item.infAdProd}"
            d0.quebrar(texto, colunas[1].largura - 4, FONTE_ITEM).ifEmpty { listOf("") }
        }
        val paginas = paginar(linhasItens.map { it.size * ENTRELINHA_ITEM + 3f })
        paginas.forEachIndexed { indice, itensDaPagina ->
            documento.setPageSize(PageSize.A4)
            documento.newPage()
            writer.isPageEmpty = false
            val d = Desenho(writer.directContent, PageSize.A4.height)
            val folha = "${indice + 1}/${paginas.size}"
            if (indice == 0) {
                canhoto(d, nota)
                cabecalho(d, nota, 70f, folha)
                blocosFiscais(d, nota)
                tabela(d, nota, linhasItens, itensDaPagina, TOPO_TABELA_P1, FIM_TABELA_P1)
                dadosAdicionais(d, nota)
            } else {
                cabecalho(d, nota, 15f, folha)
                tabela(d, nota, linhasItens, itensDaPagina, TOPO_TABELA_PN, FIM_TABELA_PN)
            }
            if (nota.ambiente == "2") d.texto(ESQ, 600f, LARG, "SEM VALOR FISCAL — HOMOLOGAÇÃO", 28f, Element.ALIGN_CENTER, true)
            val agora = LocalDateTime.now(ZoneId.of("America/Sao_Paulo")).format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))
            d.texto(ESQ, 836f, LARG, "Impresso pelo WMS em $agora", 5f, Element.ALIGN_RIGHT)
        }
    }

    /** Distribui os itens nas folhas: a 1ª tem menos espaço (canhoto, blocos fiscais e dados adicionais). */
    private fun paginar(alturas: List<Float>): List<List<Int>> {
        val paginas = mutableListOf<MutableList<Int>>(mutableListOf())
        var livre = FIM_TABELA_P1 - TOPO_TABELA_P1 - 9f - CAB_TABELA
        alturas.forEachIndexed { i, h ->
            if (h > livre && paginas.last().isNotEmpty()) {
                paginas += mutableListOf<Int>()
                livre = FIM_TABELA_PN - TOPO_TABELA_PN - 9f - CAB_TABELA
            }
            paginas.last() += i
            livre -= h
        }
        return paginas
    }

    private fun numeroFormatado(n: String) = n.padStart(9, '0').let { "${it.substring(0, 3)}.${it.substring(3, 6)}.${it.substring(6)}" }

    private fun canhoto(d: Desenho, n: NotaNfe) {
        val top = 15f
        d.caixa(ESQ, top, 465f, 22f)
        d.paragrafo(
            ESQ + 2, top + 1, 461f, 21f,
            "RECEBEMOS DE ${n.emitente.nome} OS PRODUTOS E/OU SERVIÇOS CONSTANTES DA NOTA FISCAL ELETRÔNICA INDICADA AO LADO. " +
                "EMISSÃO: ${Formato.data(n.emissao)}  VALOR TOTAL: R$ ${Formato.dinheiro(n.totais["vNF"])}  " +
                "DESTINATÁRIO: ${n.destinatario?.nome ?: ""}",
            6f,
        )
        d.campo(ESQ, top + 22f, 110f, 23f, "DATA DE RECEBIMENTO", "")
        d.campo(ESQ + 110f, top + 22f, 355f, 23f, "IDENTIFICAÇÃO E ASSINATURA DO RECEBEDOR", "")
        d.caixa(ESQ + 465f, top, 100f, 45f)
        d.texto(ESQ + 465f, top + 14f, 100f, "NF-e", 12f, Element.ALIGN_CENTER, true)
        d.texto(ESQ + 465f, top + 27f, 100f, "Nº ${numeroFormatado(n.numero)}", 8.5f, Element.ALIGN_CENTER, true)
        d.texto(ESQ + 465f, top + 38f, 100f, "SÉRIE ${n.serie.padStart(3, '0')}", 8.5f, Element.ALIGN_CENTER, true)
        d.linha(ESQ, 65f, ESQ + LARG, 65f, 0.5f, tracejada = true)
    }

    private fun cabecalho(d: Desenho, n: NotaNfe, top: Float, folha: String) {
        val h = 115f
        // Emitente
        d.caixa(ESQ, top, 235f, h)
        d.texto(ESQ + 4, top + 9f, 227f, "IDENTIFICAÇÃO DO EMITENTE", 5f)
        val e = n.emitente
        var t = top + 22f
        d.quebrar(e.nome, 225f, 10f, true).take(2).forEach { d.texto(ESQ + 5, t, 225f, it, 10f, Element.ALIGN_CENTER, true); t += 12f }
        t += 6f
        val end = e.endereco
        listOf(
            listOf(end.logradouro, end.numero).filter { it.isNotBlank() }.joinToString(", ") + (if (end.complemento.isNotBlank()) " - ${end.complemento}" else ""),
            "${end.bairro} - CEP ${Formato.cep(end.cep)}",
            "${end.municipio} - ${end.uf}",
            if (end.fone.isNotBlank()) "Fone: ${Formato.fone(end.fone)}" else "",
        ).filter { it.isNotBlank() }.forEach { d.texto(ESQ + 5, t, 225f, it, 7.5f, Element.ALIGN_CENTER); t += 10f }

        // Quadro DANFE
        val xd = ESQ + 235f
        d.caixa(xd, top, 95f, h)
        d.texto(xd, top + 17f, 95f, "DANFE", 14f, Element.ALIGN_CENTER, true)
        d.texto(xd, top + 27f, 95f, "Documento Auxiliar da", 6.5f, Element.ALIGN_CENTER)
        d.texto(xd, top + 35f, 95f, "Nota Fiscal Eletrônica", 6.5f, Element.ALIGN_CENTER)
        d.texto(xd + 8, top + 50f, 60f, "0 - ENTRADA", 7f)
        d.texto(xd + 8, top + 59f, 60f, "1 - SAÍDA", 7f)
        d.caixa(xd + 68f, top + 45f, 16f, 16f)
        d.texto(xd + 68f, top + 57f, 16f, n.tipoNf, 11f, Element.ALIGN_CENTER, true)
        d.texto(xd, top + 78f, 95f, "Nº ${numeroFormatado(n.numero)}", 9f, Element.ALIGN_CENTER, true)
        d.texto(xd, top + 90f, 95f, "SÉRIE ${n.serie.padStart(3, '0')}", 9f, Element.ALIGN_CENTER, true)
        d.texto(xd, top + 104f, 95f, "FOLHA $folha", 8f, Element.ALIGN_CENTER, true)

        // Código de barras + chave + consulta
        val xc = xd + 95f
        val wc = LARG - 235f - 95f
        d.caixa(xc, top, wc, 42f)
        codigoBarras(d, n.chave, xc + 6f, top + 6f, wc - 12f, 30f)
        d.campo(xc, top + 42f, wc, 22f, "CHAVE DE ACESSO", Formato.chave(n.chave), Element.ALIGN_CENTER, 7.5f, true)
        d.caixa(xc, top + 64f, wc, h - 64f)
        d.paragrafo(
            xc + 8, top + 72f, wc - 16f, h - 70f,
            "Consulta de autenticidade no portal nacional da NF-e www.nfe.fazenda.gov.br/portal ou no site da Sefaz Autorizadora",
            8f, 10f,
        )

        val top2 = top + h
        d.campo(ESQ, top2, 330f, 20f, "NATUREZA DA OPERAÇÃO", n.naturezaOperacao)
        d.campo(
            ESQ + 330f, top2, LARG - 330f, 20f, "PROTOCOLO DE AUTORIZAÇÃO DE USO",
            "${n.protocolo} - ${Formato.data(n.dataProtocolo)} ${Formato.hora(n.dataProtocolo)}", Element.ALIGN_CENTER, 8f, true,
        )
        if (top == 15f) return // folhas seguintes: só até o protocolo
        val top3 = top2 + 20f
        d.campo(ESQ, top3, 188f, 20f, "INSCRIÇÃO ESTADUAL", e.ie)
        d.campo(ESQ + 188f, top3, 188f, 20f, "INSCRIÇÃO ESTADUAL DO SUBST. TRIBUT.", e.ieSt)
        d.campo(ESQ + 376f, top3, 189f, 20f, "CNPJ", Formato.cnpjCpf(e.cnpjCpf))
    }

    private fun codigoBarras(d: Desenho, chave: String, x: Float, top: Float, w: Float, h: Float) {
        if (chave.length != 44) return
        val barcode = Barcode128().apply {
            codeType = com.lowagie.text.pdf.Barcode.CODE128 // só dígitos: o encoder usa o conjunto C sozinho
            code = chave
            font = null
            barHeight = h
        }
        val img = barcode.createImageWithBarcode(d.cb, null, null)
        img.scaleAbsolute(w, h)
        img.setAbsolutePosition(x, d.y(top + h))
        d.cb.addImage(img)
    }

    private fun blocosFiscais(d: Desenho, n: NotaNfe) {
        val dest = n.destinatario
        val ed = dest?.endereco
        d.titulo(ESQ, 228f, "DESTINATÁRIO / REMETENTE")
        var t = 237f
        d.campo(ESQ, t, 345f, 20f, "NOME / RAZÃO SOCIAL", dest?.nome)
        d.campo(ESQ + 345f, t, 130f, 20f, "CNPJ / CPF", Formato.cnpjCpf(dest?.cnpjCpf), Element.ALIGN_CENTER)
        d.campo(ESQ + 475f, t, 90f, 20f, "DATA DA EMISSÃO", Formato.data(n.emissao), Element.ALIGN_CENTER)
        t += 20f
        val endereco = ed?.let { listOf(it.logradouro, it.numero).filter { s -> s.isNotBlank() }.joinToString(", ") + if (it.complemento.isNotBlank()) " - ${it.complemento}" else "" }
        d.campo(ESQ, t, 265f, 20f, "ENDEREÇO", endereco)
        d.campo(ESQ + 265f, t, 140f, 20f, "BAIRRO / DISTRITO", ed?.bairro)
        d.campo(ESQ + 405f, t, 70f, 20f, "CEP", Formato.cep(ed?.cep), Element.ALIGN_CENTER)
        d.campo(ESQ + 475f, t, 90f, 20f, "DATA DA SAÍDA/ENTRADA", Formato.data(n.saida), Element.ALIGN_CENTER)
        t += 20f
        d.campo(ESQ, t, 215f, 20f, "MUNICÍPIO", ed?.municipio)
        d.campo(ESQ + 215f, t, 110f, 20f, "FONE / FAX", Formato.fone(ed?.fone))
        d.campo(ESQ + 325f, t, 30f, 20f, "UF", ed?.uf, Element.ALIGN_CENTER)
        d.campo(ESQ + 355f, t, 120f, 20f, "INSCRIÇÃO ESTADUAL", dest?.ie)
        d.campo(ESQ + 475f, t, 90f, 20f, "HORA DA SAÍDA/ENTRADA", Formato.hora(n.saida), Element.ALIGN_CENTER)

        d.titulo(ESQ, 300f, "FATURA / DUPLICATAS")
        d.caixa(ESQ, 309f, LARG, 22f)
        val larguraDup = LARG / 8
        n.duplicatas.take(8).forEachIndexed { i, dup ->
            val x = ESQ + i * larguraDup
            d.texto(x + 3, 316f, larguraDup - 6, "Nº ${dup.numero}", 5.5f)
            d.texto(x + 3, 322.5f, larguraDup - 6, "Venc. ${Formato.data(dup.vencimento)}", 5.5f)
            d.texto(x + 3, 329f, larguraDup - 6, "Valor R$ ${Formato.dinheiro(dup.valor)}", 5.5f)
        }

        d.titulo(ESQ, 334f, "CÁLCULO DO IMPOSTO")
        val tt = n.totais
        val w6 = LARG / 6
        listOf(
            "BASE DE CÁLC. DO ICMS" to "vBC", "VALOR DO ICMS" to "vICMS", "BASE DE CÁLC. ICMS S.T." to "vBCST",
            "VALOR DO ICMS SUBST." to "vST", "V. APROX. TRIBUTOS" to "vTotTrib", "VALOR TOTAL DOS PRODUTOS" to "vProd",
        ).forEachIndexed { i, (rotulo, chave) -> d.campo(ESQ + i * w6, 343f, w6, 20f, rotulo, Formato.dinheiro(tt[chave]), Element.ALIGN_RIGHT) }
        listOf(
            "VALOR DO FRETE" to "vFrete", "VALOR DO SEGURO" to "vSeg", "DESCONTO" to "vDesc",
            "OUTRAS DESPESAS" to "vOutro", "VALOR TOTAL DO IPI" to "vIPI", "VALOR TOTAL DA NOTA" to "vNF",
        ).forEachIndexed { i, (rotulo, chave) ->
            d.campo(ESQ + i * w6, 363f, w6, 20f, rotulo, Formato.dinheiro(tt[chave]), Element.ALIGN_RIGHT, bold = chave == "vNF")
        }

        d.titulo(ESQ, 386f, "TRANSPORTADOR / VOLUMES TRANSPORTADOS")
        val tr = n.transportadora
        t = 395f
        d.campo(ESQ, t, 200f, 20f, "NOME / RAZÃO SOCIAL", tr?.nome)
        d.campo(ESQ + 200f, t, 110f, 20f, "FRETE", modalidadesFrete[n.modFrete] ?: n.modFrete, tamanho = 7f)
        d.campo(ESQ + 310f, t, 60f, 20f, "CÓDIGO ANTT", "")
        d.campo(ESQ + 370f, t, 60f, 20f, "PLACA DO VEÍCULO", n.placa, Element.ALIGN_CENTER)
        d.campo(ESQ + 430f, t, 25f, 20f, "UF", n.placaUf, Element.ALIGN_CENTER)
        d.campo(ESQ + 455f, t, 110f, 20f, "CNPJ / CPF", Formato.cnpjCpf(tr?.cnpjCpf), Element.ALIGN_CENTER)
        t += 20f
        d.campo(ESQ, t, 250f, 20f, "ENDEREÇO", tr?.endereco?.logradouro)
        d.campo(ESQ + 250f, t, 150f, 20f, "MUNICÍPIO", tr?.endereco?.municipio)
        d.campo(ESQ + 400f, t, 25f, 20f, "UF", tr?.endereco?.uf, Element.ALIGN_CENTER)
        d.campo(ESQ + 425f, t, 140f, 20f, "INSCRIÇÃO ESTADUAL", tr?.ie)
        t += 20f
        d.campo(ESQ, t, 70f, 20f, "QUANTIDADE", n.volQtd, Element.ALIGN_RIGHT)
        d.campo(ESQ + 70f, t, 100f, 20f, "ESPÉCIE", n.volEspecie)
        d.campo(ESQ + 170f, t, 100f, 20f, "MARCA", n.volMarca)
        d.campo(ESQ + 270f, t, 95f, 20f, "NUMERAÇÃO", n.volNumeracao)
        d.campo(ESQ + 365f, t, 100f, 20f, "PESO BRUTO", n.pesoBruto?.let { Formato.quantidade(it, 3, 3) }, Element.ALIGN_RIGHT)
        d.campo(ESQ + 465f, t, 100f, 20f, "PESO LÍQUIDO", n.pesoLiquido?.let { Formato.quantidade(it, 3, 3) }, Element.ALIGN_RIGHT)
    }

    private fun tabela(d: Desenho, n: NotaNfe, linhasItens: List<List<String>>, itens: List<Int>, topo: Float, fim: Float) {
        d.titulo(ESQ, topo, "DADOS DOS PRODUTOS / SERVIÇOS")
        val topCab = topo + 9f
        d.caixa(ESQ, topCab, LARG, fim - topCab)
        d.linha(ESQ, topCab + CAB_TABELA, ESQ + LARG, topCab + CAB_TABELA)
        var x = ESQ
        colunas.forEachIndexed { i, c ->
            if (i > 0) d.linha(x, topCab, x, fim, 0.3f)
            if (c.rotulo2.isBlank()) {
                d.texto(x + 1, topCab + 9f, c.largura - 2, c.rotulo1, 5f, Element.ALIGN_CENTER)
            } else {
                d.texto(x + 1, topCab + 6f, c.largura - 2, c.rotulo1, 5f, Element.ALIGN_CENTER)
                d.texto(x + 1, topCab + 12f, c.largura - 2, c.rotulo2, 5f, Element.ALIGN_CENTER)
            }
            x += c.largura
        }
        var t = topCab + CAB_TABELA
        for (indice in itens) {
            val item = n.itens[indice]
            val linhas = linhasItens[indice]
            val base = t + FONTE_ITEM + 1.5f
            val valores = listOf(
                item.codigo, null, item.ncm, item.cst, item.cfop, item.unidade,
                Formato.quantidade(item.quantidade, 4), Formato.quantidade(item.valorUnitario, 4, 2), Formato.dinheiro(item.valorTotal),
                Formato.dinheiro(item.bcIcms), Formato.dinheiro(item.valorIcms), Formato.dinheiro(item.valorIpi),
                Formato.quantidade(item.aliqIcms, 2), Formato.quantidade(item.aliqIpi, 2),
            )
            var xc = ESQ
            colunas.forEachIndexed { i, c ->
                if (i == 1) {
                    linhas.forEachIndexed { l, s -> d.texto(xc + 2, base + l * ENTRELINHA_ITEM, c.largura - 4, s, FONTE_ITEM) }
                } else {
                    d.texto(xc + 2, base, c.largura - 4, valores[i], FONTE_ITEM, c.alinhamento)
                }
                xc += c.largura
            }
            t += linhas.size * ENTRELINHA_ITEM + 3f
            d.linha(ESQ, t, ESQ + LARG, t, 0.1f)
        }
    }

    private fun dadosAdicionais(d: Desenho, n: NotaNfe) {
        d.titulo(ESQ, 750f, "DADOS ADICIONAIS")
        d.caixa(ESQ, 759f, 380f, 68f)
        d.texto(ESQ + 2, 765f, 376f, "INFORMAÇÕES COMPLEMENTARES", 5f)
        val info = listOf(n.infFisco, n.infCpl).filter { it.isNotBlank() }.joinToString("\n").replace(";", "; ")
        d.paragrafo(ESQ + 3, 768f, 374f, 58f, info, 6f, 7f)
        d.caixa(ESQ + 380f, 759f, 185f, 68f)
        d.texto(ESQ + 382f, 765f, 181f, "RESERVADO AO FISCO", 5f)
    }
}
