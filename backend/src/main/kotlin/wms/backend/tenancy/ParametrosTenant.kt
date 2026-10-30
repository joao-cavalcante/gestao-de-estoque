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
) {
    /** Erros de validação (vazio = válido). Conferido antes de gravar. */
    fun validar(): List<String> = liberacaoHerdada.validar() + toleranciaPesavel.validar() +
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
 * Módulo [Modulos.LIBERACAO_HERDADA]. [eventos] vazio = nada é herdado mesmo com o módulo ligado.
 * [compararValor] = true só herda quando o valor pedido agora (VLRATUAL) não passa do liberado na origem;
 * false (perfil legado da Negri, decisão do cliente em 16/10/2026) herda qualquer valor.
 */
@Serializable
data class ParametrosLiberacaoHerdada(
    val eventos: List<Int> = emptyList(),
    val compararValor: Boolean = true,
) {
    fun validar(): List<String> = buildList {
        if (eventos.any { it <= 0 }) add("liberacaoHerdada.eventos: só códigos de evento positivos")
        if (eventos.distinct().size != eventos.size) add("liberacaoHerdada.eventos: evento repetido")
    }
}
