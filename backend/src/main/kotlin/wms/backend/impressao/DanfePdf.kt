package wms.backend.impressao

import com.lowagie.text.Document
import com.lowagie.text.Element
import com.lowagie.text.PageSize
import com.lowagie.text.pdf.PdfWriter
import wms.backend.impressao.ModeloSankhya.campoSk
import wms.backend.impressao.ModeloSankhya.t
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * DANFE da NF-e (modelo 55) no layout do Sankhya — copiado do PDF que o Sankhya gera (TGFPDF, nota 72063):
 * Courier, caixas arredondadas, logo, canhoto com cliente/região/valor por extenso, fatura com o tipo de
 * negociação e "OC: n" nas informações complementares. Itens em Arial 6, 24 por folha na 1ª; as demais folhas
 * repetem o quadro do emitente e continuam a tabela.
 */
object DanfePdf {
    private const val ESQ = 30f
    private const val DIR = 581f
    private const val ALT_ITEM = 10f
    private const val TOPO_ITENS_P1 = 461f
    private const val FIM_ITENS_P1 = 703f
    /** Nas folhas seguintes o quadro do emitente sobe (sem canhoto) e a tabela vai até o pé. */
    private const val DESLOC_PN = -66f
    private const val FIM_ITENS_PN = 815f

    /** Limites das colunas da tabela de produtos (x) e alinhamento do valor. */
    private val xs = floatArrayOf(31f, 69f, 194f, 234f, 255f, 277f, 295f, 331f, 367f, 395f, 422f, 457f, 487f, 517f, 544f, 563f, 581f)
    private val rotulos = listOf("CÓD. PROD", "DESCRIÇÃO DOS PRODUTOS/SERVIÇOS", "NCM/SH", "CST", "CFOP", "UN.", "QUANT.",
        "V. UNITÁRIO", "V. DESC.", "% DESC.", "V. TOTAL", "BC ICMS", "V. ICMS", "VALOR\nIPI", "ICMS", "IPI")
    private val alinhamentos = listOf(Element.ALIGN_CENTER, Element.ALIGN_LEFT) + List(5) { Element.ALIGN_CENTER } +
        List(9) { Element.ALIGN_RIGHT }

    private val modalidadesFrete = mapOf(
        "0" to "0 - Emitente", "1" to "1 - Destinatário", "2" to "2 - Terceiros",
        "3" to "3 - Próprio Remet.", "4" to "4 - Próprio Dest.", "9" to "9 - Sem Frete",
    )

    fun desenhar(documento: Document, writer: PdfWriter, nota: NotaNfe) {
        val porFolha1 = ((FIM_ITENS_P1 - TOPO_ITENS_P1) / ALT_ITEM).toInt()
        val topoPn = TOPO_ITENS_P1 + DESLOC_PN - 70f
        val porFolhaN = ((FIM_ITENS_PN - topoPn) / ALT_ITEM).toInt()
        val folhas = mutableListOf(nota.itens.take(porFolha1))
        var resto = nota.itens.drop(porFolha1)
        while (resto.isNotEmpty()) {
            folhas += resto.take(porFolhaN)
            resto = resto.drop(porFolhaN)
        }
        folhas.forEachIndexed { i, itens ->
            documento.setPageSize(PageSize.A4)
            documento.newPage()
            writer.isPageEmpty = false
            val d = Desenho(writer.directContent, PageSize.A4.height, ModeloSankhya.courier, ModeloSankhya.courierNegrito)
            val folha = "FOLHA ${i + 1}/${folhas.size}"
            if (i == 0) {
                canhoto(d, nota)
                emitente(d, nota, 0f, folha)
                destinatario(d, nota)
                fatura(d, nota)
                impostos(d, nota)
                transporte(d, nota)
                tabelaItens(d, itens, 441f, FIM_ITENS_P1)
                issqnEAdicionais(d, nota)
            } else {
                emitente(d, nota, DESLOC_PN, folha)
                tabelaItens(d, itens, topoPn - 20f, FIM_ITENS_PN)
            }
        }
    }

    private fun canhoto(d: Desenho, n: NotaNfe) {
        val ex = n.extras
        d.caixaArredondada(ESQ, 22f, 454f, 40f)
        d.t(34f, 26f, 446f, "RECEBEMOS DE ${n.emitente.nome} OS PRODUTOS CONSTANTES DA NOTA FISCAL INDICADA AO LADO", 7f)
        val vNf = n.totais["vNF"] ?: BigDecimal.ZERO
        val cliente = listOf(ex?.codParc.orEmpty(), n.nomeDestinatario, ex?.regiao.orEmpty()).filter { it.isNotBlank() }.joinToString(" - ")
        d.t(34f, 34f, 446f, "$cliente - (R$ ${Formato.dinheiro(vNf)})(${ModeloSankhya.extenso(vNf)})", 7f)
        d.caixaArredondada(ESQ, 62f, 120f, 20f)
        d.t(32f, 65f, 116f, "DATA DE RECEBIMENTO", 6f)
        d.caixaArredondada(150f, 62f, 334f, 20f)
        d.t(152f, 65f, 330f, "IDENTIFICAÇÃO E ASSINATURA DO RECEBEDOR", 6f)
        d.caixaArredondada(484f, 22f, 97f, 60f)
        d.t(486f, 24f, 93f, "NF-e", 12f, true, Element.ALIGN_CENTER)
        ModeloSankhya.codigoBarras(d, (ex?.nunota ?: 0L).takeIf { it > 0 }?.toString() ?: n.numero, 489f, 37f, 87f, 18f)
        d.t(488f, 60f, 90f, "N. ${ModeloSankhya.numero(n.numero)}", 8f, true)
        d.t(488f, 69f, 90f, "SÉRIE ${n.serie}", 8f, true)
        d.linha(ESQ, 86.5f, DIR, 86.5f, 1f, tracejada = true)
    }

    /** Quadro do emitente / DANFE / chave (y 88–214); [dy] sobe o quadro nas folhas seguintes. */
    private fun emitente(d: Desenho, n: NotaNfe, dy: Float, folha: String) {
        val e = n.emitente
        val ed = e.endereco
        d.caixaArredondada(ESQ, 88f + dy, 248f, 94f)
        ModeloSankhya.logo(d, n.extras?.logo, 34f, 91f + dy, 76f, 72f)
        val nomes = d.quebrar(e.nome, 150f, 8f, bold = true).take(2)
        nomes.forEachIndexed { k, l -> d.t(120f, 95f + dy + k * 9f, 155f, l, 8f, true) }
        d.t(120f, 128f + dy, 155f, listOf("${ed.logradouro} N.${ed.numero}", ed.complemento).filter { it.isNotBlank() }.joinToString(" - "), 7f)
        d.t(120f, 136f + dy, 155f, "Bairro ${ed.bairro},${ed.municipio} - ${ed.uf}", 7f)
        d.t(120f, 144f + dy, 155f, "Fone: ${Formato.fone(ed.fone)}, CEP:${ed.cep}", 7f)

        d.caixaArredondada(278f, 88f + dy, 78f, 94f)
        d.t(278f, 90f + dy, 78f, "DANFE", 12f, true, Element.ALIGN_CENTER)
        d.t(278f, 103f + dy, 78f, "Documento", 7f, alinhamento = Element.ALIGN_CENTER)
        d.t(278f, 111f + dy, 78f, "Auxiliar da Nota", 7f, alinhamento = Element.ALIGN_CENTER)
        d.t(278f, 119f + dy, 78f, "Fiscal Eletrônica", 7f, alinhamento = Element.ALIGN_CENTER)
        d.t(281f, 140f + dy, 60f, "0 - ENTRADA", 7f)
        d.t(281f, 147f + dy, 60f, "1 - SAÍDA", 7f)
        d.caixa(337f, 140f + dy, 14f, 15f, 0.5f)
        d.t(337f, 145f + dy, 14f, n.tipoNf, 6f, true, Element.ALIGN_CENTER)
        d.t(281f, 158f + dy, 74f, "N. ${ModeloSankhya.numero(n.numero)}", 7f, true)
        d.t(281f, 166f + dy, 74f, "SÉRIE ${n.serie}", 7f, true)
        d.t(281f, 174f + dy, 74f, folha, 7f, true, Element.ALIGN_RIGHT)

        d.caixaArredondada(356f, 88f + dy, 225f, 36f)
        ModeloSankhya.codigoBarras(d, n.chave, 380f, 95f + dy, 188f, 25f)
        d.caixaArredondada(356f, 124f + dy, 225f, 20f)
        d.t(359f, 126f + dy, 220f, "CHAVE DE ACESSO", 6f)
        d.t(359f, 135f + dy, 220f, Formato.chave(n.chave), 6f, true, Element.ALIGN_CENTER)
        d.caixaArredondada(356f, 144f + dy, 225f, 38f)
        d.t(361f, 147f + dy, 218f, "Consulta de autenticidade no portal nacional da NF-e", 6f)
        d.t(361f, 154f + dy, 218f, "www.nfe.fazenda.gov.br/portal ou no site da Sefaz", 6f)
        d.t(361f, 161f + dy, 218f, "Autorizadora", 6f)

        d.campoSk(ESQ, 182f + dy, 369f, 198f + dy, "NATUREZA DA OPERAÇÃO", n.naturezaOperacao)
        d.campoSk(369f, 182f + dy, DIR, 198f + dy, "PROTOCOLO DE AUTORIZAÇÃO DE USO", "${n.protocolo} ${ModeloSankhya.dataHora(n.dataProtocolo)}", tamValor = 7f)
        d.campoSk(ESQ, 198f + dy, 210f, 214f + dy, "INSCRIÇÃO ESTADUAL", e.ie)
        d.campoSk(210f, 198f + dy, 397f, 214f + dy, "INSC. ESTADUAL DO SUBST. TRIBUTÁRIO", e.ieSt)
        d.campoSk(397f, 198f + dy, DIR, 214f + dy, "CNPJ", Formato.cnpjCpf(e.cnpjCpf))
    }

    private fun destinatario(d: Desenho, n: NotaNfe) {
        val dest = n.destinatario
        val ed = dest?.endereco
        d.t(33f, 225f, 300f, "DESTINATÁRIO/REMETENTE", 5f, true)
        d.caixaArredondada(ESQ, 232f, 331f, 38f)
        d.t(32f, 234f, 200f, "NOME/RAZÃO SOCIAL", 5f)
        d.t(33f, 238f, 265f, n.nomeDestinatario, 7f)
        d.t(300f, 239f, 58f, n.extras?.codParc, 8f, alinhamento = Element.ALIGN_RIGHT)
        d.caixaArredondada(361f, 232f, 139f, 38f)
        d.t(363f, 234f, 130f, "CNPJ/CPF", 5f)
        d.t(364f, 239f, 134f, Formato.cnpjCpf(dest?.cnpjCpf), 8f)
        d.caixaArredondada(500f, 232f, 81f, 38f)
        d.t(502f, 234f, 78f, "DATA DA EMISSÃO", 5f)
        d.t(503f, 239f, 77f, ModeloSankhya.dataTraco(n.emissao), 8f)
        d.campoSk(ESQ, 270f, 295f, 286f, "ENDEREÇO", ed?.let { listOf("${it.logradouro} N. ${it.numero}", it.complemento).filter { s -> s.isNotBlank() }.joinToString(" - ") })
        d.campoSk(295f, 270f, 432f, 286f, "BAIRRO/DISTRITO", ed?.bairro)
        d.campoSk(432f, 270f, 500f, 286f, "CEP", ModeloSankhya.cep(ed?.cep))
        d.campoSk(500f, 270f, DIR, 286f, "DATA DA ENTRADA/SAÍDA", ModeloSankhya.dataTraco(n.saida.ifBlank { n.emissao }))
        d.campoSk(ESQ, 286f, 214f, 302f, "MUNICÍPIO", ed?.municipio)
        d.campoSk(214f, 286f, 345f, 302f, "FONE/FAX", Formato.fone(ed?.fone))
        d.campoSk(345f, 286f, 380f, 302f, "UF", ed?.uf)
        d.campoSk(380f, 286f, 500f, 302f, "INSCRIÇÃO ESTADUAL", dest?.ie)
        d.campoSk(500f, 286f, DIR, 302f, "HORA DA SAÍDA", Formato.hora(n.saida.ifBlank { n.emissao }).let { h -> n.saida.ifBlank { n.emissao }.takeIf { it.length >= 19 }?.substring(11, 19) ?: h })
    }

    /** "BOLETO 23 DIAS| BOL=001 Venc=12/11/2026 Valor=39,60" — tipo de negociação (TGFTPV) + duplicatas do XML. */
    private fun fatura(d: Desenho, n: NotaNfe) {
        d.t(33f, 305f, 300f, "FATURA/DUPLICATA", 5f, true)
        d.caixaArredondada(ESQ, 310f, 550f, 30f)
        val dups = n.duplicatas.map { "BOL=${it.numero} Venc=${Formato.data(it.vencimento)} Valor=${Formato.dinheiro(it.valor)}" }
        val texto = (listOf(n.extras?.tipoNegociacao.orEmpty()).filter { it.isNotBlank() } + dups).joinToString("| ")
        d.paragrafo(33f, 312f, 544f, 26f, texto, 6f, 7f)
    }

    private fun impostos(d: Desenho, n: NotaNfe) {
        val tt = n.totais
        fun v(k: String) = Formato.dinheiro(tt[k])
        d.t(33f, 343f, 300f, "CÁLCULO DE IMPOSTO", 5f, true)
        val l1 = listOf(30f to "BASE DE CÁLCULO DO ICMS" to "vBC", 143f to "VALOR DO ICMS" to "vICMS", 256f to "BASE DE CÁLCULO DO ICMS ST" to "vBCST",
            369f to "VALOR DO ICMS ST" to "vST", 482f to "VALOR TOTAL DOS PRODUTOS" to "vProd")
        l1.forEachIndexed { i, (xr, k) ->
            val x1 = l1.getOrNull(i + 1)?.first?.first ?: DIR
            d.campoSk(xr.first, 349f, x1, 365f, xr.second, v(k), Element.ALIGN_RIGHT)
        }
        val outras = (tt["vOutro"] ?: BigDecimal.ZERO)
        val l2 = listOf(30f to "VALOR DO FRETE" to v("vFrete"), 122f to "VALOR DO SEGURO" to v("vSeg"), 214f to "DESCONTO" to v("vDesc"),
            306f to "OUTRAS DESPESAS ACESSÓRIAS" to Formato.dinheiro(outras), 408f to "VALOR DO IPI" to v("vIPI"))
        l2.forEachIndexed { i, (xr, valor) ->
            val x1 = l2.getOrNull(i + 1)?.first?.first ?: 490f
            d.campoSk(xr.first, 365f, x1, 381f, xr.second, valor, Element.ALIGN_RIGHT)
        }
        d.campoSk(490f, 365f, DIR, 381f, "VALOR TOTAL DA NOTA", v("vNF"), Element.ALIGN_RIGHT, boldRotulo = true, boldValor = true)
    }

    private fun transporte(d: Desenho, n: NotaNfe) {
        val tr = n.transportadora
        d.t(32f, 385f, 300f, "TRANSPORTADOR/VOLUMES TRANSPORTADOS", 5f, true)
        d.campoSk(ESQ, 392f, 268f, 408f, "RAZÃO SOCIAL", tr?.nome)
        d.caixaArredondada(268f, 392f, 73f, 16f)
        d.t(271f, 394f, 68f, "FRETE POR CONTA", 4f)
        d.t(271f, 399f, 68f, modalidadesFrete[n.modFrete] ?: n.modFrete, 8f, alinhamento = Element.ALIGN_CENTER)
        d.campoSk(341f, 392f, 392f, 408f, "CÓDIGO ANTT", "")
        d.campoSk(392f, 392f, 456f, 408f, "PLACA DO VEÍCULO", n.placa)
        d.campoSk(456f, 392f, 480f, 408f, "UF", n.placaUf)
        d.campoSk(480f, 392f, DIR, 408f, "CNPJ/CPF", Formato.cnpjCpf(tr?.cnpjCpf))
        d.campoSk(ESQ, 408f, 268f, 424f, "ENDEREÇO", tr?.endereco?.logradouro)
        d.campoSk(268f, 408f, 456f, 424f, "MUNICÍPIO", tr?.endereco?.municipio)
        d.campoSk(456f, 408f, 480f, 424f, "UF", tr?.endereco?.uf)
        d.campoSk(480f, 408f, DIR, 424f, "INSCRIÇÃO ESTADUAL", tr?.ie)
        val qtd = n.volQtd.toBigDecimalOrNull()
        d.campoSk(ESQ, 424f, 103f, 440f, "QUANTIDADE", qtd?.let { ModeloSankhya.quantidade(it, 2) } ?: n.volQtd, Element.ALIGN_RIGHT)
        d.campoSk(103f, 424f, 180f, 440f, "ESPÉCIE", n.volEspecie)
        d.campoSk(180f, 424f, 268f, 440f, "MARCA", n.volMarca)
        d.campoSk(268f, 424f, 370f, 440f, "NÚMERO", n.volNumeracao)
        d.campoSk(370f, 424f, 480f, 440f, "PESO BRUTO", n.pesoBruto?.let { "${ModeloSankhya.quantidade(it, 4)} Kg" }, Element.ALIGN_RIGHT)
        d.campoSk(480f, 424f, DIR, 440f, "PESO LÍQUIDO", n.pesoLiquido?.let { "${ModeloSankhya.quantidade(it, 4)} Kg" }, Element.ALIGN_RIGHT)
    }

    /** Título + cabeçalho da tabela em [top]; linhas de 10 pt até [fim]. */
    private fun tabelaItens(d: Desenho, itens: List<ItemNfe>, top: Float, fim: Float) {
        d.t(32f, top, 300f, "DADOS DOS PRODUTOS/SERVIÇOS", 5f, true)
        val cab = top + 7f
        val corpo = cab + 13f
        val n = xs.size - 1
        // Cabeçalho: alíquota ICMS/IPI dividida em duas linhas.
        for (k in 0 until n) {
            val x0 = xs[k]
            val x1 = xs[k + 1]
            when (k) {
                14, 15 -> {
                    d.caixa(x0, cab + 7f, x1 - x0, 6f, 0.25f)
                    d.t(x0, cab + 8f, x1 - x0, rotulos[k], 5f, alinhamento = Element.ALIGN_CENTER)
                }
                else -> {
                    d.caixa(x0, cab, x1 - x0, 13f, 0.25f)
                    val linhas = rotulos[k].split('\n')
                    linhas.forEachIndexed { j, l ->
                        d.t(x0, cab + (if (linhas.size == 1) 4f else 1f + j * 6f), x1 - x0, l, 5f, alinhamento = Element.ALIGN_CENTER)
                    }
                }
            }
        }
        d.caixa(xs[14], cab, xs[16] - xs[14], 7f, 0.25f)
        d.t(xs[14], cab + 1f, xs[16] - xs[14], "ALÍQUOTA", 5f, alinhamento = Element.ALIGN_CENTER)

        val a = d.comFonte(ModeloSankhya.arial, ModeloSankhya.arialNegrito)
        var y = corpo
        itens.forEach { it ->
            if (y + ALT_ITEM > fim + 0.5f) return@forEach
            val pDesc = if (it.valorTotal.signum() > 0) it.valorDesconto.multiply(BigDecimal(100)).divide(it.valorTotal, 2, RoundingMode.HALF_UP) else BigDecimal.ZERO
            val valores = listOf(
                it.codigo, it.descricao, it.ncm, it.cst, it.cfop, it.unidade,
                Formato.quantidade(it.quantidade, 4, 0), Formato.quantidade(it.valorUnitario, 4, 2), Formato.dinheiro(it.valorDesconto),
                Formato.dinheiro(pDesc), Formato.dinheiro(it.valorTotal), Formato.dinheiro(it.bcIcms), Formato.dinheiro(it.valorIcms),
                Formato.dinheiro(it.valorIpi), Formato.dinheiro(it.aliqIcms), Formato.dinheiro(it.aliqIpi),
            )
            for (k in 0 until n) {
                d.linha(xs[k], y, xs[k], y + ALT_ITEM, 0.25f)
                a.t(xs[k] + 2f, y + 3f, xs[k + 1] - xs[k] - 4f, valores[k], 6f, alinhamento = alinhamentos[k])
            }
            d.linha(xs[n], y, xs[n], y + ALT_ITEM, 0.25f)
            d.linha(xs[0], y + ALT_ITEM, xs[n], y + ALT_ITEM, 0.5f)
            y += ALT_ITEM
        }
    }

    private fun issqnEAdicionais(d: Desenho, n: NotaNfe) {
        d.t(31f, 707f, 300f, "CÁLCULO DO ISSQN", 5f, true)
        d.campoSk(ESQ, 713f, 167f, 729f, "INSCRIÇÃO MUNICIPAL", "")
        d.campoSk(167f, 713f, 304f, 729f, "VALOR TOTAL DOS SERVIÇOS", Formato.dinheiro(n.totais["vServ"]), Element.ALIGN_RIGHT, tamValor = 6f)
        d.campoSk(304f, 713f, 441f, 729f, "BASE DE CÁLCULO DE ISSQN", Formato.dinheiro(n.totais["vBCISS"]), Element.ALIGN_RIGHT, tamValor = 6f)
        d.campoSk(441f, 713f, 580f, 729f, "VALOR DO ISSQN", Formato.dinheiro(n.totais["vISS"]), Element.ALIGN_RIGHT, tamValor = 6f)

        d.t(31f, 731f, 300f, "DADOS ADICIONAIS", 5f, true)
        d.caixaArredondada(ESQ, 737f, 320f, 82f)
        d.t(32f, 739f, 300f, "INFORMAÇÕES COMPLEMENTARES", 5f)
        val oc = n.extras?.ordemCarga?.let { "OC: $it" }
        var y = 743f
        listOfNotNull(oc, n.infFisco.ifBlank { null }, n.infCpl.ifBlank { null }).forEach { bloco ->
            d.quebrar(bloco, 316f, 6f).forEach { l ->
                if (y > 812f) return@forEach
                d.t(31f, y, 318f, l, 6f)
                y += 7f
            }
        }
        d.caixaArredondada(350f, 737f, 230f, 82f)
        d.t(352f, 739f, 226f, "RESERVADO AO FISCO", 5f)
    }
}
