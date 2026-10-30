package wms.backend.master

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.mindrot.jbcrypt.BCrypt
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** Falhas recentes de login Master que contam pro limite (ver MasterRepository.contarFalhas). */
data class ContagemFalhas(val par: Int, val email: Int, val ip: Int)

/**
 * Limite de tentativas do login Master. Janela deslizante de 15 min, sempre temporária: a conta nunca fica
 * desativada por tentativa errada (ninguém tranca a plataforma por fora). Recuperação antes da janela vencer:
 * CriarMasterKt desbloquear <email>.
 */
object PoliticaLimiteLogin {
    val JANELA: Duration = Duration.ofMinutes(15)
    /** Mesmo e-mail pelo mesmo IP — quem errou a senha algumas vezes. */
    const val MAX_FALHAS_PAR = 5
    /** Mesmo e-mail por vários IPs — força bruta distribuída contra uma conta. */
    const val MAX_FALHAS_EMAIL = 20
    /** Mesmo IP contra vários e-mails — varredura de contas. */
    const val MAX_FALHAS_IP = 30

    fun bloqueado(c: ContagemFalhas) = c.par >= MAX_FALHAS_PAR || c.email >= MAX_FALHAS_EMAIL || c.ip >= MAX_FALHAS_IP
}

/** O que o login Master precisa de fora (banco, auditoria) — trocado por memória nos testes. */
interface FontesLoginMaster {
    fun buscar(email: String): MasterRepository.MasterLogin?
    fun contarFalhas(email: String, ip: String, desde: Instant): ContagemFalhas
    fun registrarTentativa(email: String, ip: String, sucesso: Boolean, bloqueada: Boolean)
    fun consumirPassoMfa(masterId: UUID, passo: Long): Boolean
    fun auditar(ator: AtorAdmin, detalhe: JsonObject, resultado: String)
}

object FontesLoginMasterPostgres : FontesLoginMaster {
    override fun buscar(email: String) = MasterRepository.buscarParaLogin(email)
    override fun contarFalhas(email: String, ip: String, desde: Instant) = MasterRepository.contarFalhas(email, ip, desde)
    override fun registrarTentativa(email: String, ip: String, sucesso: Boolean, bloqueada: Boolean) =
        MasterRepository.registrarTentativa(email, ip, sucesso, bloqueada)
    override fun consumirPassoMfa(masterId: UUID, passo: Long) = MasterRepository.consumirPassoMfa(masterId, passo)
    override fun auditar(ator: AtorAdmin, detalhe: JsonObject, resultado: String) =
        AuditoriaAdmin.registrar(ator, null, null, "login", detalhe, resultado)
}

sealed interface ResultadoLoginMaster {
    data class Autenticado(val master: MasterRepository.MasterLogin) : ResultadoLoginMaster
    /** Qualquer recusa (conta inexistente, inativa, senha, código, MFA não cadastrado) — a resposta é a MESMA. */
    data object Recusado : ResultadoLoginMaster
    data object LimiteExcedido : ResultadoLoginMaster
}

/**
 * Login Master: limite de tentativas → senha → segundo fator (TOTP). Quem chama não fica sabendo se a conta existe:
 * conta inexistente paga o mesmo custo de bcrypt e recebe a mesma recusa. O motivo real vai só pra auditoria.
 *
 * `mfaObrigatorio` (WMS_MASTER_MFA_OBRIGATORIO, padrão true): conta sem MFA cadastrado não entra. Desligar só junto
 * com a restrição de rede (WMS_MASTER_REDES_PERMITIDAS) — ver deploy/RUNBOOK-master.md.
 */
class LoginMaster(
    private val fontes: FontesLoginMaster,
    private val mfaObrigatorio: Boolean,
    private val agora: () -> Instant = Instant::now,
) {
    fun autenticar(emailInformado: String, senha: String, codigo: String?, ip: String): ResultadoLoginMaster {
        val email = MasterRepository.normalizarEmail(emailInformado)
        val instante = agora()

        if (PoliticaLimiteLogin.bloqueado(fontes.contarFalhas(email, ip, instante.minus(PoliticaLimiteLogin.JANELA)))) {
            fontes.registrarTentativa(email, ip, sucesso = false, bloqueada = true)
            fontes.auditar(AtorAdmin("master", null, email, ip), motivo("limite_tentativas"), "negado")
            return ResultadoLoginMaster.LimiteExcedido
        }

        val conta = fontes.buscar(email)
        val senhaConfere = if (conta != null) MasterRepository.validarSenha(senha, conta.senhaHash) else {
            MasterRepository.validarSenha(senha, HASH_FICTICIO) // mesmo custo de conta existente (sem atalho de tempo)
            false
        }
        val recusa: String? = when {
            conta == null -> "credenciais"
            !senhaConfere -> "credenciais"
            !conta.ativo -> "conta_inativa"
            conta.mfaSegredo == null -> if (mfaObrigatorio) "mfa_nao_cadastrado" else null
            else -> {
                val passo = MfaTotp.passoDoCodigo(conta.mfaSegredo, codigo, instante)
                when {
                    passo == null -> "mfa_invalido"
                    !fontes.consumirPassoMfa(conta.id, passo) -> "mfa_reutilizado"
                    else -> null
                }
            }
        }

        val ator = AtorAdmin("master", conta?.id, email, ip)
        if (recusa != null) {
            fontes.registrarTentativa(email, ip, sucesso = false, bloqueada = false)
            fontes.auditar(ator, motivo(recusa), "negado")
            return ResultadoLoginMaster.Recusado
        }
        fontes.registrarTentativa(email, ip, sucesso = true, bloqueada = false)
        fontes.auditar(ator, buildJsonObject { put("segundoFator", conta!!.mfaSegredo != null) }, "ok")
        return ResultadoLoginMaster.Autenticado(conta!!)
    }

    private fun motivo(m: String) = buildJsonObject { put("motivo", m) }

    companion object {
        private val HASH_FICTICIO: String by lazy { BCrypt.hashpw(UUID.randomUUID().toString(), BCrypt.gensalt()) }

        fun mfaObrigatorioDoAmbiente(): Boolean = System.getenv("WMS_MASTER_MFA_OBRIGATORIO")?.trim()?.lowercase() != "false"

        fun padrao() = LoginMaster(FontesLoginMasterPostgres, mfaObrigatorioDoAmbiente())
    }
}
