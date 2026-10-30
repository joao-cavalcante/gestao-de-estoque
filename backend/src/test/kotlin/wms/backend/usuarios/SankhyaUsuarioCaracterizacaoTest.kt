package wms.backend.usuarios

import wms.backend.tenancy.ParametrosTenant
import wms.backend.tenancy.ParametrosUsuarioSankhya
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Caracterização (Entrega 1, Fase 0) — payload da criação de usuário no Sankhya como saía do código antes de
 * parametrizar (modelo do CODUSU 194, CODGRUPO 32, e-mail @negri.local). Desde a Fase 5 ele só existe no perfil da
 * Negri (V66) — estes testes provam que o perfil reproduz o payload antigo.
 */
class SankhyaUsuarioCaracterizacaoTest {
    private val modeloNegri = linkedMapOf(
        "ABREGAVETA" to "N",
        "ACESSAFORMULAREL" to "N",
        "ACESSOVISUALCAB" to "T",
        "ALTCTAFAT" to "N",
        "ALTCTAIMPBOL" to "N",
        "ALTORDCFECH" to "N",
        "APENASCOMPLIB" to "N",
        "APROVCOT" to "M",
        "AVISAVARPRECO" to "N",
        "CAIXA" to "N",
        "CODCENCUSPAD" to "0",
        "CODGRUPO" to "32",
        "CODIDECONECT" to "0",
        "CODPARC" to "0",
        "CODVEND" to "0",
        "DESCTOTALNOTAPDV" to "N",
        "EXCLIBORC" to "N",
        "EXIBIRVALANALRENT" to "S",
        "IGNORAHORASCRUZ" to "N",
        "IMP2SANSUPCAI" to "S",
        "IMPNFCENTRAL" to "S",
        "INSTALAPACOTESS" to "N",
        "INTEGRAECONECT" to "N",
        "LOCALE" to "PT_BR",
        "MINUTOSFIN" to "0",
        "NIVEL" to "100",
        "PERMALTMOEDA" to "N",
        "PERMEXPREL" to "S",
        "PERMIMPRIMEREL" to "N",
        "PERMREPERRO" to "N",
        "PORTASMTP" to "25",
        "RESTRINGECART" to "N",
        "SEGURANCASMTP" to "N",
        "SELECTWCAPO" to "N",
        "SENHANUNCAEXPIRA" to "S",
        "TEMECF" to "N",
        "TIMBAIXAWORD" to "S",
        "TIMBAIXTITRECABE" to "N",
        "TIMVERTODASFACS" to "N",
        "TIPOSMTP" to "N",
        "TIPOUSU" to "0",
        "VERCABPROPRIA" to "N",
        "VISACESOUTUSU" to "S",
    )

    /** Perfil da Negri exatamente como a V66 grava (lido do próprio arquivo da migração). */
    private val perfilNegri: ParametrosUsuarioSankhya by lazy {
        val sql = File("../db/migrations/V66__usuario_sankhya_tenant.sql").readText()
        ParametrosTenant.ler(sql.substring(sql.indexOf("'{") + 1, sql.indexOf("}'::jsonb") + 1)).usuarioSankhya
    }

    @Test
    fun `Negri depois da V66 - mesmo payload de antes (modelo do 194, CODUSU, NOMEUSU e e-mail)`() {
        assertNull(SankhyaUsuarioService.motivoIndisponivel(perfilNegri))
        val campos = SankhyaUsuarioService.montarCampos(perfilNegri, 901, "JOSE DA SILVA", "José da Silva", null)
        val esperado = LinkedHashMap(modeloNegri).apply {
            put("CODUSU", "901"); put("NOMEUSU", "JOSE DA SILVA"); put("ACCOUNTEMAIL", "jose.silva@negri.local")
        }
        // Mesmo conteúdo; a ordem das chaves não vai pro Sankhya (localFields é um objeto).
        assertEquals(esperado.toSortedMap(), campos.toSortedMap())
    }

    @Test
    fun `Negri - grupo 32 e usuario-modelo 194`() {
        assertEquals("32", SankhyaUsuarioService.montarCampos(perfilNegri, 1, "A B", "A B", null)["CODGRUPO"])
        assertEquals(194, perfilNegri.usuarioModelo)
    }

    @Test
    fun `Negri - dominio negri local, primeiro e ultimo nome, sem acento`() {
        assertEquals("joao.cavalcante@negri.local", SankhyaUsuarioService.emailSankhya("João  Pedro Cavalcante", perfilNegri.dominioEmail!!))
        assertEquals("maria.maria@negri.local", SankhyaUsuarioService.emailSankhya("Maria", perfilNegri.dominioEmail!!))
    }

    @Test
    fun `e-mail informado substitui o gerado`() {
        assertEquals("x@y.com", SankhyaUsuarioService.montarCampos(perfilNegri, 1, "A B", "A B", "x@y.com")["ACCOUNTEMAIL"])
    }
}
