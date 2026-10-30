package wms.backend.usuarios

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import wms.backend.erp.SankhyaDbExplorerClient
import wms.backend.erp.SankhyaSpClient
import wms.backend.tenancy.ParametrosUsuarioSankhya
import wms.backend.tenancy.TenantRepository
import java.text.Normalizer

/**
 * Cria o usuário do WMS no Sankhya (TSIUSU) pela ENTIDADE `Usuario` (CRUDServiceProvider.saveRecord,
 * credencial de integração do tenant — precisa de permissão na tela de Usuários do Sankhya).
 *
 * Só com `parametros.usuarioSankhya` completo (habilitado, usuário-modelo, grupo, domínio de e-mail — V66); sem isso
 * o recurso fica indisponível e nada é escolhido sozinho. O novo usuário recebe os [CAMPOS_MODELO] com os valores
 * do usuário-modelo (lidos da TSIUSU), ou os `camposFixos` da configuração (Negri: os valores do CODUSU 194 de
 * 10/2026, congelados como antes), + CODGRUPO configurado, CODUSU (próximo livre), NOMEUSU (nome completo,
 * maiúsculo, sem acento) e ACCOUNTEMAIL (primeiro.ultimo@<domínio>). Senha não vai (o Sankhya trata à parte).
 * Nome já existente = só devolve o CODUSU. Usuário-modelo ou grupo que não existe no Sankhya = não cria.
 */
object SankhyaUsuarioService {

    /** Colunas da TSIUSU copiadas do usuário-modelo (permissões e preferências; nunca identidade nem senha). */
    internal val CAMPOS_MODELO = listOf(
        "ABREGAVETA", "ACESSAFORMULAREL", "ACESSOVISUALCAB", "ALTCTAFAT", "ALTCTAIMPBOL", "ALTORDCFECH", "APENASCOMPLIB",
        "APROVCOT", "AVISAVARPRECO", "CAIXA", "CODCENCUSPAD", "CODIDECONECT", "CODPARC", "CODVEND", "DESCTOTALNOTAPDV",
        "EXCLIBORC", "EXIBIRVALANALRENT", "IGNORAHORASCRUZ", "IMP2SANSUPCAI", "IMPNFCENTRAL", "INSTALAPACOTESS",
        "INTEGRAECONECT", "LOCALE", "MINUTOSFIN", "NIVEL", "PERMALTMOEDA", "PERMEXPREL", "PERMIMPRIMEREL", "PERMREPERRO",
        "PORTASMTP", "RESTRINGECART", "SEGURANCASMTP", "SELECTWCAPO", "SENHANUNCAEXPIRA", "TEMECF", "TIMBAIXAWORD",
        "TIMBAIXTITRECABE", "TIMVERTODASFACS", "TIPOSMTP", "TIPOUSU", "VERCABPROPRIA", "VISACESOUTUSU",
    )

    data class Resultado(val codusu: Int, val nomeUsu: String, val jaExistia: Boolean)

    class SankhyaUsuarioException(message: String) : Exception(message)

    /** Criação não configurada pro tenant (não é falha do Sankhya). */
    class CriacaoIndisponivelException(message: String) : Exception(message)

    /** Motivo de a configuração não servir (null = utilizável). */
    internal fun motivoIndisponivel(p: ParametrosUsuarioSankhya): String? = when {
        !p.habilitado -> "criação de usuário no Sankhya não habilitada pra esta instalação"
        p.usuarioModelo == null -> "usuário-modelo não configurado"
        p.codGrupo == null -> "grupo do usuário não configurado"
        p.dominioEmail.isNullOrBlank() -> "domínio de e-mail não configurado"
        p.validar().isNotEmpty() -> "configuração inválida: ${p.validar().joinToString("; ")}"
        else -> null
    }

    fun nomeSankhya(nome: String): String =
        Normalizer.normalize(nome, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
            .trim().split(Regex("\\s+")).joinToString(" ").uppercase()

    fun emailSankhya(nome: String, dominio: String): String {
        val partes = nomeSankhya(nome).lowercase().split(" ")
        return "${partes.first()}.${partes.last()}@${dominio.trim().removePrefix("@")}"
    }

    /**
     * Campos do saveRecord de Usuario: valores do modelo ([valoresModelo], ou os `camposFixos` da configuração) +
     * CODGRUPO configurado + CODUSU, NOMEUSU e ACCOUNTEMAIL do novo usuário. Só chamar com [motivoIndisponivel] null.
     */
    internal fun montarCampos(
        p: ParametrosUsuarioSankhya,
        codusu: Int,
        nomeUsu: String,
        nome: String,
        email: String?,
        valoresModelo: Map<String, String?> = emptyMap(),
    ): LinkedHashMap<String, String> {
        val base = LinkedHashMap<String, String>()
        if (p.camposFixos.isNotEmpty()) base.putAll(p.camposFixos)
        else CAMPOS_MODELO.forEach { campo -> valoresModelo[campo]?.trim()?.takeIf { it.isNotEmpty() }?.let { base[campo] = it } }
        base["CODGRUPO"] = p.codGrupo.toString()
        base["CODUSU"] = codusu.toString()
        base["NOMEUSU"] = nomeUsu
        base["ACCOUNTEMAIL"] = email?.takeIf { it.isNotBlank() } ?: emailSankhya(nome, p.dominioEmail!!)
        return base
    }

    private suspend fun codusuPorNome(tenantSlug: String, nomeUsu: String): Int? =
        SankhyaDbExplorerClient.executarQuery(
            tenantSlug, "SELECT CODUSU FROM TSIUSU WHERE UPPER(NOMEUSU) = '${nomeUsu.replace("'", "''")}'",
        ).firstOrNull()?.get("CODUSU")?.trim()?.toIntOrNull()

    suspend fun criar(tenantSlug: String, nome: String, email: String? = null): Resultado {
        val tenantId = TenantRepository.buscarPorSlug(tenantSlug)?.id?.let(java.util.UUID::fromString)
            ?: throw CriacaoIndisponivelException("tenant '$tenantSlug' não encontrado")
        val p = TenantRepository.parametros(tenantId).usuarioSankhya
        motivoIndisponivel(p)?.let { throw CriacaoIndisponivelException(it) }
        val nomeUsu = nomeSankhya(nome)
        if (nomeUsu.isBlank()) throw SankhyaUsuarioException("nome vazio")
        codusuPorNome(tenantSlug, nomeUsu)?.let { return Resultado(it, nomeUsu, jaExistia = true) }

        // Modelo e grupo conferidos no Sankhya (leitura) antes de qualquer gravação.
        // Com camposFixos (Negri) os valores não vêm do modelo: só a existência dele é conferida.
        val colunas = if (p.camposFixos.isNotEmpty()) "CODUSU" else CAMPOS_MODELO.joinToString()
        val modelo = SankhyaDbExplorerClient.executarQuery(
            tenantSlug, "SELECT $colunas FROM TSIUSU WHERE CODUSU = ${p.usuarioModelo}",
        ).firstOrNull() ?: throw SankhyaUsuarioException("usuário-modelo ${p.usuarioModelo} não existe no Sankhya")
        SankhyaDbExplorerClient.executarQuery(tenantSlug, "SELECT CODGRUPO FROM TSIGRU WHERE CODGRUPO = ${p.codGrupo}")
            .firstOrNull() ?: throw SankhyaUsuarioException("grupo ${p.codGrupo} não existe no Sankhya")

        val codusu = SankhyaDbExplorerClient.executarQuery(tenantSlug, "SELECT NVL(MAX(CODUSU), 0) + 1 AS PROX FROM TSIUSU")
            .firstOrNull()?.get("PROX")?.trim()?.toIntOrNull()
            ?: throw SankhyaUsuarioException("não consegui obter o próximo CODUSU")
        val campos = montarCampos(p, codusu, nomeUsu, nome, email, modelo)
        try {
            SankhyaSpClient.chamarRaw(
                tenantSlug, "CRUDServiceProvider.saveRecord", "mge",
                buildJsonObject {
                    putJsonObject("dataSet") {
                        put("rootEntity", "Usuario")
                        put("includePresentationFields", "N")
                        putJsonObject("dataRow") {
                            putJsonObject("localFields") { campos.forEach { (k, v) -> putJsonObject(k) { put("\$", v) } } }
                        }
                        putJsonObject("entity") { putJsonObject("fieldset") { put("list", "CODUSU,NOMEUSU") } }
                    }
                },
            )
        } catch (e: Exception) {
            throw SankhyaUsuarioException(e.message ?: "falha ao criar no Sankhya")
        }
        return Resultado(codusuPorNome(tenantSlug, nomeUsu) ?: codusu, nomeUsu, jaExistia = false)
    }
}
