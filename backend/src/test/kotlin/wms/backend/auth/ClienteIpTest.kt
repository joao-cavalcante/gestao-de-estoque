package wms.backend.auth

import wms.backend.master.RedeMaster
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Entrega 1.2 — IP do cliente atrás do nginx e restrição de rede do Painel Master. */
class ClienteIpTest {
    private val docker = FaixaIp.lerLista("172.16.0.0/12")!!

    @Test
    fun `faixas CIDR IPv4 e IPv6`() {
        val f = FaixaIp.ler("10.8.0.0/24")!!
        assertTrue(f.contem("10.8.0.1"))
        assertTrue(f.contem("10.8.0.255"))
        assertFalse(f.contem("10.8.1.1"))
        assertTrue(FaixaIp.ler("172.16.0.0/12")!!.contem("172.31.255.1"))
        assertFalse(FaixaIp.ler("172.16.0.0/12")!!.contem("172.32.0.1"))
        assertTrue(FaixaIp.ler("203.0.113.7")!!.contem("203.0.113.7"))
        assertFalse(FaixaIp.ler("203.0.113.7")!!.contem("203.0.113.8"))
        assertTrue(FaixaIp.ler("::1/128")!!.contem("::1"))
        assertFalse(FaixaIp.ler("10.0.0.0/8")!!.contem("::1"))
    }

    @Test
    fun `entrada invalida nunca vira libera tudo`() {
        assertNull(FaixaIp.ler("exemplo.com"))
        assertNull(FaixaIp.ler("10.0.0.0/33"))
        assertNull(FaixaIp.lerLista("10.0.0.0/8, lixo"))
        assertEquals(0, FaixaIp.lerLista("")!!.size)
        assertFalse(RedeMaster.permitido("10.0.0.1", null), "lista inválida = ninguém")
    }

    @Test
    fun `X-Real-IP so vale vindo do proxy confiavel`() {
        assertEquals("200.1.2.3", ClienteIp.resolver("172.18.0.5", "200.1.2.3", docker))
        // chegou direto na porta da API (par TCP público) dizendo ser da VPN: ignorado
        assertEquals("200.9.9.9", ClienteIp.resolver("200.9.9.9", "10.8.0.2", docker))
        // sem proxy configurado: sempre o par TCP
        assertEquals("172.18.0.5", ClienteIp.resolver("172.18.0.5", "10.8.0.2", emptyList()))
        // cabeçalho que não é IP: ignorado
        assertEquals("172.18.0.5", ClienteIp.resolver("172.18.0.5", "10.8.0.2, 1.1.1.1", docker))
    }

    @Test
    fun `restricao de rede do Painel Master`() {
        val vpn = FaixaIp.lerLista("10.8.0.0/24")
        assertTrue(RedeMaster.permitido("10.8.0.9", vpn))
        assertFalse(RedeMaster.permitido("200.1.2.3", vpn))
        assertTrue(RedeMaster.permitido("200.1.2.3", emptyList()), "sem restrição configurada")
        // forjar X-Real-IP da VPN chegando direto não passa: o IP resolvido é o par TCP
        assertFalse(RedeMaster.permitido(ClienteIp.resolver("200.9.9.9", "10.8.0.2", docker), vpn))
    }
}
