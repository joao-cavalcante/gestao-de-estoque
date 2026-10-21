package wms.backend.impressao

import wms.backend.erp.SankhyaDbExplorerClient
import wms.backend.separacao.PREFIXO_OC_OBSERVACAO
import java.math.BigDecimal

/**
 * Nota de venda da OC e a situação dela na SEFAZ (TGFCAB.STATUSNFE; 'A' = autorizada). [boletoPendente]: tem
 * título de boleto em aberto ainda sem linha digitável (não registrado no banco) — espera igual à SEFAZ.
 */
data class NotaDaOc(val nunota: Long, val numNota: String, val statusNfe: String, val boletoPendente: Boolean = false) {
    val pronta: Boolean get() = statusNfe == "A" && !boletoPendente
    val situacao: String get() = if (statusNfe != "A") statusNfe.ifBlank { "sem status" } else "boleto sem registro"
}

/** [notas]: as notas do job; [impressas]: as que entraram no PDF (as autorizadas). */
data class PdfDaOc(val pdf: ByteArray?, val notas: List<NotaDaOc>, val impressas: List<NotaDaOc> = emptyList()) {
    /** Notas que ainda não podem sair: SEFAZ não autorizou ou boleto sem registro. */
    val pendentes: List<NotaDaOc> get() = notas.filter { !it.pronta }
}

/**
 * Monta o PDF de impressão da OC fechada: romaneio + DANFE de cada nota (XML autorizado em
 * TGFNFE.XMLENVCLI, lido em pedaços — o DbExplorer só devolve VARCHAR2 de até 4000).
 */
object ImpressaoOcService {
    private const val PEDACO = 4000

    /** Romaneio de Entrega (layout do relatório 179 do Sankhya) na frente das notas. */
    private const val IMPRIMIR_ROMANEIO = true
    private const val PEDACOS_POR_CONSULTA = 6

    /**
     * [somente] = null: OC inteira (romaneio + DANFEs); senão só o DANFE dessas notas, sem romaneio.
     * Com nota pendente na SEFAZ só gera se [aceitarParcial]: aí sai o que está autorizado e o romaneio
     * marca as pendentes (o DANFE delas sai num job separado).
     */
    suspend fun gerar(tenantSlug: String, oc: Long, somente: Set<Long>? = null, aceitarParcial: Boolean = false): PdfDaOc {
        val (romaneio, todas) = romaneio(tenantSlug, oc)
        val (boletos, semRegistro) = boletos(tenantSlug, oc)
        val notas = (if (somente == null) todas else todas.filter { it.nunota in somente })
            .map { if (it.nunota in semRegistro) it.copy(boletoPendente = true) else it }
        val prontas = notas.filter { it.pronta }
        if (prontas.isEmpty() || (prontas.size < notas.size && !aceitarParcial)) return PdfDaOc(null, notas)
        val documentos = prontas.map { NfeXml.ler(xml(tenantSlug, it.nunota)) to boletos[it.nunota].orEmpty() }
        val pendentes = notas.filter { !it.pronta }.map { it.nunota }.toSet()
        val entrega = if (somente != null || !IMPRIMIR_ROMANEIO) null else romaneioEntrega(tenantSlug, oc)
        return PdfDaOc(ImpressaoPdf.gerarComBoletos(null, documentos, entrega), notas, prontas)
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

    /**
     * Dados do "Romaneio de Entrega" — mesma consulta do relatório formatado 179 do Sankhya
     * (ROMANEIO_ENTREGA_NEGRI_PORTAL_OC_UNICA.jrxml), com a OC no lugar dos NUNOTAs selecionados na tela:
     * notas de venda da OC + pedidos da OC ainda sem nota; dados do cliente/vendedor/pagamento vêm do pedido.
     */
    suspend fun romaneioEntrega(tenantSlug: String, oc: Long): RomaneioEntrega {
        val linhas = SankhyaDbExplorerClient.executarQuery(
            tenantSlug,
            """
            WITH PARAMETROS AS (
                SELECT DISTINCT C.ORDEMCARGA, C.CODEMP FROM TGFCAB C WHERE C.ORDEMCARGA = $oc AND C.TIPMOV IN ('V', 'P')
            ), BASE AS (
                SELECT C.NUNOTA DOC_NUNOTA, C.NUMNOTA DOC_NUMNOTA, C.CODEMP, C.ORDEMCARGA, C.CODPARC, C.CODVEND, C.CODTIPVENDA, C.DHTIPVENDA,
                       C.VLRNOTA, C.PESOBRUTO, C.OBSERVACAO, C.TIPMOV, O.DTINIC, O.DTPREVSAIDA, O.CODVEICULO, O.CODPARCMOTORISTA,
                       O.CODPARCTRANSP, O.PESOMAX
                  FROM TGFCAB C
                  LEFT JOIN TGFORD O ON O.CODEMP = C.CODEMP AND O.ORDEMCARGA = C.ORDEMCARGA
                  JOIN PARAMETROS P ON P.ORDEMCARGA = C.ORDEMCARGA AND P.CODEMP = C.CODEMP
                 WHERE C.TIPMOV = 'V' AND NVL(C.STATUSNFE, ' ') NOT IN ('C', 'D')
                UNION ALL
                SELECT C.NUNOTA, C.NUMNOTA, C.CODEMP, C.ORDEMCARGA, C.CODPARC, C.CODVEND, C.CODTIPVENDA, C.DHTIPVENDA,
                       C.VLRNOTA, C.PESOBRUTO, C.OBSERVACAO, C.TIPMOV, O.DTINIC, O.DTPREVSAIDA, O.CODVEICULO, O.CODPARCMOTORISTA,
                       O.CODPARCTRANSP, O.PESOMAX
                  FROM TGFCAB C
                  LEFT JOIN TGFORD O ON O.CODEMP = C.CODEMP AND O.ORDEMCARGA = C.ORDEMCARGA
                  JOIN PARAMETROS P ON P.ORDEMCARGA = C.ORDEMCARGA AND P.CODEMP = C.CODEMP
                 WHERE C.TIPMOV = 'P'
                   AND NOT EXISTS (SELECT 1 FROM TGFVAR V JOIN TGFCAB N ON N.NUNOTA = V.NUNOTA AND N.TIPMOV = 'V'
                                    WHERE V.NUNOTAORIG = C.NUNOTA AND N.ORDEMCARGA = C.ORDEMCARGA)
            ), DOC AS (
                SELECT B.*,
                       CASE WHEN B.TIPMOV = 'V' THEN NVL((SELECT MAX(PED.NUMNOTA) FROM TGFVAR VOR JOIN TGFCAB PED ON PED.NUNOTA = VOR.NUNOTAORIG AND PED.TIPMOV = 'P'
                                                           WHERE VOR.NUNOTA = B.DOC_NUNOTA), B.DOC_NUMNOTA) ELSE B.DOC_NUMNOTA END AS PEDIDO_NUM,
                       CASE WHEN B.TIPMOV = 'V' THEN B.DOC_NUMNOTA
                            ELSE (SELECT MAX(NF.NUMNOTA) FROM TGFVAR VNF JOIN TGFCAB NF ON NF.NUNOTA = VNF.NUNOTA AND NF.TIPMOV = 'V'
                                   WHERE VNF.NUNOTAORIG = B.DOC_NUNOTA) END AS NF_NUM,
                       CASE WHEN B.TIPMOV = 'V' THEN NVL((SELECT MAX(PED.CODPARC) FROM TGFVAR VOR JOIN TGFCAB PED ON PED.NUNOTA = VOR.NUNOTAORIG AND PED.TIPMOV = 'P'
                                                           WHERE VOR.NUNOTA = B.DOC_NUNOTA), B.CODPARC) ELSE B.CODPARC END AS PEDIDO_CODPARC,
                       CASE WHEN B.TIPMOV = 'V' THEN NVL((SELECT MAX(PED.CODTIPVENDA) FROM TGFVAR VOR JOIN TGFCAB PED ON PED.NUNOTA = VOR.NUNOTAORIG AND PED.TIPMOV = 'P'
                                                           WHERE VOR.NUNOTA = B.DOC_NUNOTA), B.CODTIPVENDA) ELSE B.CODTIPVENDA END AS PEDIDO_CODTIPVENDA,
                       CASE WHEN B.TIPMOV = 'V' THEN NVL((SELECT MAX(PED.CODVEND) FROM TGFVAR VOR JOIN TGFCAB PED ON PED.NUNOTA = VOR.NUNOTAORIG AND PED.TIPMOV = 'P'
                                                           WHERE VOR.NUNOTA = B.DOC_NUNOTA), B.CODVEND) ELSE B.CODVEND END AS PEDIDO_CODVEND,
                       CASE WHEN B.TIPMOV = 'V' THEN NVL((SELECT MAX(PED.DHTIPVENDA) FROM TGFVAR VOR JOIN TGFCAB PED ON PED.NUNOTA = VOR.NUNOTAORIG AND PED.TIPMOV = 'P'
                                                           WHERE VOR.NUNOTA = B.DOC_NUNOTA), B.DHTIPVENDA) ELSE B.DHTIPVENDA END AS PEDIDO_DHTIPVENDA
                  FROM BASE B
            )
            SELECT D.ORDEMCARGA, TO_CHAR(NVL(D.DTPREVSAIDA, D.DTINIC), 'YYYY-MM-DD') AS DATA_OC, VEI.MARCAMODELO AS VEICULO, VEI.PLACA,
                   D.PESOMAX, MOT.NOMEPARC AS MOTORISTA, D.PEDIDO_NUM, D.NF_NUM, D.PEDIDO_CODPARC AS CODPARC, VEN.APELIDO AS VENDEDORA,
                   PAR.NOMEPARC AS CLIENTE,
                   NVL(ENDE.TIPO || ' ', '') || NVL(ENDE.NOMEEND, '') ||
                      CASE WHEN PAR.NUMEND IS NOT NULL THEN ', ' || PAR.NUMEND END ||
                      CASE WHEN PAR.COMPLEMENTO IS NOT NULL THEN ' - ' || PAR.COMPLEMENTO END AS ENDERECO,
                   BAI.NOMEBAI AS BAIRRO, CID.NOMECID AS CIDADE, PAR.TELEFONE, D.OBSERVACAO AS REFERENCIA,
                   TPV.DESCRTIPVENDA AS CONDICAO_PAGAMENTO, D.VLRNOTA, D.PESOBRUTO
              FROM DOC D
              LEFT JOIN TGFVEI VEI ON VEI.CODVEICULO = D.CODVEICULO
              LEFT JOIN TGFPAR MOT ON MOT.CODPARC = D.CODPARCMOTORISTA
              LEFT JOIN TGFVEN VEN ON VEN.CODVEND = D.PEDIDO_CODVEND
              LEFT JOIN TGFPAR PAR ON PAR.CODPARC = D.PEDIDO_CODPARC
              LEFT JOIN TSIEND ENDE ON ENDE.CODEND = PAR.CODEND
              LEFT JOIN TSIBAI BAI ON BAI.CODBAI = PAR.CODBAI
              LEFT JOIN TSICID CID ON CID.CODCID = PAR.CODCID
              LEFT JOIN TGFTPV TPV ON TPV.CODTIPVENDA = D.PEDIDO_CODTIPVENDA AND TPV.DHALTER = D.PEDIDO_DHTIPVENDA
             ORDER BY D.ORDEMCARGA, D.PEDIDO_NUM, D.DOC_NUNOTA
            """.trimIndent(),
        )
        return montarRomaneioEntrega(oc, linhas)
    }

    fun montarRomaneioEntrega(oc: Long, linhas: List<Map<String, String?>>): RomaneioEntrega {
        fun dec(s: String?) = s?.trim()?.toBigDecimalOrNull()
        fun txt(l: Map<String, String?>, k: String) = l[k]?.trim().orEmpty()
        val primeira = linhas.firstOrNull() ?: emptyMap()
        val pedidos = linhas.map { l ->
            EntregaPedido(
                pedido = txt(l, "PEDIDO_NUM"),
                notaFiscal = txt(l, "NF_NUM"),
                pagamento = txt(l, "CONDICAO_PAGAMENTO"),
                valor = dec(l["VLRNOTA"]),
                peso = dec(l["PESOBRUTO"]),
                codParc = txt(l, "CODPARC"),
                cliente = txt(l, "CLIENTE"),
                endereco = txt(l, "ENDERECO"),
                bairro = txt(l, "BAIRRO"),
                cidade = txt(l, "CIDADE"),
                // A observação da nota começa com "ORDEM DE CARGA: n." (gravada no faturamento) — no romaneio a OC já está no título.
                referencia = txt(l, "REFERENCIA").replace(Regex("^${Regex.escape(PREFIXO_OC_OBSERVACAO)}\\d+\\.\\s*"), ""),
                telefone = txt(l, "TELEFONE"),
                vendedora = txt(l, "VENDEDORA"),
            )
        }
        return RomaneioEntrega(
            ordemCarga = primeira["ORDEMCARGA"]?.toLongOrNull() ?: oc,
            data = txt(primeira, "DATA_OC"),
            placa = txt(primeira, "PLACA"),
            motorista = txt(primeira, "MOTORISTA"),
            veiculo = txt(primeira, "VEICULO"),
            pesoMaximo = dec(primeira["PESOMAX"])?.takeIf { it.signum() > 0 },
            pesoTotal = pedidos.fold(BigDecimal.ZERO) { a, p -> a + (p.peso ?: BigDecimal.ZERO) },
            pedidos = pedidos,
        )
    }

    /**
     * Boletos em aberto das notas da OC (TGFFIN a receber, sem baixa; tipo de título boleto ou já com linha
     * digitável), por NUNOTA. Segundo item: notas com boleto ainda sem linha digitável (não registrado).
     */
    suspend fun boletos(tenantSlug: String, oc: Long): Pair<Map<Long, List<Boleto>>, Set<Long>> {
        val linhas = SankhyaDbExplorerClient.executarQuery(
            tenantSlug,
            """
            SELECT FIN.NUNOTA, CAB.NUMNOTA, FIN.DESDOBRAMENTO, TO_CHAR(FIN.DTVENC, 'YYYY-MM-DD') AS DTVENC, TO_CHAR(FIN.DTNEG, 'YYYY-MM-DD') AS DTNEG,
                   FIN.VLRDESDOB, FIN.NOSSONUM, FIN.LINHADIGITAVEL, FIN.CODIGOBARRA, FIN.EMVPIX,
                   FIN.CODBCO, BCO.NOMEBCO, CTA.CODAGE, CTA.NUMBENEFICIARIO, CTA.CONVENIO, CTA.CARTEIRA, CTA.VALORMULTA, CTA.VALORJUROS, CTA.DIASPROTESTO,
                   E.RAZAOSOCIAL AS RAZEMP, E.CGC AS CGCEMP, ENE.TIPO AS TIPEMP, ENE.NOMEEND AS ENDEMP, E.NUMEND AS NUMEMP, BAE.NOMEBAI AS BAIEMP,
                   CIE.NOMECID AS CIDEMP, UFE.UF AS UFEMP, E.CEP AS CEPEMP,
                   PAR.RAZAOSOCIAL, PAR.CGC_CPF, ENP.TIPO AS TIPPAR, ENP.NOMEEND AS ENDPAR, PAR.NUMEND AS NUMPAR, PAR.COMPLEMENTO AS COMPPAR,
                   BAP.NOMEBAI AS BAIPAR, CIP.NOMECID AS CIDPAR, UFP.UF AS UFPAR, PAR.CEP AS CEPPAR
              FROM TGFFIN FIN
              JOIN TGFCAB CAB ON CAB.NUNOTA = FIN.NUNOTA
              JOIN TSICTA CTA ON CTA.CODCTABCOINT = FIN.CODCTABCOINT
              LEFT JOIN TSIBCO BCO ON BCO.CODBCO = FIN.CODBCO
              LEFT JOIN TGFTIT TIT ON TIT.CODTIPTIT = FIN.CODTIPTIT
              JOIN TSIEMP E ON E.CODEMP = FIN.CODEMP
              LEFT JOIN TSIEND ENE ON ENE.CODEND = E.CODEND
              LEFT JOIN TSIBAI BAE ON BAE.CODBAI = E.CODBAI
              LEFT JOIN TSICID CIE ON CIE.CODCID = E.CODCID
              LEFT JOIN TSIUFS UFE ON UFE.CODUF = CIE.UF
              JOIN TGFPAR PAR ON PAR.CODPARC = FIN.CODPARC
              LEFT JOIN TSIEND ENP ON ENP.CODEND = PAR.CODEND
              LEFT JOIN TSIBAI BAP ON BAP.CODBAI = PAR.CODBAI
              LEFT JOIN TSICID CIP ON CIP.CODCID = PAR.CODCID
              LEFT JOIN TSIUFS UFP ON UFP.CODUF = CIP.UF
             WHERE CAB.TIPMOV = 'V' AND CAB.ORDEMCARGA = $oc AND FIN.RECDESP = 1 AND FIN.DHBAIXA IS NULL
               AND (FIN.LINHADIGITAVEL IS NOT NULL OR UPPER(TIT.DESCRTIPTIT) LIKE '%BOLETO%')
             ORDER BY FIN.NUNOTA, FIN.DTVENC, FIN.DESDOBRAMENTO
            """.trimIndent(),
        )
        val registrado = { l: Map<String, String?> -> !l["LINHADIGITAVEL"].isNullOrBlank() && !l["CODIGOBARRA"].isNullOrBlank() }
        val semRegistro = linhas.filterNot(registrado).mapNotNull { it["NUNOTA"]?.toLongOrNull() }.toSet()
        return linhas.filter(registrado).map { montarBoleto(it) }.groupBy { it.nunota } to semRegistro
    }

    fun montarBoleto(l: Map<String, String?>): Boleto {
        fun dec(s: String?) = s?.trim()?.toBigDecimalOrNull()
        fun endereco(tipo: String?, rua: String?, num: String?, compl: String?, bairro: String?, cidade: String?, uf: String?, cep: String?) =
            listOfNotNull(
                listOfNotNull(logradouro(tipo, rua), num?.takeIf { it.isNotBlank() }, compl?.takeIf { it.isNotBlank() }).joinToString(", ").ifBlank { null },
                bairro?.takeIf { it.isNotBlank() },
                listOfNotNull(cidade, uf).joinToString("/").ifBlank { null },
                cep?.takeIf { it.isNotBlank() }?.let { "CEP ${Formato.cep(it)}" },
            ).joinToString(" - ")
        val codBanco = l["CODBCO"]?.trim().orEmpty()
        return Boleto(
            nunota = l["NUNOTA"]!!.toLong(),
            numNota = l["NUMNOTA"].orEmpty(),
            desdobramento = l["DESDOBRAMENTO"].orEmpty(),
            vencimento = l["DTVENC"].orEmpty(),
            emissao = l["DTNEG"].orEmpty(),
            valor = dec(l["VLRDESDOB"]) ?: BigDecimal.ZERO,
            nossoNumero = l["NOSSONUM"].orEmpty(),
            linhaDigitavel = l["LINHADIGITAVEL"].orEmpty().split(' ').filter { it.isNotBlank() }.joinToString(" "),
            codigoBarras = l["CODIGOBARRA"].orEmpty(),
            pixCopiaECola = l["EMVPIX"]?.trim()?.takeIf { it.isNotEmpty() },
            codBanco = codBanco,
            nomeBanco = if (codBanco.trimStart('0') == "33") "Santander" else l["NOMEBCO"].orEmpty(),
            // TSICTA.CODAGE vem com 5 dígitos ("01070"): agência de 4 dígitos.
            agencia = l["CODAGE"].orEmpty().trim().trimStart('0').padStart(4, '0'),
            codBeneficiario = (l["NUMBENEFICIARIO"] ?: l["CONVENIO"]).orEmpty().trim(),
            carteira = l["CARTEIRA"].orEmpty(),
            multaPerc = dec(l["VALORMULTA"]),
            jurosPerc = dec(l["VALORJUROS"]),
            diasProtesto = l["DIASPROTESTO"]?.trim()?.toIntOrNull(),
            beneficiario = l["RAZEMP"].orEmpty(),
            cnpjBeneficiario = l["CGCEMP"].orEmpty(),
            enderecoBeneficiario = endereco(l["TIPEMP"], l["ENDEMP"], l["NUMEMP"], null, l["BAIEMP"], l["CIDEMP"], l["UFEMP"], l["CEPEMP"]),
            pagador = l["RAZAOSOCIAL"].orEmpty(),
            docPagador = l["CGC_CPF"].orEmpty(),
            enderecoPagador = endereco(l["TIPPAR"], l["ENDPAR"], l["NUMPAR"], l["COMPPAR"], l["BAIPAR"], l["CIDPAR"], l["UFPAR"], l["CEPPAR"]),
        )
    }

    /** TSIEND.TIPO abreviado ("R", "AV") + nome. */
    private fun logradouro(tipo: String?, nome: String?): String? {
        if (nome.isNullOrBlank()) return null
        val t = when (val tp = tipo?.trim()?.uppercase().orEmpty()) {
            "R" -> "Rua"
            "AV" -> "Av."
            "AL" -> "Alameda"
            "TV" -> "Travessa"
            "EST" -> "Estrada"
            "ROD" -> "Rodovia"
            "PC", "PCA" -> "Praça"
            else -> tp
        }
        return "$t ${nome.trim()}".trim()
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
