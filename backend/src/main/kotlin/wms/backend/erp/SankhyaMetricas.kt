package wms.backend.erp

/**
 * Tempo de cada chamada ao Sankhya no log (`SANKHYA ms=812 ok=true chamada=ConferenciaSP.cortar`).
 * Sem isso não há histórico de gargalo: o log de acesso do Ktor só mostra a rota inteira, não
 * quais chamadas ao Sankhya compõem o tempo dela. Uma linha por tentativa (retry aparece 2x).
 */
object SankhyaMetricas {
    suspend fun <T> medir(chamada: String, bloco: suspend () -> T): T {
        val inicio = System.nanoTime()
        var ok = false
        try {
            val resultado = bloco()
            ok = true
            return resultado
        } finally {
            val ms = (System.nanoTime() - inicio) / 1_000_000
            println("SANKHYA ms=$ms ok=$ok chamada=$chamada")
        }
    }
}
