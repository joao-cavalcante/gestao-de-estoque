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
    fun gerar(romaneio: RomaneioOc?, notas: List<NotaNfe>): ByteArray {
        val saida = ByteArrayOutputStream()
        val documento = Document(PageSize.A4, 0f, 0f, 0f, 0f)
        val writer = PdfWriter.getInstance(documento, saida)
        documento.addTitle(romaneio?.let { "Ordem de carga ${it.ordemCarga}" } ?: "DANFE")
        documento.addCreator("WMS")
        documento.open()
        romaneio?.let { RomaneioPdf.desenhar(documento, writer, it) }
        notas.forEach { nota -> if (nota.modelo == "65") DanfeNfcePdf.desenhar(documento, writer, nota) else DanfePdf.desenhar(documento, writer, nota) }
        documento.close()
        return saida.toByteArray()
    }
}

/**
 * Teste local: DANFE a partir de arquivos XML (nfeProc), sem tocar no Sankhya. Com --romaneio=<arquivo>
 * (uma linha JSON por nota, saída do SankhyaQueryKt com a consulta de [ImpressaoOcService.romaneio])
 * desenha também o romaneio.
 *   java -cp ... wms.backend.impressao.ImpressaoPdfKt saida.pdf [--romaneio=linhas.json] nota1.xml [nota2.xml...]
 */
fun main(args: Array<String>) {
    val romaneioArq = args.firstOrNull { it.startsWith("--romaneio=") }?.substringAfter("=")
    val resto = args.filterNot { it.startsWith("--romaneio=") }
    if (resto.isEmpty()) {
        System.err.println("uso: ImpressaoPdfKt <saida.pdf> [--romaneio=linhas.json] [nota.xml...]")
        exitProcess(2)
    }
    val notas = resto.drop(1).map { NfeXml.ler(File(it).readText(Charsets.UTF_8)) }
    val romaneio = romaneioArq?.let { arq ->
        val linhas = File(arq).readLines().filter { it.startsWith("{") }.map { l ->
            kotlinx.serialization.json.Json.parseToJsonElement(l).jsonObject.mapValues { (_, v) -> v.jsonPrimitive.contentOrNull }
        }
        ImpressaoOcService.montarRomaneio(0, linhas)
    }
    File(resto[0]).writeBytes(ImpressaoPdf.gerar(romaneio, notas))
    println("PDF gerado: ${resto[0]} (${notas.size} nota(s))")
}
