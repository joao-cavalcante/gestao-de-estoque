package wms.backend.tenancy

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Parâmetros de operação por tenant (`tenancy.erp_connections.parametros`, V62) — o complemento tipado dos
 * [Modulos]: o módulo liga a regra, o parâmetro diz como ela roda. Todo valor padrão é o SEGURO (nada automático,
 * nada específico de cliente); o comportamento da Negri vem só da configuração gravada pra ela nas migrações.
 * Um JSON ilegível vale como "sem parâmetros" (os padrões) — nunca como permissão.
 */
@Serializable
data class ParametrosTenant(
    val liberacaoHerdada: ParametrosLiberacaoHerdada = ParametrosLiberacaoHerdada(),
    val liberador: ParametrosLiberador = ParametrosLiberador(),
    val toleranciaPesavel: PoliticaToleranciaPesavel = PoliticaToleranciaPesavel(),
    /** Fuso da operação (ID IANA). null = FusoInstalacao.PADRAO (America/Sao_Paulo). Ver FusoInstalacao. */
    val fuso: String? = null,
    val usuarioSankhya: ParametrosUsuarioSankhya = ParametrosUsuarioSankhya(),
    val ajustePesoCompra: ParametrosAjustePesoCompra = ParametrosAjustePesoCompra(),
) {
    /** Erros de validação (vazio = válido). Conferido antes de gravar. */
    fun validar(): List<String> = liberacaoHerdada.validar() + toleranciaPesavel.validar() + usuarioSankhya.validar() +
        ajustePesoCompra.validar() +
        listOfNotNull(fuso?.takeIf { !FusoInstalacao.valido(it) }?.let { "fuso: '$it' não é um fuso válido (ex.: America/Sao_Paulo)" })

    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun ler(bruto: String?): ParametrosTenant =
            if (bruto.isNullOrBlank()) ParametrosTenant()
            else runCatching { json.decodeFromString(serializer(), bruto) }.getOrElse {
                println("AVISO: parâmetros do tenant ilegíveis — valendo os padrões seguros: ${it.message}")
                ParametrosTenant()
            }

        fun gravar(p: ParametrosTenant): String = json.encodeToString(serializer(), p)
    }
}

/**
 * Liberador de serviço (liberações automáticas). A credencial fica cifrada em `erp_connections.credencial_liberador`
 * (V63). [fallbackVariavelAmbiente] = TRANSIÇÃO da instalação legada (só a Negri, V63): sem credencial no tenant,
 * aceita LIBERADOR_USUARIO/LIBERADOR_SENHA do processo. Desligar depois de cadastrar a credencial do tenant.
 */
@Serializable
data class ParametrosLiberador(
    val fallbackVariavelAmbiente: Boolean = false,
)

/**
 * Auto-liberação de corte de item pesável quando o NUCCO NÃO tem tolerância configurada (V50). Com tolerância no
 * NUCCO, vale a do NUCCO (inclusive "sem limite" explícito). [semConfiguracaoNucco] null (padrão do produto) = nada é
 * liberado sozinho: o corte vai pra liberação manual. Negri (V64): acima sem limite, abaixo 5% — a regra de antes.
 */
@Serializable
data class PoliticaToleranciaPesavel(
    val semConfiguracaoNucco: ToleranciaPadrao? = null,
) {
    fun validar(): List<String> = semConfiguracaoNucco?.validar().orEmpty()
}

/** Em %, por sentido. Sem `pct` e sem `semLimite` = aquele sentido NÃO é liberado sozinho. */
@Serializable
data class ToleranciaPadrao(
    val acimaPct: Double? = null,
    val acimaSemLimite: Boolean = false,
    val abaixoPct: Double? = null,
    val abaixoSemLimite: Boolean = false,
) {
    fun validar(): List<String> = buildList {
        if ((acimaPct ?: 0.0) < 0 || (abaixoPct ?: 0.0) < 0) add("toleranciaPesavel: percentual negativo")
        if (acimaPct != null && acimaSemLimite) add("toleranciaPesavel: acimaPct e acimaSemLimite juntos")
        if (abaixoPct != null && abaixoSemLimite) add("toleranciaPesavel: abaixoPct e abaixoSemLimite juntos")
    }
}

/**
 * Criação de usuário no Sankhya pela tela de Usuários (SankhyaUsuarioService). Padrão: desligada. Habilitada só com
 * [usuarioModelo] (CODUSU cujas permissões são copiadas), [codGrupo] e [dominioEmail] — nada é escolhido sozinho.
 * [camposFixos] (opcional) = valores congelados em vez de ler o modelo no Sankhya (Negri, V66: cópia do CODUSU 194).
 */
@Serializable
data class ParametrosUsuarioSankhya(
    val habilitado: Boolean = false,
    val usuarioModelo: Int? = null,
    val codGrupo: Int? = null,
    val dominioEmail: String? = null,
    val camposFixos: Map<String, String> = emptyMap(),
) {
    fun validar(): List<String> = buildList {
        if (usuarioModelo != null && usuarioModelo <= 0) add("usuarioSankhya.usuarioModelo: CODUSU inválido")
        if (codGrupo != null && codGrupo < 0) add("usuarioSankhya.codGrupo: grupo inválido")
        if (dominioEmail != null && !DOMINIO.matches(dominioEmail.trim())) add("usuarioSankhya.dominioEmail: domínio inválido")
        if (camposFixos.keys.any { it in IDENTIDADE }) {
            add("usuarioSankhya.camposFixos: CODUSU, NOMEUSU, ACCOUNTEMAIL e CODGRUPO não entram nos campos fixos")
        }
    }

    private companion object {
        val DOMINIO = Regex("""^@?[A-Za-z0-9-]+(\.[A-Za-z0-9-]+)+$""")
        val IDENTIDADE = setOf("CODUSU", "NOMEUSU", "ACCOUNTEMAIL", "CODGRUPO")
    }
}

/**
 * Diferença de peso na COMPRA por faixas (wms.backend.separacao.AjustePesoCompra), configuração global do cliente.
 * Ligado ([habilitado]), cada sentido (a maior / a menor) passa por até três faixas:
 *  1. DESCONSIDERAR — até [desconsiderarAcimaPct]/[desconsiderarAbaixoPct] (gelo, água): entra pelo peso da nota, sem
 *     documento. null = faixa desligada naquele sentido.
 *  2. AJUSTAR — até a tolerância (produto, senão NUCCO): nota intacta e a diferença vira nota de ajuste (TOP
 *     [topEntrada]/[topSaida]). Desligada ([ajusteEntrada]/[ajusteSaida] = false) = vai pra faixa 3.
 *  3. DIVERGÊNCIA — o resto: peso lido, divergência e liberação; o Sankhya trata pela CCO (pedido complementar,
 *     corte/devolução).
 * Desligado ([habilitado] = false) = regra de antes (a maior dentro da tolerância vai com o peso da nota, sem ajuste).
 */
@Serializable
data class ParametrosAjustePesoCompra(
    val habilitado: Boolean = false,
    val desconsiderarAcimaPct: Double? = null,
    val desconsiderarAbaixoPct: Double? = null,
    val ajusteEntrada: Boolean = false,
    val ajusteSaida: Boolean = false,
    val topEntrada: Int? = null,
    val topSaida: Int? = null,
    /** Série das notas de ajuste por sentido (o nativo usa SERIETOPDIFMAIOR/MENOR da empresa). null = a da TOP. */
    val serieEntrada: String? = null,
    val serieSaida: String? = null,
) {
    fun validar(): List<String> = buildList {
        if (listOfNotNull(desconsiderarAcimaPct, desconsiderarAbaixoPct).any { it < 0 || it > 100 }) {
            add("ajustePesoCompra: percentual de desconsiderar entre 0 e 100")
        }
        if (listOfNotNull(serieEntrada, serieSaida).any { it.trim().length > 3 }) add("ajustePesoCompra: série com mais de 3 caracteres")
        if ((topEntrada ?: 1) <= 0 || (topSaida ?: 1) <= 0) add("ajustePesoCompra: TOP inválida")
        if (topEntrada != null && topEntrada == topSaida) add("ajustePesoCompra: TOP de entrada e de saída iguais")
        if (ajusteEntrada && topEntrada == null) add("ajustePesoCompra: ajuste do peso a maior ligado sem TOP de entrada")
        if (ajusteSaida && topSaida == null) add("ajustePesoCompra: ajuste do peso a menor ligado sem TOP de saída")
    }
}

/**
 * Módulo [Modulos.LIBERACAO_HERDADA]. [eventos] vazio = nada é herdado mesmo com o módulo ligado.
 * [todosEventos] = herda QUALQUER evento já liberado na origem (a lista [eventos] é ignorada) — menos os de corte
 * (64), que nunca são herdados (LiberacaoHerdada.EVENTOS_CORTE). Negri desde 06/11/2026 (V73).
 * [compararValor] = true só herda quando o valor pedido agora (VLRATUAL) não passa do liberado na origem;
 * false (perfil legado da Negri, decisão do cliente em 16/10/2026) herda qualquer valor.
 */
/** Liberação de corte (TSILIB TGFCOI2, evento 64 — ConferenciaSP.cortar): decisão do liberador, nunca herdada. */
val EVENTOS_CORTE: List<Int> = listOf(64)

@Serializable
data class ParametrosLiberacaoHerdada(
    val eventos: List<Int> = emptyList(),
    val compararValor: Boolean = true,
    val todosEventos: Boolean = false,
) {
    fun validar(): List<String> = buildList {
        if (eventos.any { it in EVENTOS_CORTE }) add("liberacaoHerdada.eventos: evento de corte (${EVENTOS_CORTE.joinToString()}) nunca é herdado")
        if (eventos.any { it <= 0 }) add("liberacaoHerdada.eventos: só códigos de evento positivos")
        if (eventos.distinct().size != eventos.size) add("liberacaoHerdada.eventos: evento repetido")
    }
}
