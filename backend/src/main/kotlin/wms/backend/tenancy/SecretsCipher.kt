package wms.backend.tenancy

/** Contrato comum entre a cifra local (dev) e a cifra via KMS (produção). */
interface SecretsCipher {
    fun encrypt(plaintext: String): String
    fun decrypt(ciphertext: String): String
}
