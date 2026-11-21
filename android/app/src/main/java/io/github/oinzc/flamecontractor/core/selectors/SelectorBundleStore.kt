package io.github.oinzc.flamecontractor.core.selectors

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

class SelectorBundleStore(
    baseRawJson: String,
    private val overrideFile: File,
) {
    constructor(context: Context) : this(
        baseRawJson = readAssetBundle(context),
        overrideFile = File(context.filesDir, OVERRIDE_FILE_NAME),
    )

    private val lock = Any()
    private val normalizedBaseJson: String = baseRawJson.replace("\r\n", "\n")
    private val baseBundle: SelectorBundle = try {
        SelectorBundle.fromJson(normalizedBaseJson)
    } catch (_: Throwable) {
        SelectorBundle.defaults()
    }
    private val baseValues: Map<String, Any> = baseBundle.toMap()

    private var cachedOverrides: Map<String, Any> = emptyMap()
    private var cachedCurrent: SelectorBundle = baseBundle
    private var lastOverrideExists: Boolean = false
    private var lastOverrideMtime: Long = -1L
    private var lastOverrideSize: Long = -1L

    init {
        synchronized(lock) {
            reloadLocked(force = true)
        }
    }

    fun current(): SelectorBundle = synchronized(lock) {
        reloadLocked(force = false)
        cachedCurrent
    }

    fun base(): SelectorBundle = baseBundle

    fun overrides(): Map<String, Any> = synchronized(lock) {
        reloadLocked(force = false)
        val copy = LinkedHashMap<String, Any>(cachedOverrides.size)
        for ((k, v) in cachedOverrides) {
            copy[k] = SelectorBundle.copyValue(v)
        }
        copy
    }

    fun hasOverrides(): Boolean = synchronized(lock) {
        reloadLocked(force = false)
        cachedOverrides.isNotEmpty()
    }

    fun editableKeys(): List<String> = SelectorBundle.EDITABLE_KEYS

    fun digest(): String = synchronized(lock) {
        reloadLocked(force = false)
        val payloadBytes = if (cachedOverrides.isEmpty()) {
            normalizedBaseJson.toByteArray(Charsets.UTF_8)
        } else {
            SelectorBundle.toCanonicalSortedJson(cachedCurrent).toByteArray(Charsets.UTF_8)
        }
        sha256Hex12(payloadBytes)
    }

    fun saveOverrides(rawJson: String): SelectorBundle = synchronized(lock) {
        val validated = SelectorBundleValidator.validateOverrides(baseBundle, rawJson)
        val deltas = LinkedHashMap<String, Any>()
        for ((key, value) in validated) {
            if (value != baseValues[key]) {
                deltas[key] = SelectorBundle.copyValue(value)
            }
        }

        if (deltas.isEmpty()) {
            return clearOverridesLocked()
        }

        val serialized = SelectorBundle.toPrettyJson(deltas).toByteArray(Charsets.UTF_8)
        atomicWrite(overrideFile, serialized)
        reloadLocked(force = true)
        cachedCurrent
    }

    fun clearOverrides(): SelectorBundle = synchronized(lock) {
        clearOverridesLocked()
    }

    private fun clearOverridesLocked(): SelectorBundle {
        if (overrideFile.exists()) {
            overrideFile.delete()
        }
        reloadLocked(force = true)
        return cachedCurrent
    }

    private fun reloadLocked(force: Boolean) {
        val exists = overrideFile.isFile
        val mtime = if (exists) overrideFile.lastModified() else -1L
        val size = if (exists) overrideFile.length() else -1L
        if (
            !force &&
            exists == lastOverrideExists &&
            mtime == lastOverrideMtime &&
            size == lastOverrideSize
        ) {
            return
        }

        val parsedOverrides = LinkedHashMap<String, Any>()
        if (exists) {
            val rawDisk = try {
                overrideFile.readText(Charsets.UTF_8)
            } catch (_: Throwable) {
                null
            }
            if (!rawDisk.isNullOrBlank()) {
                val parsed = try {
                    SelectorJsonCodec.parse(rawDisk)
                } catch (_: Throwable) {
                    null
                }
                if (parsed is Map<*, *>) {
                    for ((rawKey, rawValue) in parsed) {
                        val key = rawKey as? String ?: continue
                        val cleaned = try {
                            SelectorBundleValidator.validateEntry(baseValues, key, rawValue)
                        } catch (_: IllegalArgumentException) {
                            // 磁盘手工篡改混入非法项或 CHAT_URL 时安全忽略
                            continue
                        }
                        if (cleaned != baseValues[key]) {
                            parsedOverrides[key] = SelectorBundle.copyValue(cleaned)
                        }
                    }
                }
            }
        }

        cachedOverrides = parsedOverrides
        cachedCurrent = baseBundle.merge(parsedOverrides)
        lastOverrideExists = exists
        lastOverrideMtime = mtime
        lastOverrideSize = size
    }

    companion object {
        const val BUNDLE_ASSET_NAME = "selectors_bundle.json"
        const val OVERRIDE_FILE_NAME = "selectors_override.json"

        private fun readAssetBundle(context: Context): String = try {
            context.assets.open(BUNDLE_ASSET_NAME).bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (_: Throwable) {
            SelectorBundle.toPrettyJson(SelectorBundle.defaults().toMap())
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

        private fun sha256Hex12(bytes: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            val sb = StringBuilder(12)
            for (i in 0 until 6) {
                val b = digest[i].toInt() and 0xFF
                sb.append(HEX_CHARS[b ushr 4])
                sb.append(HEX_CHARS[b and 0x0F])
            }
            return sb.toString()
        }

        private val HEX_CHARS = "0123456789abcdef".toCharArray()
    }
}
