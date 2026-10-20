package wms.backend.impressao

import wms.backend.erp.SankhyaDbExplorerClient
import java.math.BigDecimal

/** Nota de venda da OC e a situação dela na SEFAZ (TGFCAB.STATUSNFE; 'A' = autorizada). */
data class NotaDaOc(val nunota: Long, val numNota: String, val statusNfe: String)

data class PdfDaOc(val pdf: ByteArray?, val notas: List<NotaDaOc>) {
    /** Notas que ainda não estão autorizadas — enquanto houver alguma, não gera o PDF. */
    val pendentes: List<NotaDaOc> get() = notas.filter { it.statusNfe != "A" }
}

/**
 * Monta o PDF de impressão da OC fechada: romaneio + DANFE de cada nota (XML autorizado em
 * TGFNFE.XMLENVCLI, lido em pedaços — o DbExplorer só devolve VARCHAR2 de até 4000).
 */
object ImpressaoOcService {
    private const val PEDACO = 4000
    private const val PEDACOS_POR_CONSULTA = 6

    suspend fun gerar(tenantSlug: String, oc: Long): PdfDaOc {
        val (romaneio, notas) = romaneio(tenantSlug, oc)
        if (notas.isEmpty() || notas.any { it.statusNfe != "A" }) return PdfDaOc(null, notas)
        val nfes = notas.map { NfeXml.ler(xml(tenantSlug, it.nunota)) }
        return PdfDaOc(ImpressaoPdf.gerar(romaneio, nfes), notas)
    }

    suspend fun romaneio(tenantSlug: String, oc: Long): Pair<RomaneioOc, List<NotaDaOc>> {
        val linhas = SankhyaDbExplorerClient.executarQuery(
            tenantSlug,
            """
            SELECT E.NOMEFANTASIA AS NOMEEMP, CAB.ORDEMCARGA, CAB.NUNOTA, CAB.NUMNOTA, CAB.CODPARC, PAR.RAZAOSOCIAL,
                   CID.NOMECID || '/' || UFP.UF AS CIDADE, CAB.NUMPEDIDO2, CAB.PESOBRUTO, CAB.VLRNOTA, CAB.STATUSNFE,
                   TO_CHAR(ORD.DTPREVSAIDA, 'YYYY-MM-DD') AS DTPREVSAIDA, TRA.RAZAOSOCIAL AS TRANSPORTADORA, TRA.CGC_CPF AS CNPJTRANSP,
                   MOT.NOMEPARC AS NOMEMOTORISTA, VEI.PLACA, VEI.MARCAMODELO,
                   (SELECT MIN(VAR.NUNOTAORIG) FROM TGFVAR VAR WHERE VAR.NUNOTA = CAB.NUNOTA) AS NUNOTAORIG,
                   (SELECT NVL(SUM(C2.QTDVOL), 0) FROM TGFCAB O JOIN TGFCON2 C2 ON C2.NUCONF = O.NUCONFATUAL
                     WHERE O.NUNOTA IN (SELECT DISTINCT VAR.NUNOTAORIG FROM TGFVAR VAR WHERE VAR.NUNOTA = CAB.NUNOTA)) AS QTDVOL
              FROM TGFCAB CAB
              JOIN TGFEMP EMP ON EMP.CODEMP = CAB.CODEMP
              JOIN TSIEMP E ON E.CODEMP = CAB.CODEMP
              LEFT JOIN TGFORD ORD ON ORD.ORDEMCARGA = CAB.ORDEMCARGA AND ORD.CODEMP = NVL(EMP.CODEMPOC, EMP.CODEMP)
              LEFT JOIN TGFPAR TRA ON TRA.CODPARC = ORD.CODPARCTRANSP
              LEFT JOIN TGFPAR MOT ON MOT.CODPARC = ORD.CODPARCMOTORISTA
              LEFT JOIN TGFVEI VEI ON VEI.CODVEICULO = ORD.CODVEICULO
              JOIN TGFPAR PAR ON PAR.CODPARC = CAB.CODPARC
              LEFT JOIN TSICID CID ON CID.CODCID = PAR.CODCID
              LEFT JOIN TSIUFS UFP ON UFP.CODUF = CID.UF
             WHERE CAB.TIPMOV = 'V' AND CAB.ORDEMCARGA = $oc AND NVL(CAB.STATUSNFE, ' ') NOT IN ('C', 'D')
             ORDER BY CAB.NUNOTA
            """.trimIndent(),
        )
        val notas = linhas.map { NotaDaOc(it["NUNOTA"]!!.toLong(), it["NUMNOTA"] ?: "", it["STATUSNFE"]?.trim() ?: "") }
        return montarRomaneio(oc, linhas) to notas
    }

    /** Linhas da consulta do romaneio (uma por nota) -> [RomaneioOc]. */
    fun montarRomaneio(oc: Long, linhas: List<Map<String, String?>>): RomaneioOc {
        fun dec(s: String?) = s?.trim()?.toBigDecimalOrNull() ?: BigDecimal.ZERO
        val primeira = linhas.firstOrNull()
        return RomaneioOc(
            empresa = primeira?.get("NOMEEMP") ?: "",
            ordemCarga = primeira?.get("ORDEMCARGA")?.toLongOrNull() ?: oc,
            prevSaida = primeira?.get("DTPREVSAIDA") ?: "",
            transportadora = primeira?.get("TRANSPORTADORA") ?: "",
            cnpjTransportadora = primeira?.get("CNPJTRANSP") ?: "",
            placa = primeira?.get("PLACA") ?: "",
            veiculo = primeira?.get("MARCAMODELO") ?: "",
            motorista = primeira?.get("NOMEMOTORISTA") ?: "",
            notas = linhas.map {
                RomaneioNota(
                    numNota = it["NUMNOTA"] ?: "",
                    nunota = it["NUNOTA"]!!.toLong(),
                    pedido = it["NUNOTAORIG"] ?: "",
                    pedidoCliente = it["NUMPEDIDO2"] ?: "",
                    codParc = it["CODPARC"] ?: "",
                    cliente = it["RAZAOSOCIAL"] ?: "",
                    cidade = it["CIDADE"] ?: "",
                    peso = dec(it["PESOBRUTO"]),
                    volumes = dec(it["QTDVOL"]).toInt(),
                    valor = dec(it["VLRNOTA"]),
                )
            },
        )
    }

    /** XML autorizado (nfeProc) da nota, montado a partir de pedaços de 4000 caracteres do CLOB. */
    suspend fun xml(tenantSlug: String, nunota: Long): String {
        val tamanho = SankhyaDbExplorerClient.executarQuery(
            tenantSlug, "SELECT DBMS_LOB.GETLENGTH(XMLENVCLI) AS TAM FROM TGFNFE WHERE NUNOTA = $nunota",
        ).firstOrNull()?.get("TAM")?.toLongOrNull() ?: error("nota $nunota sem XML autorizado (TGFNFE.XMLENVCLI)")
        val xml = StringBuilder()
        var inicio = 1L
        while (inicio <= tamanho) {
            val colunas = (0 until PEDACOS_POR_CONSULTA).joinToString(", ") { i ->
                "DBMS_LOB.SUBSTR(XMLENVCLI, $PEDACO, ${inicio + i.toLong() * PEDACO}) AS P$i"
            }
            val linha = SankhyaDbExplorerClient.executarQuery(tenantSlug, "SELECT $colunas FROM TGFNFE WHERE NUNOTA = $nunota")
                .firstOrNull() ?: error("nota $nunota sem XML")
            (0 until PEDACOS_POR_CONSULTA).forEach { i -> xml.append(linha["P$i"] ?: "") }
            inicio += PEDACO.toLong() * PEDACOS_POR_CONSULTA
        }
        check(xml.length.toLong() == tamanho) { "XML da nota $nunota veio incompleto (${xml.length} de $tamanho caracteres)" }
        return xml.toString()
    }
}
