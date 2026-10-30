package wms.backend.separacao

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import wms.backend.erp.SankhyaDbExplorerClient
import wms.backend.liberacaocorte.LiberacaoCorteService
import wms.backend.tenancy.Modulos
import wms.backend.tenancy.ParametrosLiberacaoHerdada
import wms.backend.tenancy.TenantRepository
import java.util.UUID

/**
 * Liberação herdada do pedido na confirmação da nota — mesma regra do evento Java `negri-liberacao-herdada`
 * (C:\sk-java\eclipse-workspace\negri-liberacao-herdada), trazida pro WMS (usuário, 23/10/2026).
 *
 * Ao confirmar a nota, o Sankhya pede de novo a liberação do evento 3 (limite de crédito): o crédito usado do cliente
 * sobe entre o pedido e a nota (nota 70000: 21.215,21 × 19.953,92 liberados no pedido 69995). Se o MESMO evento já foi
 * liberado num documento acima na cadeia da TGFVAR (pedido, orçamento...), libera sozinho — o liberador não libera de novo.
 *
 *  - Só roda com o módulo [Modulos.LIBERACAO_HERDADA] (desligado por padrão; checado ANTES de consultar o Sankhya)
 *    e com eventos em ParametrosTenant.liberacaoHerdada — sem módulo, sem eventos ou sem ler a configuração: nada.
 *  - Só TABELA TGFCAB (SEQUENCIA 0) e TGFITE (sequência de origem pela TGFVAR); só os eventos configurados
 *    (Negri: 3, limite de crédito).
 *  - Pendente na conta do Sankhya: sem DHLIB ou VLRATUAL > VLRLIBERADO; reprovada nunca.
 *  - Origem liberada (DHLIB, VLRLIBERADO > 0, sem linha reprovada do evento); vale a etapa mais próxima.
 *  - Valor: com `compararValor` (padrão) só herda se VLRATUAL <= o liberado na origem. A Negri tem
 *    `compararValor = false` (decisão do cliente, 16/10/2026): herda qualquer valor.
 *  - Mesmo mecanismo da auto-liberação dos pesáveis (usuário, 23/10/2026): liberador de serviço
 *    (LIBERADOR_USUARIO / LIBERADOR_SENHA, validado no Sankhya) pelo LiberacaoLimitesSP.liberarNegarLimites, com
 *    vlrLib 0 (VLRLIBERADO recebe o VLRATUAL). A OBSLIB diz de onde veio e quem liberou na origem.
 */
object LiberacaoHerdada {

    private const val TAM_OBSLIB = 255

    /** O que a regra precisa do Sankhya e do liberador — trocável nos testes (sem Sankhya de verdade). */
    internal interface Porta {
        fun modulos(tenantId: UUID): Set<String>
        fun parametros(tenantId: UUID): ParametrosLiberacaoHerdada
        suspend fun pendentes(tenantSlug: String, sql: String): List<Map<String, String?>>
        fun credenciais(): Pair<String, String>?
        suspend fun validar(tenantSlug: String, usuario: String, senha: String): String
        suspend fun liberar(tenantSlug: String, linha: Map<String, String?>, codusu: String, obs: String)
    }

    private object PortaSankhya : Porta {
        override fun modulos(tenantId: UUID) = TenantRepository.modulosHabilitados(tenantId)
        override fun parametros(tenantId: UUID) = TenantRepository.parametros(tenantId).liberacaoHerdada
        override suspend fun pendentes(tenantSlug: String, sql: String) = SankhyaDbExplorerClient.executarQuery(tenantSlug, sql)
        override fun credenciais() = LiberacaoCorteService.credenciaisLiberador()
        override suspend fun validar(tenantSlug: String, usuario: String, senha: String) =
            LiberacaoCorteService.validarLiberador(tenantSlug, usuario, senha)
        override suspend fun liberar(tenantSlug: String, linha: Map<String, String?>, codusu: String, obs: String) =
            LiberacaoCorteService.chamarLiberarNegar(tenantSlug, listOf(linha), codusu, "S", obs, vlrLib = 0)
    }

    /** Libera na [nunota] o que já foi liberado na origem. Devolve quantas liberações foram feitas. Nunca lança. */
    suspend fun herdar(tenantSlug: String, tenantId: UUID, nunota: Long): Int =
        withContext(Dispatchers.IO) { herdar(tenantSlug, tenantId, nunota, PortaSankhya) }

    internal suspend fun herdar(tenantSlug: String, tenantId: UUID, nunota: Long, porta: Porta): Int {
        val config = runCatching { configuracao(porta.modulos(tenantId), porta.parametros(tenantId)) }
            .getOrElse {
                println("AVISO: liberação herdada — configuração do tenant $tenantSlug ilegível, nota $nunota fica manual: ${it.message}")
                return 0
            } ?: return 0
        val consultados = runCatching { porta.pendentes(tenantSlug, sqlPendentesComOrigem(nunota, config.eventos)) }
            .getOrElse {
                println("AVISO: liberação herdada — consulta da nota $nunota falhou: ${it.message}")
                return 0
            }
        val pendentes = if (!config.compararValor) consultados else consultados.filter(::valorCobertoPelaOrigem)
        if (pendentes.size < consultados.size) {
            println("INFO: liberação herdada — nota $nunota: ${consultados.size - pendentes.size} pendência(s) com valor acima do liberado na origem ficam manuais")
        }
        if (pendentes.isEmpty()) return 0
        val (usuario, senha) = porta.credenciais() ?: run {
            println("AVISO: LIBERADOR_USUARIO/LIBERADOR_SENHA não configurados — liberação herdada da nota $nunota fica manual.")
            return 0
        }
        val codusu = runCatching { porta.validar(tenantSlug, usuario, senha) }.getOrElse {
            println("AVISO: liberação herdada da nota $nunota — liberador de serviço não validou: ${it.message}")
            return 0
        }
        var liberadas = 0
        for (p in pendentes) {
            val obs = ("Liberação automática: evento ${p["EVENTO"]} já liberado no Nro. Único ${p["NUNOTAORIG"]} " +
                "por ${p["NOMEUSU"] ?: p["CODUSULIB"]} em ${p["DHLIBORIG"]} (liberado lá: ${p["VLRORIG"]}; aqui: ${p["VLRATUAL"]}).").take(TAM_OBSLIB)
            // Chaves como inteiro puro ("3", não "3.0") — o chamarLiberarNegar lê com toIntOrNull/toLongOrNull.
            val linha = listOf("NUCHAVE", "EVENTO", "NUCLL", "SEQCASCATA", "SEQUENCIA").associateWith {
                p[it]?.trim()?.toBigDecimalOrNull()?.toLong()?.toString() ?: "0"
            } + ("TABELA" to p["TABELA"]?.trim())
            runCatching { porta.liberar(tenantSlug, linha, codusu, obs) }
                .onSuccess {
                    liberadas++
                    println("INFO: liberação herdada — nota $nunota ${linha["TABELA"]} seq ${linha["SEQUENCIA"]} evento ${linha["EVENTO"]} " +
                        "liberada (origem ${p["NUNOTAORIG"]})")
                }.onFailure {
                    println("AVISO: liberação herdada — nota $nunota ${linha["TABELA"]} seq ${linha["SEQUENCIA"]} evento ${linha["EVENTO"]} falhou: ${it.message}")
                }
        }
        return liberadas
    }

    /**
     * Linhas pendentes da nota com a liberação de origem mais próxima na cadeia da TGFVAR (CONNECT BY a partir de cada
     * item da nota; cabeçalho casa com qualquer item, item só com a própria sequência).
     */
    /** Configuração efetiva: null = não roda (módulo desligado ou nenhum evento configurado). */
    internal fun configuracao(modulos: Set<String>, parametros: ParametrosLiberacaoHerdada): ParametrosLiberacaoHerdada? =
        parametros.takeIf { Modulos.LIBERACAO_HERDADA in modulos && it.eventos.isNotEmpty() && it.validar().isEmpty() }

    /** VLRATUAL (pedido agora) <= VLRORIG (liberado na origem). Valor ausente ou ilegível = não coberto. */
    internal fun valorCobertoPelaOrigem(linha: Map<String, String?>): Boolean {
        val atual = linha["VLRATUAL"]?.trim()?.toBigDecimalOrNull() ?: return false
        val origem = linha["VLRORIG"]?.trim()?.toBigDecimalOrNull() ?: return false
        return atual <= origem
    }

    internal fun sqlPendentesComOrigem(nunota: Long, eventos: List<Int>) = """
        SELECT * FROM (
          SELECT L.NUCHAVE, L.TABELA, L.EVENTO, L.SEQUENCIA, L.SEQCASCATA, L.NUCLL, L.VLRATUAL,
                 O.NUCHAVE AS NUNOTAORIG, O.CODUSULIB, O.VLRLIBERADO AS VLRORIG,
                 TO_CHAR(O.DHLIB, 'DD/MM/YYYY HH24:MI') AS DHLIBORIG, U.NOMEUSU,
                 ROW_NUMBER() OVER (PARTITION BY L.TABELA, L.EVENTO, L.SEQUENCIA, L.SEQCASCATA, L.NUCLL
                                    ORDER BY V.NIVEL, O.DHLIB DESC) AS RN
            FROM TSILIB L
            JOIN (SELECT CONNECT_BY_ROOT V.SEQUENCIA AS SEQNOTA, V.NUNOTAORIG, V.SEQUENCIAORIG, LEVEL AS NIVEL
                    FROM TGFVAR V
                   START WITH V.NUNOTA = $nunota
                 CONNECT BY NOCYCLE PRIOR V.NUNOTAORIG = V.NUNOTA AND PRIOR V.SEQUENCIAORIG = V.SEQUENCIA) V
              ON L.TABELA = 'TGFCAB' OR V.SEQNOTA = L.SEQUENCIA
            JOIN TSILIB O ON O.NUCHAVE = V.NUNOTAORIG AND O.TABELA = L.TABELA AND O.EVENTO = L.EVENTO
                         AND O.SEQUENCIA = CASE WHEN L.TABELA = 'TGFCAB' THEN 0 ELSE V.SEQUENCIAORIG END
            LEFT JOIN TSIUSU U ON U.CODUSU = O.CODUSULIB
           WHERE L.NUCHAVE = $nunota
             AND L.TABELA IN ('TGFCAB', 'TGFITE')
             AND L.EVENTO IN (${eventos.joinToString()})
             AND NVL(L.REPROVADO, 'N') = 'N'
             AND (L.DHLIB IS NULL OR L.VLRATUAL > NVL(L.VLRLIBERADO, 0))
             AND O.DHLIB IS NOT NULL AND NVL(O.REPROVADO, 'N') = 'N' AND O.VLRLIBERADO > 0
             AND NOT EXISTS (SELECT 1 FROM TSILIB R
                              WHERE R.NUCHAVE = O.NUCHAVE AND R.TABELA = O.TABELA AND R.EVENTO = O.EVENTO
                                AND R.SEQUENCIA = O.SEQUENCIA AND R.REPROVADO = 'S')
        ) WHERE RN = 1
    """.trimIndent()
}
