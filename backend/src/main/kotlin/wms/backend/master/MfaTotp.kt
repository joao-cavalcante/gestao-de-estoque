package wms.backend.master

import dev.samstevens.totp.code.DefaultCodeGenerator
import dev.samstevens.totp.code.HashingAlgorithm
import dev.samstevens.totp.qr.QrData
import dev.samstevens.totp.secret.DefaultSecretGenerator
import java.security.MessageDigest
import java.time.Instant

/**
 * Segundo fator do login Master: TOTP (RFC 6238 — Google Authenticator, Microsoft Authenticator, 1Password...).
 * Geração e cálculo do código ficam na biblioteca dev.samstevens.totp; aqui só a política: 6 dígitos, passo de 30 s,
 * aceita 1 passo de relógio pra cada lado e devolve QUAL passo casou — quem chama grava o passo e recusa repetição.
 */
object MfaTotp {
    const val PERIODO_SEGUNDOS = 30L
    const val DIGITOS = 6
    private const val TOLERANCIA_PASSOS = 1L
    const val EMISSOR = "WMS Plataforma"

    private val gerador = DefaultCodeGenerator(HashingAlgorithm.SHA1, DIGITOS)

    /** Segredo novo em Base32 (160 bits), o formato que os aplicativos autenticadores leem. */
    fun novoSegredo(): String = DefaultSecretGenerator(32).generate()

    /** otpauth://totp/... — colar no aplicativo ou gerar QR a partir dele. */
    fun uriCadastro(email: String, segredo: String): String =
        QrData.Builder()
            .label(email)
            .secret(segredo)
            .issuer(EMISSOR)
            .algorithm(HashingAlgorithm.SHA1)
            .digits(DIGITOS)
            .period(PERIODO_SEGUNDOS.toInt())
            .build()
            .uri

    fun passo(instante: Instant): Long = instante.epochSecond / PERIODO_SEGUNDOS

    fun codigo(segredo: String, passo: Long): String = gerador.generate(segredo, passo)

    /** Passo de 30 s em que o código é válido (dentro da tolerância), ou null. Comparação em tempo constante. */
    fun passoDoCodigo(segredo: String, codigoInformado: String?, agora: Instant): Long? {
        val informado = codigoInformado?.filterNot { it.isWhitespace() } ?: return null
        if (informado.length != DIGITOS || !informado.all { it.isDigit() }) return null
        val atual = passo(agora)
        return (atual - TOLERANCIA_PASSOS..atual + TOLERANCIA_PASSOS).firstOrNull {
            MessageDigest.isEqual(codigo(segredo, it).toByteArray(), informado.toByteArray())
        }
    }
}
