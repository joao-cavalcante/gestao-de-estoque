package wms.backend.impressao

import com.lowagie.text.Document
import com.lowagie.text.PageSize
import com.lowagie.text.pdf.PdfWriter
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.system.exitProcess

/** Monta um PDF com o romaneio (se houver) seguido do DANFE de cada nota, na ordem recebida. */
object ImpressaoPdf {
    fun gerar(romaneio: RomaneioOc?, notas: List<NotaNfe>): ByteArray = gerarComBoletos(romaneio, notas.map { it to emptyList() })

    /** Romaneio (se houver) + DANFE de cada nota seguido dos boletos dela. */
    fun gerarComBoletos(romaneio: RomaneioOc?, documentos: List<Pair<NotaNfe, List<Boleto>>>, entrega: RomaneioEntrega? = null): ByteArray {
        val saida = ByteArrayOutputStream()
        val documento = Document(PageSize.A4, 0f, 0f, 0f, 0f)
        val writer = PdfWriter.getInstance(documento, saida)
        documento.addTitle(romaneio?.let { "Ordem de carga ${it.ordemCarga}" } ?: "DANFE")
        documento.addCreator("WMS")
        documento.open()
        romaneio?.let { RomaneioPdf.desenhar(documento, writer, it) }
        entrega?.let { RomaneioEntregaPdf.desenhar(documento, writer, it) }
        documentos.forEach { (nota, boletos) ->
            if (nota.modelo == "65") DanfeNfcePdf.desenhar(documento, writer, nota) else DanfePdf.desenhar(documento, writer, nota)
            boletos.forEach { BoletoPdf.desenhar(documento, writer, it) }
        }
        documento.close()
        return saida.toByteArray()
    }

    /** Só os boletos (teste local do layout). */
    fun soBoletos(boletos: List<Boleto>): ByteArray {
        val saida = ByteArrayOutputStream()
        val documento = Document(PageSize.A4, 0f, 0f, 0f, 0f)
        val writer = PdfWriter.getInstance(documento, saida)
        documento.open()
        boletos.forEach { BoletoPdf.desenhar(documento, writer, it) }
        documento.close()
        return saida.toByteArray()
    }
}

/**
 * Teste local: DANFE a partir de arquivos XML (nfeProc), sem tocar no Sankhya. Com --romaneio=<arquivo>
 * (uma linha JSON por nota, saída do SankhyaQueryKt com a consulta de [ImpressaoOcService.romaneio])
 * desenha também o romaneio.
 *   java -cp ... wms.backend.impressao.ImpressaoPdfKt saida.pdf [--romaneio=linhas.json] nota1.xml [nota2.xml...]
 * Com --boletos=<arquivo> (linhas JSON da consulta de [ImpressaoOcService.boletos]) gera só os boletos.
 */
fun main(args: Array<String>) {
    val romaneioArq = args.firstOrNull { it.startsWith("--romaneio=") }?.substringAfter("=")
    val boletosArq = args.firstOrNull { it.startsWith("--boletos=") }?.substringAfter("=")
    val entregaArq = args.firstOrNull { it.startsWith("--entrega=") }?.substringAfter("=")
    // --extras=nunota;codparc;regiao;tipo negociação;oc;peso;volumes;nome parceiro|... (uma por XML, na mesma ordem) e --logo=png
    val extrasArg = args.firstOrNull { it.startsWith("--extras=") }?.substringAfter("=")
    val logoArq = args.firstOrNull { it.startsWith("--logo=") }?.substringAfter("=")
    val resto = args.filterNot { a -> listOf("--romaneio=", "--boletos=", "--entrega=", "--extras=", "--logo=").any { a.startsWith(it) } }
    fun linhasJson(arq: String) = File(arq).readLines().filter { it.startsWith("{") }.map { l ->
        kotlinx.serialization.json.Json.parseToJsonElement(l).jsonObject.mapValues { (_, v) -> v.jsonPrimitive.contentOrNull }
    }
    if (entregaArq != null && resto.isNotEmpty()) {
        val entrega = ImpressaoOcService.montarRomaneioEntrega(0, linhasJson(entregaArq))
        File(resto[0]).writeBytes(ImpressaoPdf.gerarComBoletos(null, emptyList(), entrega))
        println("PDF gerado: ${resto[0]} (${entrega.pedidos.size} pedido(s))")
        return
    }
    if (boletosArq != null && resto.isNotEmpty()) {
        val boletos = linhasJson(boletosArq).map { ImpressaoOcService.montarBoleto(it) }
        File(resto[0]).writeBytes(ImpressaoPdf.soBoletos(boletos))
        println("PDF gerado: ${resto[0]} (${boletos.size} boleto(s))")
        return
    }
    if (resto.isEmpty()) {
        System.err.println("uso: ImpressaoPdfKt <saida.pdf> [--romaneio=linhas.json] [nota.xml...]")
        exitProcess(2)
    }
    val logo = logoArq?.let { File(it).readBytes() }
    val extras = extrasArg?.split('|')?.map { e ->
        val c = e.split(';')
        ExtrasNota(c[0].toLong(), c[1], c[7], c[2], c[3], c[4].toLongOrNull(), c[5].toBigDecimalOrNull(), c[5].toBigDecimalOrNull(), c[6], logo)
    }.orEmpty()
    val notas = resto.drop(1).mapIndexed { i, arq -> NfeXml.ler(File(arq).readText(Charsets.UTF_8)).copy(extras = extras.getOrNull(i)) }
    val romaneio = romaneioArq?.let { ImpressaoOcService.montarRomaneio(0, linhasJson(it)) }
    File(resto[0]).writeBytes(ImpressaoPdf.gerar(romaneio, notas))
    println("PDF gerado: ${resto[0]} (${notas.size} nota(s))")
}
