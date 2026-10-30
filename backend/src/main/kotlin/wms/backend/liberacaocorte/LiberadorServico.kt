package wms.backend.liberacaocorte

import wms.backend.tenancy.TenantRepository
import java.util.UUID

/**
 * Credencial do liberador de serviço (liberações automáticas: pesável na tolerância, liberação herdada) — POR
 * TENANT (`erp_connections.credencial_liberador`, cifrada, V63). Nunca a credencial de integração do ERP.
 *
 * Variável de ambiente (LIBERADOR_USUARIO / LIBERADOR_SENHA) só como reserva da instalação legada, e só se o
 * tenant tiver `parametros.liberador.fallbackVariavelAmbiente = true` (Negri, V63) e NENHUMA credencial gravada.
 * Credencial gravada que não decifra bloqueia (não cai na variável). Sem credencial = nada automático.
 */
object LiberadorServico {

    class Credencial(val usuario: String, val senha: String, val origem: String) {
        override fun toString() = "Credencial(origem=$origem)"
    }

    /** null = indisponível; [motivo] diz por quê (vai pro log, sem segredo). */
    data class Resolucao(val credencial: Credencial?, val motivo: String?)

    /** Decisão pura — testável sem banco. [credencialTenant] lança se o blob gravado não decifrar. */
    internal fun resolver(
        credencialTenant: () -> Pair<String, String>?,
        fallbackPermitido: Boolean,
        variavel: (String) -> String?,
    ): Resolucao {
        val doTenant = try {
            credencialTenant()
        } catch (e: Exception) {
            return Resolucao(null, "credencial do liberador gravada no tenant não pôde ser lida (${e.javaClass.simpleName})")
        }
        if (doTenant != null) return Resolucao(Credencial(doTenant.first, doTenant.second, "tenant"), null)
        if (!fallbackPermitido) return Resolucao(null, "liberador de serviço não configurado no tenant")
        val usuario = variavel("LIBERADOR_USUARIO")
        val senha = variavel("LIBERADOR_SENHA")
        if (usuario.isNullOrBlank() || senha.isNullOrBlank()) {
            return Resolucao(null, "liberador não configurado no tenant e LIBERADOR_USUARIO/LIBERADOR_SENHA ausentes")
        }
        return Resolucao(Credencial(usuario, senha, "variavel_ambiente_legado"), null)
    }

    /** Credencial do liberador do tenant, ou null (com AVISO rastreável no log — [contexto] diz o que ficou manual). */
    fun credencial(tenantId: UUID, contexto: String): Credencial? {
        val r = resolver(
            credencialTenant = { TenantRepository.obterCredencialLiberador(tenantId) },
            fallbackPermitido = runCatching { TenantRepository.parametros(tenantId).liberador.fallbackVariavelAmbiente }.getOrDefault(false),
            variavel = System::getenv,
        )
        if (r.credencial == null) println("AVISO: liberação automática bloqueada (tenant $tenantId, $contexto): ${r.motivo} — fica manual.")
        else if (r.credencial.origem != "tenant") println("INFO: liberador de serviço pela variável de ambiente (transição legada, tenant $tenantId, $contexto)")
        return r.credencial
    }
}
