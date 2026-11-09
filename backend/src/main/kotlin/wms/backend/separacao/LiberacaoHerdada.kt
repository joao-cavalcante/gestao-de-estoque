package wms.backend.separacao

import wms.backend.erp.SankhyaDbExplorerClient
import wms.backend.liberacaocorte.LiberacaoCorteService

/**
 * Liberação automática na confirmação da nota gerada pela Torre.
 *
 * Cliente, 09/11/2026: "na nota todos os eventos serão liberados automaticamente ao invés de fazer um espelho". Antes
 * (23/10 a 09/11) só se liberava o evento já liberado no pedido (espelho pela TGFVAR); a nota 82606 parou no evento 3
 * (limite de crédito) porque o pedido 82043 não tinha liberação de crédito pra herdar. Agora toda liberação pendente da
 * nota é liberada, tenha ou não liberação no pedido.
 *
 *  - Só TABELA TGFCAB e TGFITE da própria nota; nunca o corte ([EVENTOS_CORTE]) nem linha reprovada (decisão de alguém).
 *  - Pendente na conta do Sankhya: sem DHLIB ou VLRATUAL > VLRLIBERADO.
 *  - Liberador de serviço (LIBERADOR_USUARIO / LIBERADOR_SENHA, validado no Sankhya) pelo
 *    LiberacaoLimitesSP.liberarNegarLimites com vlrLib 0 (VLRLIBERADO recebe o VLRATUAL). A OBSLIB diz que foi automática
 *    e, quando houver, onde o evento já tinha sido liberado antes.
 */
object LiberacaoHerdada {

    /** Corte (TSILIB TGFCOI2, evento 64 — ConferenciaSP.cortar): decisão do liberador, nunca automática. */
    private val EVENTOS_CORTE = listOf(64)

    private const val TAM_OBSLIB = 255

    /** Libera todas as liberações pendentes da [nunota]. Devolve quantas foram feitas. Nunca lança. */
    suspend fun herdar(tenantSlug: String, nunota: Long): Int {
        val pendentes = runCatching { SankhyaDbExplorerClient.executarQuery(tenantSlug, sqlPendentes(nunota)) }
            .getOrElse {
                println("AVISO: liberação automática — consulta da nota $nunota falhou: ${it.message}")
                return 0
            }
        if (pendentes.isEmpty()) return 0
        val (usuario, senha) = LiberacaoCorteService.credenciaisLiberador() ?: run {
            println("AVISO: LIBERADOR_USUARIO/LIBERADOR_SENHA não configurados — liberação da nota $nunota fica manual.")
            return 0
        }
        val codusu = runCatching { LiberacaoCorteService.validarLiberador(tenantSlug, usuario, senha) }.getOrElse {
            println("AVISO: liberação automática da nota $nunota — liberador de serviço não validou: ${it.message}")
            return 0
        }
        var liberadas = 0
        for (p in pendentes) {
            val origem = p["NUNOTAORIG"]?.takeIf { it.isNotBlank() }?.let {
                " Já liberado no Nro. Único $it por ${p["NOMEUSU"] ?: p["CODUSULIB"]} em ${p["DHLIBORIG"]} (lá: ${p["VLRORIG"]})."
            } ?: ""
            val obs = ("Liberação automática da Torre de Operação na confirmação da nota: evento ${p["EVENTO"]}, " +
                "valor ${p["VLRATUAL"]}.$origem").take(TAM_OBSLIB)
            // Chaves como inteiro puro ("3", não "3.0") — o chamarLiberarNegar lê com toIntOrNull/toLongOrNull.
            val linha = listOf("NUCHAVE", "EVENTO", "NUCLL", "SEQCASCATA", "SEQUENCIA").associateWith {
                p[it]?.trim()?.toBigDecimalOrNull()?.toLong()?.toString() ?: "0"
            } + ("TABELA" to p["TABELA"]?.trim())
            runCatching { LiberacaoCorteService.chamarLiberarNegar(tenantSlug, listOf(linha), codusu, "S", obs, vlrLib = 0) }
                .onSuccess {
                    liberadas++
                    println("INFO: liberação automática — nota $nunota ${linha["TABELA"]} seq ${linha["SEQUENCIA"]} evento ${linha["EVENTO"]} " +
                        "liberada (valor ${p["VLRATUAL"]}${p["NUNOTAORIG"]?.takeIf { it.isNotBlank() }?.let { ", já liberado em $it" } ?: ", sem liberação anterior"})")
                }.onFailure {
                    println("AVISO: liberação automática — nota $nunota ${linha["TABELA"]} seq ${linha["SEQUENCIA"]} evento ${linha["EVENTO"]} falhou: ${it.message}")
                }
        }
        return liberadas
    }

    /**
     * Linhas pendentes da nota. A liberação de origem mais próxima na cadeia da TGFVAR vem só pra OBSLIB/log
     * (LEFT JOIN — não é mais condição).
     */
    private fun sqlPendentes(nunota: Long) = """
        SELECT * FROM (
          SELECT L.NUCHAVE, L.TABELA, L.EVENTO, L.SEQUENCIA, L.SEQCASCATA, L.NUCLL, L.VLRATUAL,
                 O.NUCHAVE AS NUNOTAORIG, O.CODUSULIB, O.VLRLIBERADO AS VLRORIG,
                 TO_CHAR(O.DHLIB, 'DD/MM/YYYY HH24:MI') AS DHLIBORIG, U.NOMEUSU,
                 ROW_NUMBER() OVER (PARTITION BY L.TABELA, L.EVENTO, L.SEQUENCIA, L.SEQCASCATA, L.NUCLL
                                    ORDER BY CASE WHEN O.NUCHAVE IS NULL THEN 1 ELSE 0 END, V.NIVEL, O.DHLIB DESC) AS RN
            FROM TSILIB L
            LEFT JOIN (SELECT CONNECT_BY_ROOT V.SEQUENCIA AS SEQNOTA, V.NUNOTAORIG, V.SEQUENCIAORIG, LEVEL AS NIVEL
                         FROM TGFVAR V
                        START WITH V.NUNOTA = $nunota
                      CONNECT BY NOCYCLE PRIOR V.NUNOTAORIG = V.NUNOTA AND PRIOR V.SEQUENCIAORIG = V.SEQUENCIA) V
              ON L.TABELA = 'TGFCAB' OR V.SEQNOTA = L.SEQUENCIA
            LEFT JOIN TSILIB O ON O.NUCHAVE = V.NUNOTAORIG AND O.TABELA = L.TABELA AND O.EVENTO = L.EVENTO
                              AND O.SEQUENCIA = CASE WHEN L.TABELA = 'TGFCAB' THEN 0 ELSE V.SEQUENCIAORIG END
                              AND O.DHLIB IS NOT NULL AND NVL(O.REPROVADO, 'N') = 'N'
            LEFT JOIN TSIUSU U ON U.CODUSU = O.CODUSULIB
           WHERE L.NUCHAVE = $nunota
             AND L.TABELA IN ('TGFCAB', 'TGFITE')
             AND L.EVENTO NOT IN (${EVENTOS_CORTE.joinToString()})
             AND NVL(L.REPROVADO, 'N') = 'N'
             AND (L.DHLIB IS NULL OR L.VLRATUAL > NVL(L.VLRLIBERADO, 0))
        ) WHERE RN = 1
    """.trimIndent()
}
