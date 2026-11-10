package wms.backend.separacao

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import wms.backend.erp.SankhyaDbExplorerClient
import wms.backend.liberacaocorte.LiberacaoCorteService
import wms.backend.tenancy.EVENTOS_CORTE
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
 *  - Só TABELA TGFCAB (SEQUENCIA 0) e TGFITE (sequência de origem pela TGFVAR); só os eventos configurados, ou
 *    todos com `todosEventos` (Negri desde 06/11/2026: "com exceção dos eventos de corte, tudo que for liberado no
 *    pedido e pedido de novo na nota é liberado automaticamente"). Corte (EVENTOS_CORTE, 64) nunca é herdado.
 *  - `todosEventos` (Negri desde 09/11/2026: "na nota todos os eventos serão liberados automaticamente ao invés de
 *    fazer um espelho" / "todos menos o evento de corte"): libera TODA pendência da nota (TGFCAB/TGFITE), tenha ou
 *    não liberação no pedido, inclusive linha reprovada — menos o corte. A origem, se houver, só vai pra OBSLIB/log;
 *    `compararValor` não se aplica. Nota 82606 (evento 3 sem liberação no pedido 82043) foi o caso.
 *  - Lista de eventos (sem `todosEventos`): espelho do pedido, regras abaixo.
 *  - Pendente na conta do Sankhya: sem DHLIB ou VLRATUAL > VLRLIBERADO; reprovada nunca (só no espelho).
 *  - Origem liberada (DHLIB, VLRLIBERADO > 0, sem linha reprovada do evento); vale a etapa mais próxima.
 *  - Valor: com `compararValor` (padrão) só herda se VLRATUAL <= o liberado na origem. A Negri tem
 *    `compararValor = false` (decisão do cliente, 16/10/2026): herda qualquer valor.
 *  - Mesmo mecanismo da auto-liberação dos pesáveis (usuário, 23/10/2026): liberador de serviço do tenant
 *    (LiberadorServico, validado no Sankhya) pelo LiberacaoLimitesSP.liberarNegarLimites, com
 *    vlrLib 0 (VLRLIBERADO recebe o VLRATUAL). A OBSLIB diz de onde veio e quem liberou na origem.
 */
object LiberacaoHerdada {

    private const val TAM_OBSLIB = 255

    /** O que a regra precisa do Sankhya e do liberador — trocável nos testes (sem Sankhya de verdade). */
    internal interface Porta {
        fun modulos(tenantId: UUID): Set<String>
        fun parametros(tenantId: UUID): ParametrosLiberacaoHerdada
        suspend fun pendentes(tenantSlug: String, sql: String): List<Map<String, String?>>
        fun credenciais(tenantId: UUID): Pair<String, String>?
        suspend fun validar(tenantSlug: String, usuario: String, senha: String): String
        suspend fun liberar(tenantSlug: String, linha: Map<String, String?>, codusu: String, obs: String)
    }

    private object PortaSankhya : Porta {
        override fun modulos(tenantId: UUID) = TenantRepository.modulosHabilitados(tenantId)
        override fun parametros(tenantId: UUID) = TenantRepository.parametros(tenantId).liberacaoHerdada
        override suspend fun pendentes(tenantSlug: String, sql: String) = SankhyaDbExplorerClient.executarQuery(tenantSlug, sql)
        override fun credenciais(tenantId: UUID) =
            wms.backend.liberacaocorte.LiberadorServico.credencial(tenantId, "liberação herdada")?.let { it.usuario to it.senha }
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
        val sql = if (config.todosEventos) sqlTodasPendentes(nunota) else sqlPendentesComOrigem(nunota, config.eventos)
        val consultados = runCatching { porta.pendentes(tenantSlug, sql) }
            .getOrElse {
                println("AVISO: liberação herdada — consulta da nota $nunota falhou: ${it.message}")
                return 0
            }
        val pendentes = if (config.todosEventos || !config.compararValor) consultados else consultados.filter(::valorCobertoPelaOrigem)
        if (pendentes.size < consultados.size) {
            println("INFO: liberação herdada — nota $nunota: ${consultados.size - pendentes.size} pendência(s) com valor acima do liberado na origem ficam manuais")
        }
        if (pendentes.isEmpty()) return 0
        val (usuario, senha) = porta.credenciais(tenantId) ?: run {
            println("AVISO: liberador de serviço indisponível no tenant $tenantSlug — liberação herdada da nota $nunota fica manual.")
            return 0
        }
        val codusu = runCatching { porta.validar(tenantSlug, usuario, senha) }.getOrElse {
            println("AVISO: liberação herdada da nota $nunota — liberador de serviço não validou: ${it.message}")
            return 0
        }
        var liberadas = 0
        for (p in pendentes) {
            val origem = p["NUNOTAORIG"]?.takeIf { it.isNotBlank() }
            val obs = (if (origem == null) {
                "Liberação automática da Torre de Operação na confirmação da nota: evento ${p["EVENTO"]}, valor ${p["VLRATUAL"]}."
            } else {
                "Liberação automática: evento ${p["EVENTO"]} já liberado no Nro. Único $origem " +
                    "por ${p["NOMEUSU"] ?: p["CODUSULIB"]} em ${p["DHLIBORIG"]} (liberado lá: ${p["VLRORIG"]}; aqui: ${p["VLRATUAL"]})."
            }).take(TAM_OBSLIB)
            // Chaves como inteiro puro ("3", não "3.0") — o chamarLiberarNegar lê com toIntOrNull/toLongOrNull.
            val linha = listOf("NUCHAVE", "EVENTO", "NUCLL", "SEQCASCATA", "SEQUENCIA").associateWith {
                p[it]?.trim()?.toBigDecimalOrNull()?.toLong()?.toString() ?: "0"
            } + ("TABELA" to p["TABELA"]?.trim())
            runCatching { porta.liberar(tenantSlug, linha, codusu, obs) }
                .onSuccess {
                    liberadas++
                    println("INFO: liberação herdada — nota $nunota ${linha["TABELA"]} seq ${linha["SEQUENCIA"]} evento ${linha["EVENTO"]} " +
                        "liberada (${p["NUNOTAORIG"]?.takeIf { it.isNotBlank() }?.let { "origem $it" } ?: "sem liberação anterior, valor ${p["VLRATUAL"]}"})")
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
        parametros.takeIf {
            Modulos.LIBERACAO_HERDADA in modulos && (it.todosEventos || it.eventos.isNotEmpty()) && it.validar().isEmpty()
        }

    /** VLRATUAL (pedido agora) <= VLRORIG (liberado na origem). Valor ausente ou ilegível = não coberto. */
    internal fun valorCobertoPelaOrigem(linha: Map<String, String?>): Boolean {
        val atual = linha["VLRATUAL"]?.trim()?.toBigDecimalOrNull() ?: return false
        val origem = linha["VLRORIG"]?.trim()?.toBigDecimalOrNull() ?: return false
        return atual <= origem
    }

    /** Filtro de evento: a lista configurada ou, com [todos], qualquer um — corte fora nos dois casos. */
    internal fun filtroEventos(eventos: List<Int>, todos: Boolean): String {
        val semCorte = "L.EVENTO NOT IN (${EVENTOS_CORTE.joinToString()})"
        return if (todos) semCorte else "L.EVENTO IN (${eventos.joinToString()}) AND $semCorte"
    }

    /**
     * `todosEventos`: toda pendência da nota menos o corte, inclusive reprovada. A liberação de origem mais próxima na
     * TGFVAR vem só pra OBSLIB/log (LEFT JOIN — não é condição).
     */
    internal fun sqlTodasPendentes(nunota: Long) = """
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
             AND ${filtroEventos(emptyList(), todos = true)}
             AND (L.DHLIB IS NULL OR L.VLRATUAL > NVL(L.VLRLIBERADO, 0))
        ) WHERE RN = 1
    """.trimIndent()

    internal fun sqlPendentesComOrigem(nunota: Long, eventos: List<Int>, todos: Boolean = false) = """
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
             AND ${filtroEventos(eventos, todos)}
             AND NVL(L.REPROVADO, 'N') = 'N'
             AND (L.DHLIB IS NULL OR L.VLRATUAL > NVL(L.VLRLIBERADO, 0))
             AND O.DHLIB IS NOT NULL AND NVL(O.REPROVADO, 'N') = 'N' AND O.VLRLIBERADO > 0
             AND NOT EXISTS (SELECT 1 FROM TSILIB R
                              WHERE R.NUCHAVE = O.NUCHAVE AND R.TABELA = O.TABELA AND R.EVENTO = O.EVENTO
                                AND R.SEQUENCIA = O.SEQUENCIA AND R.REPROVADO = 'S')
        ) WHERE RN = 1
    """.trimIndent()
}
