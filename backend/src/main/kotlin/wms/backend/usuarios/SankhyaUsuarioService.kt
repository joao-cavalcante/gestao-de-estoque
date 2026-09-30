package wms.backend.usuarios

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import wms.backend.erp.SankhyaDbExplorerClient
import wms.backend.erp.SankhyaSpClient
import java.text.Normalizer

/**
 * Cria o usuário do WMS no Sankhya (TSIUSU) pela ENTIDADE `Usuario` (CRUDServiceProvider.saveRecord,
 * credencial de integração do tenant — precisa de permissão na tela de Usuários do Sankhya).
 * Replica o modelo do usuário ALLAN GARCIA BISPO (CODUSU 194, grupo 32 LOGÍSTICA/SEPARAÇÃO):
 * troca só CODUSU (próximo livre), NOMEUSU (nome completo, maiúsculo, sem acento) e ACCOUNTEMAIL
 * (primeiro.ultimo@negri.local). Senha não vai (o Sankhya trata à parte). Nome já existente = só devolve o CODUSU.
 */
object SankhyaUsuarioService {

    private val MODELO = linkedMapOf(
        "ABREGAVETA" to "N", "ACESSAFORMULAREL" to "N", "ACESSOVISUALCAB" to "T", "ALTCTAFAT" to "N",
        "ALTCTAIMPBOL" to "N", "ALTORDCFECH" to "N", "APENASCOMPLIB" to "N", "APROVCOT" to "M",
        "AVISAVARPRECO" to "N", "CAIXA" to "N", "CODCENCUSPAD" to "0", "CODGRUPO" to "32",
        "CODIDECONECT" to "0", "CODPARC" to "0", "CODVEND" to "0", "DESCTOTALNOTAPDV" to "N",
        "EXCLIBORC" to "N", "EXIBIRVALANALRENT" to "S", "IGNORAHORASCRUZ" to "N", "IMP2SANSUPCAI" to "S",
        "IMPNFCENTRAL" to "S", "INSTALAPACOTESS" to "N", "INTEGRAECONECT" to "N", "LOCALE" to "PT_BR",
        "MINUTOSFIN" to "0", "NIVEL" to "100", "PERMALTMOEDA" to "N", "PERMEXPREL" to "S",
        "PERMIMPRIMEREL" to "N", "PERMREPERRO" to "N", "PORTASMTP" to "25", "RESTRINGECART" to "N",
        "SEGURANCASMTP" to "N", "SELECTWCAPO" to "N", "SENHANUNCAEXPIRA" to "S", "TEMECF" to "N",
        "TIMBAIXAWORD" to "S", "TIMBAIXTITRECABE" to "N", "TIMVERTODASFACS" to "N", "TIPOSMTP" to "N",
        "TIPOUSU" to "0", "VERCABPROPRIA" to "N", "VISACESOUTUSU" to "S",
    )

    data class Resultado(val codusu: Int, val nomeUsu: String, val jaExistia: Boolean)

    class SankhyaUsuarioException(message: String) : Exception(message)

    fun nomeSankhya(nome: String): String =
        Normalizer.normalize(nome, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
            .trim().split(Regex("\\s+")).joinToString(" ").uppercase()

    fun emailSankhya(nome: String): String {
        val partes = nomeSankhya(nome).lowercase().split(" ")
        return "${partes.first()}.${partes.last()}@negri.local"
    }

    private suspend fun codusuPorNome(tenantSlug: String, nomeUsu: String): Int? =
        SankhyaDbExplorerClient.executarQuery(
            tenantSlug, "SELECT CODUSU FROM TSIUSU WHERE UPPER(NOMEUSU) = '${nomeUsu.replace("'", "''")}'",
        ).firstOrNull()?.get("CODUSU")?.trim()?.toIntOrNull()

    suspend fun criar(tenantSlug: String, nome: String, email: String? = null): Resultado {
        val nomeUsu = nomeSankhya(nome)
        if (nomeUsu.isBlank()) throw SankhyaUsuarioException("nome vazio")
        codusuPorNome(tenantSlug, nomeUsu)?.let { return Resultado(it, nomeUsu, jaExistia = true) }

        val codusu = SankhyaDbExplorerClient.executarQuery(tenantSlug, "SELECT NVL(MAX(CODUSU), 0) + 1 AS PROX FROM TSIUSU")
            .firstOrNull()?.get("PROX")?.trim()?.toIntOrNull()
            ?: throw SankhyaUsuarioException("não consegui obter o próximo CODUSU")
        val campos = LinkedHashMap(MODELO).apply {
            put("CODUSU", codusu.toString())
            put("NOMEUSU", nomeUsu)
            put("ACCOUNTEMAIL", email?.takeIf { it.isNotBlank() } ?: emailSankhya(nome))
        }
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
