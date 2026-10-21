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
    /** Nota não autorizada (ou boleto sem registro) na hora da impressão — o DANFE dela sai depois, separado. */
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
 * Romaneio da carga (A4 em pé): cabeçalho com a OC em destaque, motorista/veículo/transportadora, resumo
 * (pedidos, clientes, volumes, peso, valor) e um pedido por linha — agrupado por cidade, com caixinha de
 * entregue pro motorista. Volumes vêm da conferência do WMS (TGFCON2.QTDVOL; TGWREV é vazia na Negri).
 */
object RomaneioPdf {
    private val pagina = PageSize.A4
    private const val ESQ = 20f
    private val LARG = pagina.width - 40f
    private val BASE = pagina.height - 24f
    private const val ALT_LINHA = 26f
    private const val ALT_CIDADE = 16f
    private const val ALT_CAB_TABELA = 16f
    private const val ALT_ASSINATURAS = 78f
    private const val TOPO_PRIMEIRA = 198f
    private const val TOPO_DEMAIS = 66f

    private data class Coluna(val rotulo: String, val largura: Float, val alinhamento: Int)

    private val colunas = listOf(
        Coluna("#", 20f, Element.ALIGN_CENTER),
        Coluna("Entregue", 40f, Element.ALIGN_CENTER),
        Coluna("Pedido", 52f, Element.ALIGN_CENTER),
        Coluna("NF", 48f, Element.ALIGN_CENTER),
        Coluna("Cliente", 223f, Element.ALIGN_LEFT),
        Coluna("Vols.", 34f, Element.ALIGN_CENTER),
        Coluna("Peso (kg)", 60f, Element.ALIGN_RIGHT),
        Coluna("Valor (R$)", 78f, Element.ALIGN_RIGHT),
    )
    private val xs = colunas.runningFold(ESQ) { a, c -> a + c.largura }

    /** Item da folha: título de cidade ou linha de pedido (com o número de ordem). */
    private sealed interface Linha { val altura: Float }
    private data class Cidade(val nome: String, val pedidos: Int) : Linha { override val altura = ALT_CIDADE }
    private data class Pedido(val ordem: Int, val nota: RomaneioNota) : Linha { override val altura = ALT_LINHA }

    fun desenhar(documento: Document, writer: PdfWriter, r: RomaneioOc) {
        val agora = LocalDateTime.now(ZoneId.of("America/Sao_Paulo")).format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))
        val folhas = paginar(r)
        folhas.forEachIndexed { i, itens ->
            documento.setPageSize(pagina)
            documento.newPage()
            writer.isPageEmpty = false
            val d = Desenho(writer.directContent, pagina.height)
            var t = if (i == 0) {
                cabecalhoCompleto(d, r)
                TOPO_PRIMEIRA
            } else {
                cabecalhoCurto(d, r)
                TOPO_DEMAIS
            }
            t = tabelaCabecalho(d, t)
            itens.forEach { l ->
                when (l) {
                    is Cidade -> {
                        d.preencher(ESQ, t, LARG, ALT_CIDADE, 0.88f)
                        d.texto(ESQ + 6, t + 11.5f, LARG - 12, l.nome, 8.5f, bold = true)
                        d.texto(ESQ + 6, t + 11.5f, LARG - 12, "${l.pedidos} pedido(s)", 7.5f, Element.ALIGN_RIGHT)
                    }
                    is Pedido -> linhaPedido(d, l, t)
                }
                t += l.altura
            }
            if (i == folhas.lastIndex) {
                totais(d, r, t + 4f)
                assinaturas(d, r, BASE - ALT_ASSINATURAS)
            }
            d.texto(ESQ, BASE + 14f, LARG, "OC ${r.ordemCarga}  ·  Folha ${i + 1} de ${folhas.size}  ·  impresso em $agora", 7f, Element.ALIGN_RIGHT)
        }
    }

    /** Cidades em ordem alfabética, clientes em ordem dentro da cidade; a última folha reserva totais + assinaturas. */
    private fun paginar(r: RomaneioOc): List<List<Linha>> {
        var ordem = 0
        val linhas = r.notas.groupBy { it.cidade.ifBlank { "SEM CIDADE" } }.toSortedMap().flatMap { (cidade, notas) ->
            listOf<Linha>(Cidade(cidade, notas.size)) +
                notas.sortedWith(compareBy({ it.cliente }, { it.nunota })).map { Pedido(++ordem, it) }
        }
        val limite = BASE - 10f
        val folhas = mutableListOf<List<Linha>>()
        var atual = mutableListOf<Linha>()
        var top = TOPO_PRIMEIRA + ALT_CAB_TABELA
        fun novaFolha() {
            folhas += atual
            atual = mutableListOf()
            top = TOPO_DEMAIS + ALT_CAB_TABELA
        }
        for (l in linhas) {
            // Cidade não fica sozinha no pé da folha.
            val precisa = if (l is Cidade) l.altura + ALT_LINHA else l.altura
            if (top + precisa > limite && atual.isNotEmpty()) novaFolha()
            atual += l
            top += l.altura
        }
        if (top + 40f + ALT_ASSINATURAS > BASE) novaFolha()
        folhas += atual
        return folhas
    }

    private fun branco(d: Desenho, bloco: () -> Unit) {
        d.cb.saveState()
        d.cb.setGrayFill(1f)
        bloco()
        d.cb.restoreState()
    }

    private fun cabecalhoCompleto(d: Desenho, r: RomaneioOc) {
        // Faixa escura: título + OC em destaque.
        d.preencher(ESQ, 20f, LARG, 56f, 0.15f)
        branco(d) {
            d.texto(ESQ + 14, 44f, 300f, "ROMANEIO DE CARGA", 18f, bold = true)
            d.texto(ESQ + 14, 62f, 300f, r.empresa, 9.5f)
            d.texto(ESQ + LARG - 214, 36f, 200f, "ORDEM DE CARGA", 7.5f, Element.ALIGN_RIGHT, true)
            d.texto(ESQ + LARG - 214, 66f, 200f, r.ordemCarga.toString(), 28f, Element.ALIGN_RIGHT, true)
        }

        // Motorista / veículo / transportadora / saída.
        val w = LARG / 4
        val top = 84f
        val info = listOf(
            "MOTORISTA" to r.motorista.ifBlank { "—" },
            "VEÍCULO" to listOf(r.placa, r.veiculo).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "—" },
            "TRANSPORTADORA" to r.transportadora.ifBlank { "—" },
            "PREVISÃO DE SAÍDA" to Formato.data(r.prevSaida).ifBlank { "—" },
        )
        d.caixa(ESQ, top, LARG, 42f)
        info.forEachIndexed { k, (rotulo, valor) ->
            val x = ESQ + k * w
            if (k > 0) d.linha(x, top, x, top + 42f)
            d.texto(x + 8, top + 13f, w - 16, rotulo, 6.5f, bold = true)
            d.texto(x + 8, top + 29f, w - 16, valor, 10f, bold = true)
        }
        if (r.cnpjTransportadora.isNotBlank()) {
            d.texto(ESQ + 2 * w + 8, top + 38.5f, w - 16, "CNPJ/CPF ${Formato.cnpjCpf(r.cnpjTransportadora)}", 6f)
        }

        // Resumo da carga.
        val resumo = listOf(
            "PEDIDOS" to r.notas.size.toString(),
            "CLIENTES" to r.notas.map { it.codParc }.distinct().size.toString(),
            "VOLUMES" to r.notas.sumOf { it.volumes }.toString(),
            "PESO (KG)" to Formato.quantidade(peso(r), 2, 2),
            "VALOR (R$)" to Formato.dinheiro(valor(r)),
        )
        val espaco = 8f
        val wr = (LARG - (resumo.size - 1) * espaco) / resumo.size
        resumo.forEachIndexed { k, (rotulo, valor) ->
            val x = ESQ + k * (wr + espaco)
            d.preencher(x, 134f, wr, 48f, 0.92f)
            d.texto(x, 150f, wr, rotulo, 7f, Element.ALIGN_CENTER, true)
            d.texto(x, 172f, wr, valor, 16f, Element.ALIGN_CENTER, true)
        }
        d.texto(ESQ, 193f, LARG, "Pedidos agrupados por cidade. Marque \"Entregue\" a cada entrega.", 7f)
    }

    private fun cabecalhoCurto(d: Desenho, r: RomaneioOc) {
        d.preencher(ESQ, 20f, LARG, 30f, 0.15f)
        branco(d) {
            d.texto(ESQ + 12, 40f, 300f, "ROMANEIO DE CARGA (continuação)", 11f, bold = true)
            d.texto(ESQ + LARG - 212, 41f, 200f, "OC ${r.ordemCarga}", 14f, Element.ALIGN_RIGHT, true)
        }
        d.texto(ESQ, 61f, LARG, listOf(r.motorista, r.placa).filter { it.isNotBlank() }.joinToString("  ·  "), 8f)
    }

    private fun tabelaCabecalho(d: Desenho, top: Float): Float {
        colunas.forEachIndexed { k, c ->
            d.texto(xs[k] + 3, top + 11f, c.largura - 6, c.rotulo.uppercase(), 7f, c.alinhamento, true)
        }
        d.linha(ESQ, top + ALT_CAB_TABELA, ESQ + LARG, top + ALT_CAB_TABELA, 1.2f)
        return top + ALT_CAB_TABELA
    }

    private fun linhaPedido(d: Desenho, l: Pedido, t: Float) {
        val n = l.nota
        fun col(k: Int, s: String, tam: Float = 9f, bold: Boolean = false) =
            d.texto(xs[k] + 3, t + 16.5f, colunas[k].largura - 6, s, tam, colunas[k].alinhamento, bold)

        col(0, l.ordem.toString(), 8f)
        val lado = 11f
        d.caixa(xs[1] + (colunas[1].largura - lado) / 2, t + (ALT_LINHA - lado) / 2, lado, lado, 0.8f)
        col(2, n.pedido.ifBlank { "—" }, 10f, true)
        col(3, n.numNota + if (n.pendenteSefaz) " *" else "")
        d.texto(xs[4] + 3, t + 11.5f, colunas[4].largura - 6, n.cliente, 8.5f, bold = true)
        val detalhe = buildList {
            if (n.pendenteSefaz) add("PENDENTE (SEFAZ/BOLETO)")
            add("Cód. ${n.codParc}")
            if (n.pedidoCliente.isNotBlank()) add("Ped. cliente ${n.pedidoCliente}")
            add("N.Único ${n.nunota}")
        }.joinToString("  ·  ")
        d.texto(xs[4] + 3, t + 21.5f, colunas[4].largura - 6, detalhe, 6.5f, bold = n.pendenteSefaz)
        col(5, n.volumes.toString(), 10f, true)
        col(6, Formato.quantidade(n.peso, 2, 2))
        col(7, Formato.dinheiro(n.valor))
        d.linha(ESQ, t + ALT_LINHA, ESQ + LARG, t + ALT_LINHA, 0.3f)
    }

    private fun peso(r: RomaneioOc) = r.notas.fold(BigDecimal.ZERO) { a, n -> a + n.peso }
    private fun valor(r: RomaneioOc) = r.notas.fold(BigDecimal.ZERO) { a, n -> a + n.valor }

    private fun totais(d: Desenho, r: RomaneioOc, top: Float) {
        d.linha(ESQ, top - 4f, ESQ + LARG, top - 4f, 1.2f)
        d.texto(ESQ + 3, top + 10f, 300f, "TOTAL DA CARGA: ${r.notas.size} pedido(s)", 9f, bold = true)
        d.texto(xs[5] + 3, top + 10f, colunas[5].largura - 6, r.notas.sumOf { it.volumes }.toString(), 10f, Element.ALIGN_CENTER, true)
        d.texto(xs[6] + 3, top + 10f, colunas[6].largura - 6, Formato.quantidade(peso(r), 2, 2), 9f, Element.ALIGN_RIGHT, true)
        d.texto(xs[7] + 3, top + 10f, colunas[7].largura - 6, Formato.dinheiro(valor(r)), 9f, Element.ALIGN_RIGHT, true)
        val pendentes = r.notas.filter { it.pendenteSefaz }
        if (pendentes.isNotEmpty()) {
            d.texto(
                ESQ + 3, top + 24f, LARG - 6,
                "* NF ${pendentes.joinToString(", ") { it.numNota }} pendente (SEFAZ ou boleto sem registro) — DANFE e boleto saem separados assim que liberar.",
                8f, bold = true,
            )
        }
    }

    private fun assinaturas(d: Desenho, r: RomaneioOc, top: Float) {
        val w = LARG / 3
        fun bloco(k: Int, titulo: String, linha1: String) {
            val x = ESQ + k * w
            d.linha(x + 14f, top + 46f, x + w - 14f, top + 46f, 0.6f)
            d.texto(x, top + 57f, w, titulo, 8f, Element.ALIGN_CENTER, true)
            d.texto(x, top + 68f, w, linha1, 7.5f, Element.ALIGN_CENTER)
        }
        d.texto(ESQ, top + 10f, LARG, "Declaro que conferi e recebi a carga acima para entrega.", 7.5f)
        bloco(0, "Motorista", r.motorista.ifBlank { "Nome: ____________________" })
        bloco(1, "Conferente da expedição", "Nome: ____________________")
        bloco(2, "Saída", "Data: ____/____   Hora: ____:____")
    }
}
