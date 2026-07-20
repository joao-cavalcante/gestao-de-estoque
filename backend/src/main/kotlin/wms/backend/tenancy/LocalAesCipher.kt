package wms.backend.tenancy

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM com chave estática (env var). Só pra desenvolvimento local —
 * ver VaultTransitCipher pra produção. Diferente de um KMS de verdade, a
 * chave inteira mora na própria aplicação: qualquer processo com a env var
 * consegue decifrar tudo, sem auditoria, sem revogação, sem rotação
 * incremental. É exatamente o problema que a fundação precisa resolver
 * antes de "centenas a milhares de tenants" em produção real.
 */
object LocalAesCipher : SecretsCipher {
    private const val ALGORITHM = "AES/GCM/NoPadding"
    private const val GCM_TAG_LENGTH_BITS = 128
    private const val IV_LENGTH_BYTES = 12

    private val secureRandom = SecureRandom()

    private val key: SecretKeySpec by lazy {
        val encoded = System.getenv("WMS_CREDENTIALS_KEY")
        val keyBytes = if (encoded.isNullOrBlank()) {
            System.err.println(
                "AVISO: WMS_CREDENTIALS_KEY não definida — usando chave de " +
                    "desenvolvimento fixa. NUNCA use isso em produção: qualquer " +
                    "instância com essa mesma chave padrão consegue decifrar as " +
                    "credenciais de TODOS os tenants."
            )
            "dev-only-key-troque-em-producao!".toByteArray().copyOf(32)
        } else {
            Base64.getDecoder().decode(encoded)
        }
        require(keyBytes.size == 32) { "WMS_CREDENTIALS_KEY precisa decodificar pra exatamente 32 bytes (AES-256)" }
        SecretKeySpec(keyBytes, "AES")
    }

    /** Retorna base64(iv || ciphertext+tag) — um blob único, pronto pra salvar em coluna text. */
    override fun encrypt(plaintext: String): String {
        val iv = ByteArray(IV_LENGTH_BYTES).also { secureRandom.nextBytes(it) }
        val cipher = Cipher.getInstance(ALGORITHM)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(iv + ciphertext)
    }

    /** Inverso de [encrypt]. Lança exceção se o blob estiver corrompido ou a chave não bater. */
    override fun decrypt(ciphertext: String): String {
        val bytes = Base64.getDecoder().decode(ciphertext)
        val iv = bytes.copyOfRange(0, IV_LENGTH_BYTES)
        val encrypted = bytes.copyOfRange(IV_LENGTH_BYTES, bytes.size)
        val cipher = Cipher.getInstance(ALGORITHM)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
        return String(cipher.doFinal(encrypted), Charsets.UTF_8)
    }
}
