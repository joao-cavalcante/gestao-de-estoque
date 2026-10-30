package wms.backend.auth

import io.ktor.server.application.*
import java.net.InetAddress

/** Faixa de rede em notação CIDR (IPv4 ou IPv6, ex.: 10.8.0.0/24, 172.16.0.0/12, ::1/128). IP sem prefixo = só ele. */
class FaixaIp private constructor(private val rede: ByteArray, private val prefixo: Int) {

    fun contem(ip: String): Boolean {
        val bytes = literalIp(ip)?.address ?: return false
        if (bytes.size != rede.size) return false
        val inteiros = prefixo / 8
        for (i in 0 until inteiros) if (bytes[i] != rede[i]) return false
        val resto = prefixo % 8
        if (resto == 0) return true
        val mascara = (0xFF shl (8 - resto)) and 0xFF
        return (bytes[inteiros].toInt() and mascara) == (rede[inteiros].toInt() and mascara)
    }

    companion object {
        fun ler(texto: String): FaixaIp? {
            val partes = texto.trim().split("/")
            val endereco = literalIp(partes[0])?.address ?: return null
            val prefixo = if (partes.size == 2) partes[1].toIntOrNull() ?: return null else endereco.size * 8
            if (partes.size > 2 || prefixo !in 0..endereco.size * 8) return null
            return FaixaIp(endereco, prefixo)
        }

        /** Lista separada por vírgula; entrada inválida derruba a lista inteira (configuração errada não pode virar "libera tudo"). */
        fun lerLista(texto: String?): List<FaixaIp>? {
            if (texto.isNullOrBlank()) return emptyList()
            val faixas = texto.split(",").filter { it.isNotBlank() }.map { ler(it) ?: return null }
            return faixas
        }

        /** Só literal numérico: nunca resolve nome no DNS (InetAddress.getByName resolveria "exemplo.com"). */
        private fun literalIp(texto: String): InetAddress? {
            val t = texto.trim().removePrefix("[").removeSuffix("]")
            val ehLiteral = t.isNotEmpty() && (t.contains(':') || t.all { it.isDigit() || it == '.' })
            if (!ehLiteral) return null
            return runCatching { InetAddress.getByName(t) }.getOrNull()
        }
    }
}

/**
 * IP de quem fez a requisição. Atrás do nginx do frontend o par TCP é o nginx; o IP real vem no X-Real-IP, que o
 * nginx SOBRESCREVE (proxy_set_header X-Real-IP $remote_addr). Só é aceito quando o par TCP está em
 * WMS_PROXIES_CONFIAVEIS — sem isso, qualquer um que chegasse direto na porta da API escolheria o próprio IP.
 * Vazio = nenhum proxy confiável (usa o par TCP).
 */
object ClienteIp {
    private val proxiesConfiaveis: List<FaixaIp> by lazy {
        FaixaIp.lerLista(System.getenv("WMS_PROXIES_CONFIAVEIS")) ?: run {
            System.err.println("ERRO: WMS_PROXIES_CONFIAVEIS inválido — nenhum proxy será considerado confiável")
            emptyList()
        }
    }

    fun resolver(parTcp: String, xRealIp: String?, confiaveis: List<FaixaIp>): String {
        val informado = xRealIp?.trim()?.takeIf { it.isNotEmpty() && FaixaIp.ler(it) != null }
        return if (informado != null && confiaveis.any { it.contem(parTcp) }) informado else parTcp
    }

    fun de(call: ApplicationCall): String =
        resolver(call.request.local.remoteAddress, call.request.headers["X-Real-IP"], proxiesConfiaveis)
}
