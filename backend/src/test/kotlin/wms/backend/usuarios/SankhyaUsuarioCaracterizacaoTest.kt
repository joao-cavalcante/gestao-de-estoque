package wms.backend.usuarios

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Caracterização (Entrega 1, Fase 0) — payload ATUAL da criação de usuário no Sankhya, antes de parametrizar.
 * Registra os códigos da Negri (modelo do CODUSU 194, CODGRUPO 32, e-mail @negri.local) exatamente como saem hoje.
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

    @Test
    fun `payload atual - modelo fixo do 194 mais CODUSU, NOMEUSU e e-mail`() {
        val campos = SankhyaUsuarioService.montarCampos(901, "JOSE DA SILVA", "José da Silva", null)
        val esperado = LinkedHashMap(modeloNegri).apply {
            put("CODUSU", "901"); put("NOMEUSU", "JOSE DA SILVA"); put("ACCOUNTEMAIL", "jose.silva@negri.local")
        }
        assertEquals(esperado, campos)
        assertEquals(esperado.keys.toList(), campos.keys.toList())
    }

    @Test
    fun `grupo atual e o 32`() {
        assertEquals("32", SankhyaUsuarioService.montarCampos(1, "A B", "A B", null)["CODGRUPO"])
    }

    @Test
    fun `dominio atual e negri local - primeiro e ultimo nome, sem acento`() {
        assertEquals("joao.cavalcante@negri.local", SankhyaUsuarioService.emailSankhya("João  Pedro Cavalcante"))
        assertEquals("maria.maria@negri.local", SankhyaUsuarioService.emailSankhya("Maria"))
    }

    @Test
    fun `e-mail informado substitui o gerado`() {
        assertEquals("x@y.com", SankhyaUsuarioService.montarCampos(1, "A B", "A B", "x@y.com")["ACCOUNTEMAIL"])
    }
}
