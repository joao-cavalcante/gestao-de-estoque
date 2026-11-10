package wms.backend.erp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Mensagens reais dos logs de produção (out–nov/2026) → texto de operador com "O que fazer". */
class MensagemOperadorTest {

    private fun traduz(bruto: String) = MensagemOperador.erroSankhya(bruto)!!

    private fun assertMensagem(texto: String, vararg trechos: String) {
        assertTrue(MensagemOperador.SEPARADOR in texto, "sem 'O que fazer': $texto")
        assertFalse("ORA-" in texto, "sobrou código Oracle: $texto")
        assertFalse("falhou para tenant" in texto, "sobrou prefixo técnico: $texto")
        trechos.forEach { assertTrue(it in texto, "faltou '$it' em: $texto") }
    }

    @Test
    fun `venda a prazo bloqueada`() = assertMensagem(
        traduz("Sankhya SelecaoDocumentoSP.faturar falhou para tenant 'negri': ORA-20101: Venda a prazo bloqueada para o parceiro 7385 na nota de Nro Único:81564."),
        "BLOQUEADO para venda a prazo", "cód. 7385", "financeiro",
    )

    @Test
    fun `tipo de negociacao inativo`() = assertMensagem(
        traduz("Sankhya SelecaoDocumentoSP.faturar falhou para tenant 'negri': ORA-20101: Verifique se o TIPO DE NEGOCIAÇÂO 9 está ativo ou se sua data de alteração é menor ou igual a data de lançamento da nota de Nro Único:81455."),
        "tipo de negociação 9", "INATIVA",
    )

    @Test
    fun `nota gerada e nao confirmada por tipo de negociacao nao autorizado`() = assertMensagem(
        traduz("nota 80607 gerada mas não confirmada (Sankhya CACSP.confirmarNota falhou para tenant 'negri': Tipo de negociação não autorizado para este cliente! Ver Grupo autorização.) — confirme na Central de Notas"),
        "A nota 80607 foi gerada, mas NÃO foi confirmada", "NÃO É PERMITIDA",
    )

    @Test
    fun `nota parada em liberacao`() = assertMensagem(
        traduz("nota 82168 gerada mas não confirmada (liberação pendente no Sankhya) — libere e confirme na Central de Notas"),
        "nota 82168", "liberador", "Confirmar nota",
    )

    @Test
    fun `nota aberta por outro usuario`() = assertMensagem(
        traduz("nota 80894 gerada mas não confirmada (Sankhya CACSP.confirmarNota falhou para tenant 'negri': A nota 80894 já está sendo processada neste momento pelo usuário 27-RAQUEL.LEAL, no processo: ) — confirme na Central de Notas"),
        "27-RAQUEL.LEAL", "ABERTA", "1 minuto",
    )

    @Test
    fun `liberacao pendente e reprovada no faturar`() {
        assertMensagem(traduz("Sankhya SelecaoDocumentoSP.faturar falhou para tenant 'negri': Pedido/Nota 80499 com solicitação de liberação pendente:"), "LIBERAÇÃO PENDENTE")
        assertMensagem(traduz("Sankhya SelecaoDocumentoSP.faturar falhou para tenant 'negri': Pedido/Nota 80692, com solicitação de liberação reprovada:"), "REPROVADA")
    }

    @Test
    fun `telefone e tempo esgotado`() {
        assertMensagem(traduz("nota 76696 gerada mas não confirmada (Sankhya CACSP.confirmarNota falhou para tenant 'negri': O valor do campo fone (Telefone...) informado não é valido.) — confirme na Central de Notas"), "TELEFONE")
        assertMensagem(traduz("nota 77748 gerada mas não confirmada (request timed out) — confirme na Central de Notas"), "DEMOROU")
    }

    @Test
    fun `desconhecida mantem o motivo sem prefixo tecnico`() {
        val t = traduz("Sankhya SelecaoDocumentoSP.faturar falhou para tenant 'negri': ORA-20101: Regra nova qualquer da Negri.")
        assertMensagem(t, "Regra nova qualquer da Negri", "faturamento")
    }

    @Test
    fun `ja traduzida ou vazia`() {
        val uma = traduz("request timed out")
        assertEquals(uma, MensagemOperador.erroSankhya(uma))
        assertNull(MensagemOperador.erroSankhya(null))
        assertNull(MensagemOperador.erroSankhya("  "))
    }

    @Test
    fun `rejeicao da SEFAZ`() {
        assertMensagem(MensagemOperador.rejeicaoSefaz(302, "65961"), "NF 65961", "Inscrição Estadual", "Não carregue")
        assertMensagem(MensagemOperador.rejeicaoSefaz(610, "1"), "IMPOSTO ou de VALOR")
        assertMensagem(MensagemOperador.rejeicaoSefaz(9999, null), "A nota foi RECUSADA PELA SEFAZ (código 9999)")
        assertMensagem(MensagemOperador.rejeicaoSefaz(null, "0"), "A nota foi RECUSADA PELA SEFAZ")
    }
}
