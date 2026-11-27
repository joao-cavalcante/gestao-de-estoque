package wms.backend.armazenagem

import kotlinx.serialization.Serializable
import wms.backend.auth.ClaimsToken
import wms.backend.erp.SankhyaDbExplorerClient
import wms.backend.permissoes.EmpresasAcesso
import wms.backend.tenancy.TenantRepository

/**
 * Aba Armazenagem do Recebimento (27/11/2026): andamento da armazenagem por compra, lido no Sankhya (addon TOR —
 * TORREC/TORTAR/TORMOV) só com SELECT. Mostra as compras com tarefa aberta, as concluídas nas últimas 24 h e as
 * aprovadas que ainda esperam tarefa (ajuste de peso por sair) ou deram erro na geração.
 */
@Serializable
data class ItemArmazenagemDto(
    val nutar: Long,
    val codprod: Int,
    val descricao: String,
    val unidade: String?,
    val qtd: Double,
    val qtdFeita: Double,
    val situacao: String,
    val origem: String?,
    val sugerido: String?,
    val guardadoEm: String?,
    val motivo: String?,
    val operador: String?,
    val concluidaEm: String?,
)

@Serializable
data class CompraArmazenagemDto(
    val nunota: Long,
    val numnota: Long?,
    val fornecedor: String?,
    val codemp: Int?,
    val aprovadaEm: String?,
    /** aguardando | erro | na_doca | guardando | guardado */
    val situacao: String,
    val erro: String?,
    val itens: List<ItemArmazenagemDto>,
)

object ArmazenagemCompras {

    private const val SQL_TAREFAS = "SELECT T.NUTAR, T.NUNOTA, C.NUMNOTA, PA.NOMEPARC, C.CODEMP, " +
        "TO_CHAR(R.DHAPROV, 'YYYY-MM-DD\"T\"HH24:MI:SS') AS APROVADA, T.CODPROD, P.DESCRPROD, T.CODVOL, T.QTD, T.QTDFEITA, " +
        "T.SITUACAO, O.CODIGO AS ORIGEM, S.CODIGO AS SUGERIDO, U.NOMEUSU, " +
        "TO_CHAR(T.DHCONCLUSAO, 'YYYY-MM-DD\"T\"HH24:MI:SS') AS FIM, " +
        "(SELECT LISTAGG(D.CODIGO, ', ') WITHIN GROUP (ORDER BY M.NUMOV) FROM TORMOV M JOIN TOREND D ON D.CODEND = M.CODENDDEST " +
        "  WHERE M.NUTAR = T.NUTAR AND M.TIPO = 'ARM') AS GUARDADO, " +
        "(SELECT MAX(M.MOTIVO) FROM TORMOV M WHERE M.NUTAR = T.NUTAR AND M.TIPO = 'ARM') AS MOTIVO " +
        "FROM TORTAR T JOIN TGFCAB C ON C.NUNOTA = T.NUNOTA JOIN TGFPRO P ON P.CODPROD = T.CODPROD " +
        "LEFT JOIN TORREC R ON R.NUNOTA = T.NUNOTA AND R.NUCONF = T.NUCONF LEFT JOIN TGFPAR PA ON PA.CODPARC = C.CODPARC " +
        "LEFT JOIN TOREND O ON O.CODEND = T.CODENDORIG LEFT JOIN TOREND S ON S.CODEND = T.CODENDSUG " +
        "LEFT JOIN TSIUSU U ON U.CODUSU = T.CODUSU " +
        "WHERE (T.SITUACAO IN ('AB', 'EX') OR T.DHCONCLUSAO >= SYSDATE - 1) "

    private fun sqlEsperando(corte: String) = "SELECT R.NUNOTA, C.NUMNOTA, PA.NOMEPARC, C.CODEMP, R.ENDSIT, R.ENDERRO, " +
        "TO_CHAR(R.DHAPROV, 'YYYY-MM-DD\"T\"HH24:MI:SS') AS APROVADA FROM TORREC R JOIN TGFCAB C ON C.NUNOTA = R.NUNOTA " +
        "JOIN TORCFG G ON G.CODEMP = C.CODEMP LEFT JOIN TGFPAR PA ON PA.CODPARC = C.CODPARC " +
        "WHERE R.SITUACAO = 'AP' AND NVL(G.ENDATIVO, 'N') = 'S' AND NVL(R.ENDSIT, 'X') <> 'GE' AND R.DHAPROV >= $corte "

    suspend fun listar(claims: ClaimsToken): List<CompraArmazenagemDto> {
        if (!runCatching { TenantRepository.parametros(claims.tenantId).ajustePesoCompra.viaAddon }.getOrDefault(false)) return emptyList()
        val slug = TenantRepository.buscarPorId(claims.tenantId)?.slug ?: return emptyList()
        val permitidas = EmpresasAcesso.permitidas(claims)
        val filtro = permitidas?.let { if (it.isEmpty()) return emptyList() else "AND C.CODEMP IN (${it.sorted().joinToString(",")}) " } ?: ""

        val tarefas = runCatching { SankhyaDbExplorerClient.executarQuery(slug, "$SQL_TAREFAS$filtro ORDER BY R.DHAPROV DESC, T.NUTAR") }
            .getOrElse { if (it.message?.contains("ORA-00942") == true) return emptyList() else throw it }
        val esperando = runCatching { SankhyaDbExplorerClient.executarQuery(slug, sqlEsperando("NVL(G.DHINIEND, TRUNC(SYSDATE))") + filtro) }
            .recoverCatching { SankhyaDbExplorerClient.executarQuery(slug, sqlEsperando("TRUNC(SYSDATE)") + filtro) }
            .getOrElse { emptyList() }
        return montar(tarefas, esperando)
    }

    private fun Map<String, String?>.t(c: String) = this[c]?.trim()?.takeIf { it.isNotEmpty() }
    private fun Map<String, String?>.n(c: String) = t(c)?.replace(',', '.')?.toDoubleOrNull()

    internal fun montar(tarefas: List<Map<String, String?>>, esperando: List<Map<String, String?>>): List<CompraArmazenagemDto> {
        val porCompra = tarefas.groupBy { it.n("NUNOTA")?.toLong() ?: 0L }.filterKeys { it > 0 }
        val compras = porCompra.map { (nunota, linhas) ->
            val l0 = linhas.first()
            val itens = linhas.map { l ->
                ItemArmazenagemDto(
                    nutar = l.n("NUTAR")?.toLong() ?: 0,
                    codprod = l.n("CODPROD")?.toInt() ?: 0,
                    descricao = l.t("DESCRPROD") ?: "",
                    unidade = l.t("CODVOL"),
                    qtd = l.n("QTD") ?: 0.0,
                    qtdFeita = l.n("QTDFEITA") ?: 0.0,
                    situacao = l.t("SITUACAO") ?: "AB",
                    origem = l.t("ORIGEM"),
                    sugerido = l.t("SUGERIDO"),
                    guardadoEm = l.t("GUARDADO"),
                    motivo = l.t("MOTIVO"),
                    operador = l.t("NOMEUSU"),
                    concluidaEm = l.t("FIM"),
                )
            }
            CompraArmazenagemDto(nunota, l0.n("NUMNOTA")?.toLong(), l0.t("NOMEPARC"), l0.n("CODEMP")?.toInt(), l0.t("APROVADA"),
                situacao(itens), null, itens)
        }
        val comTarefa = porCompra.keys
        val semTarefa = esperando.mapNotNull { l ->
            val nunota = l.n("NUNOTA")?.toLong() ?: return@mapNotNull null
            if (nunota in comTarefa) return@mapNotNull null
            val erro = l.t("ENDSIT") == "ER"
            CompraArmazenagemDto(nunota, l.n("NUMNOTA")?.toLong(), l.t("NOMEPARC"), l.n("CODEMP")?.toInt(), l.t("APROVADA"),
                if (erro) "erro" else "aguardando", if (erro) l.t("ENDERRO") else null, emptyList())
        }
        return (semTarefa + compras).sortedWith(compareBy<CompraArmazenagemDto> { ORDEM[it.situacao] ?: 9 }.thenByDescending { it.nunota })
    }

    private val ORDEM = mapOf("erro" to 0, "guardando" to 1, "na_doca" to 2, "aguardando" to 3, "guardado" to 4)

    internal fun situacao(itens: List<ItemArmazenagemDto>): String = when {
        itens.all { it.situacao == "CO" || it.situacao == "CA" } -> "guardado"
        itens.any { it.situacao == "EX" || it.qtdFeita > 0 || it.situacao == "CO" } -> "guardando"
        else -> "na_doca"
    }
}
