package io.github.oinzc.flamecontractor.core.notify

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class EncryptedSecretStore internal constructor(
    private val secretFile: File,
    private val keyProvider: () -> SecretKey,
) {
    constructor(context: Context) : this(
        secretFile = File(context.filesDir, SECRET_FILE_NAME),
        keyProvider = { getOrCreateAndroidKeyStoreKey(DEFAULT_KEY_ALIAS) },
    )

    private val lock = Any()

    fun saveBarkUrl(url: String?) {
        synchronized(lock) {
            val cleaned = url?.trim().orEmpty()
            if (cleaned.isEmpty()) {
                if (secretFile.exists()) {
                    secretFile.delete()
                }
                return
            }

            try {
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.ENCRYPT_MODE, keyProvider())
                val iv = cipher.iv
                if (iv == null || iv.isEmpty() || iv.size > MAX_IV_LENGTH) {
                    throw IllegalStateException("加密初始化向量无效")
                }
                val ciphertext = cipher.doFinal(cleaned.toByteArray(Charsets.UTF_8))
                val payload = ByteArray(2 + iv.size + ciphertext.size)
                payload[0] = FORMAT_VERSION
                payload[1] = iv.size.toByte()
                System.arraycopy(iv, 0, payload, 2, iv.size)
                System.arraycopy(ciphertext, 0, payload, 2 + iv.size, ciphertext.size)
                atomicWrite(secretFile, payload)
            } catch (_: Throwable) {
                // 严禁在异常或日志中携带任何明文 URL 或底层敏感消息
                throw IllegalStateException("保存加密 Bark 配置失败")
            }
        }
    }

    fun readBarkUrl(): String? = synchronized(lock) {
        if (!secretFile.isFile) return null
        try {
            val payload = secretFile.readBytes()
            if (payload.size <= 2 || payload[0] != FORMAT_VERSION) {
                return null
            }
            val ivLen = payload[1].toInt() and 0xFF
            if (ivLen !in 1..MAX_IV_LENGTH || payload.size <= 2 + ivLen) {
                return null
            }
            val iv = payload.copyOfRange(2, 2 + ivLen)
            val ciphertext = payload.copyOfRange(2 + ivLen, payload.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, keyProvider(), GCMParameterSpec(GCM_TAG_BITS, iv))
            val plaintext = cipher.doFinal(ciphertext)
            String(plaintext, Charsets.UTF_8).trim().ifEmpty { null }
        } catch (_: Throwable) {
            // 密钥失效或密文损坏时静默降级为未配置，绝不泄露明文或崩溃
            null
        }
    }

    fun readMaskedBarkUrl(): String? = BarkUrlNormalizer.mask(readBarkUrl())

    fun hasBarkUrl(): Boolean = !readBarkUrl().isNullOrBlank()

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val DEFAULT_KEY_ALIAS = "flame_contractor_bark_secret_v1"
        private const val SECRET_FILE_NAME = "bark_secret.enc"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        private const val KEY_SIZE_BITS = 256
        private const val FORMAT_VERSION: Byte = 0x01
        private const val MAX_IV_LENGTH = 32
        private val keystoreLock = Any()

        private fun getOrCreateAndroidKeyStoreKey(alias: String): SecretKey = synchronized(keystoreLock) {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            val existing = keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry
            if (existing != null) {
                return existing.secretKey
            }
            val keyGenerator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                ANDROID_KEYSTORE,
            )
            val spec = KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setKeySize(KEY_SIZE_BITS)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
            keyGenerator.init(spec)
            keyGenerator.generateKey()
        }

        private fun atomicWrite(target: File, bytes: ByteArray) {
            val parent = target.parentFile
            if (parent != null && !parent.exists()) {
                parent.mkdirs()
            }
            val tmpFile = File(parent ?: File("."), "${target.name}.tmp")
            try {
                FileOutputStream(tmpFile).use { fos ->
                    fos.write(bytes)
                    fos.flush()
                    try {
                        fos.fd.sync()
                    } catch (_: Throwable) {
                    }
                }
                tmpFile.setReadable(false, false)
                tmpFile.setReadable(true, true)
                tmpFile.setWritable(false, false)
                tmpFile.setWritable(true, true)
                try {
                    Files.move(
                        tmpFile.toPath(),
                        target.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE,
                    )
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(
                        tmpFile.toPath(),
                        target.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                }
            } catch (exc: Throwable) {
                tmpFile.delete()
                throw exc
            }
        }
    }
}
