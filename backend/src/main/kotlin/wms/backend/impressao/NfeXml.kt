package wms.backend.impressao

import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.math.BigDecimal
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Leitura do `<nfeProc>` autorizado (TGFNFE.XMLENVCLI do Sankhya) — só o que o DANFE precisa.
 * Sem namespace de propósito: as tags do layout 4.00 são únicas o bastante pelo nome.
 */
data class EnderecoNfe(
    val logradouro: String,
    val numero: String,
    val complemento: String,
    val bairro: String,
    val municipio: String,
    val uf: String,
    val cep: String,
    val fone: String,
)

data class ParticipanteNfe(
    val nome: String,
    val fantasia: String,
    val cnpjCpf: String,
    val ie: String,
    val ieSt: String,
    val endereco: EnderecoNfe,
)

data class ItemNfe(
    val codigo: String,
    val descricao: String,
    val ncm: String,
    val cst: String,
    val cfop: String,
    val unidade: String,
    val quantidade: BigDecimal,
    val valorUnitario: BigDecimal,
    val valorTotal: BigDecimal,
    val bcIcms: BigDecimal,
    val valorIcms: BigDecimal,
    val valorIpi: BigDecimal,
    val aliqIcms: BigDecimal,
    val aliqIpi: BigDecimal,
    val infAdProd: String,
)

data class DuplicataNfe(val numero: String, val vencimento: String, val valor: BigDecimal)

data class PagamentoNfe(val forma: String, val valor: BigDecimal)

data class NotaNfe(
    val modelo: String,
    val serie: String,
    val numero: String,
    val chave: String,
    val tipoNf: String,
    val naturezaOperacao: String,
    val emissao: String,
    val saida: String,
    val protocolo: String,
    val dataProtocolo: String,
    val ambiente: String,
    val emitente: ParticipanteNfe,
    val destinatario: ParticipanteNfe?,
    val itens: List<ItemNfe>,
    val totais: Map<String, BigDecimal>,
    val modFrete: String,
    val transportadora: ParticipanteNfe?,
    val placa: String,
    val placaUf: String,
    val volQtd: String,
    val volEspecie: String,
    val volMarca: String,
    val volNumeracao: String,
    val pesoBruto: BigDecimal?,
    val pesoLiquido: BigDecimal?,
    val duplicatas: List<DuplicataNfe>,
    val pagamentos: List<PagamentoNfe>,
    val troco: BigDecimal?,
    val infCpl: String,
    val infFisco: String,
    val qrCode: String,
    val urlChave: String,
)

object NfeXml {
    fun ler(xml: String): NotaNfe {
        val fabrica = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }
        val raiz = fabrica.newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8))).documentElement
        val inf = raiz.primeiro("infNFe") ?: error("XML sem <infNFe>")
        val ide = inf.filho("ide")
        val emit = inf.filho("emit")
        val dest = inf.filho("dest")
        val total = inf.filho("total")?.filho("ICMSTot")
        val transp = inf.filho("transp")
        val vol = transp?.filho("vol")
        val prot = raiz.primeiro("infProt")
        val adic = inf.filho("infAdic")
        val supl = raiz.primeiro("infNFeSupl")

        return NotaNfe(
            modelo = ide.txt("mod"),
            serie = ide.txt("serie"),
            numero = ide.txt("nNF"),
            chave = prot.txt("chNFe").ifBlank { inf.getAttribute("Id").removePrefix("NFe") },
            tipoNf = ide.txt("tpNF"),
            naturezaOperacao = ide.txt("natOp"),
            emissao = ide.txt("dhEmi"),
            saida = ide.txt("dhSaiEnt"),
            protocolo = prot.txt("nProt"),
            dataProtocolo = prot.txt("dhRecbto"),
            ambiente = ide.txt("tpAmb"),
            emitente = participante(emit, "enderEmit")!!,
            destinatario = participante(dest, "enderDest"),
            itens = inf.filhos("det").map { item(it) },
            totais = total?.let { t ->
                t.childNodes.let { nos -> (0 until nos.length).mapNotNull { nos.item(it) as? Element } }
                    .associate { it.tagName to num(it.textContent) }
            } ?: emptyMap(),
            modFrete = transp.txt("modFrete"),
            transportadora = transp?.filho("transporta")?.let { t ->
                ParticipanteNfe(
                    nome = t.txt("xNome"), fantasia = "", cnpjCpf = t.txt("CNPJ").ifBlank { t.txt("CPF") }, ie = t.txt("IE"), ieSt = "",
                    endereco = EnderecoNfe(t.txt("xEnder"), "", "", "", t.txt("xMun"), t.txt("UF"), "", ""),
                )
            },
            placa = transp?.filho("veicTransp").txt("placa"),
            placaUf = transp?.filho("veicTransp").txt("UF"),
            volQtd = vol.txt("qVol"),
            volEspecie = vol.txt("esp"),
            volMarca = vol.txt("marca"),
            volNumeracao = vol.txt("nVol"),
            pesoBruto = vol?.filho("pesoB")?.let { num(it.textContent) },
            pesoLiquido = vol?.filho("pesoL")?.let { num(it.textContent) },
            duplicatas = inf.filho("cobr")?.filhos("dup")?.map { DuplicataNfe(it.txt("nDup"), it.txt("dVenc"), num(it.txt("vDup"))) } ?: emptyList(),
            pagamentos = inf.filho("pag")?.filhos("detPag")?.map { PagamentoNfe(it.txt("tPag"), num(it.txt("vPag"))) } ?: emptyList(),
            troco = inf.filho("pag")?.filho("vTroco")?.let { num(it.textContent) },
            infCpl = adic.txt("infCpl"),
            infFisco = adic.txt("infAdFisco"),
            qrCode = supl.txt("qrCode").trim(),
            urlChave = supl.txt("urlChave").trim(),
        )
    }

    private fun participante(e: Element?, tagEndereco: String): ParticipanteNfe? {
        if (e == null) return null
        val end = e.filho(tagEndereco)
        return ParticipanteNfe(
            nome = e.txt("xNome"),
            fantasia = e.txt("xFant"),
            cnpjCpf = e.txt("CNPJ").ifBlank { e.txt("CPF") }.ifBlank { e.txt("idEstrangeiro") },
            ie = e.txt("IE"),
            ieSt = e.txt("IEST"),
            endereco = EnderecoNfe(
                end.txt("xLgr"), end.txt("nro"), end.txt("xCpl"), end.txt("xBairro"),
                end.txt("xMun"), end.txt("UF"), end.txt("CEP"), end.txt("fone"),
            ),
        )
    }

    private fun item(det: Element): ItemNfe {
        val prod = det.filho("prod")
        val imposto = det.filho("imposto")
        // ICMS vem num filho variável (ICMS00, ICMS20, ICMSSN102...): pega o primeiro.
        val icms = imposto?.filho("ICMS")?.let { e -> (0 until e.childNodes.length).mapNotNull { e.childNodes.item(it) as? Element }.firstOrNull() }
        val ipiTrib = imposto?.filho("IPI")?.filho("IPITrib")
        val cst = icms?.let { it.txt("orig") + it.txt("CST").ifBlank { it.txt("CSOSN") } } ?: ""
        return ItemNfe(
            codigo = prod.txt("cProd"),
            descricao = prod.txt("xProd"),
            ncm = prod.txt("NCM"),
            cst = cst,
            cfop = prod.txt("CFOP"),
            unidade = prod.txt("uCom"),
            quantidade = num(prod.txt("qCom")),
            valorUnitario = num(prod.txt("vUnCom")),
            valorTotal = num(prod.txt("vProd")),
            bcIcms = num(icms.txt("vBC")),
            valorIcms = num(icms.txt("vICMS")),
            valorIpi = num(ipiTrib.txt("vIPI")),
            aliqIcms = num(icms.txt("pICMS")),
            aliqIpi = num(ipiTrib.txt("pIPI")),
            infAdProd = det.txt("infAdProd"),
        )
    }

    private fun num(s: String?): BigDecimal = s?.trim()?.takeIf { it.isNotEmpty() }?.toBigDecimalOrNull() ?: BigDecimal.ZERO

    private fun Element.primeiro(tag: String): Element? = getElementsByTagName(tag).item(0) as? Element

    private fun Element.filhos(tag: String): List<Element> =
        (0 until childNodes.length).mapNotNull { childNodes.item(it) as? Element }.filter { it.tagName == tag }

    private fun Element?.filho(tag: String): Element? = this?.filhos(tag)?.firstOrNull()

    private fun Element?.txt(tag: String): String = this?.filho(tag)?.textContent?.trim() ?: ""
}
