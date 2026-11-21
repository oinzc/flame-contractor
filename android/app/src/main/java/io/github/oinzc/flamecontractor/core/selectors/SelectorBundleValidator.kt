package io.github.oinzc.flamecontractor.core.selectors

object SelectorBundleValidator {

    const val MAX_SELECTOR_LENGTH = 512
    const val MAX_LIST_ITEMS = 20

    val NON_EDITABLE_KEYS: Set<String> = SelectorBundle.NON_EDITABLE_KEYS

    fun validateOverrides(base: SelectorBundle, rawJson: String): Map<String, Any> {
        if (rawJson.isBlank()) {
            throw IllegalArgumentException("覆写 JSON 不能为空")
        }
        val parsed = try {
            SelectorJsonCodec.parse(rawJson)
        } catch (exc: IllegalArgumentException) {
            throw IllegalArgumentException("覆写 JSON 格式无效：${exc.message}")
        }
        if (parsed !is Map<*, *>) {
            throw IllegalArgumentException("overrides 必须为 JSON 对象")
        }

        val baseValues = base.toMap()
        val validated = LinkedHashMap<String, Any>()
        for ((rawKey, rawValue) in parsed) {
            if (rawKey !is String) {
                throw IllegalArgumentException("选择器键名必须为字符串")
            }
            validated[rawKey] = validateEntry(baseValues, rawKey, rawValue)
        }
        return validated
    }

    internal fun validateEntry(
        baseValues: Map<String, Any>,
        key: String,
        rawValue: Any?,
    ): Any {
        if (key in NON_EDITABLE_KEYS) {
            throw IllegalArgumentException("不允许通过覆写修改 $key")
        }
        val expected = baseValues[key]
            ?: throw IllegalArgumentException("未知的选择器键名：$key")

        return when (expected) {
            is String -> cleanSingleSelector(key, rawValue)
            is List<*> -> {
                if (rawValue !is List<*>) {
                    throw IllegalArgumentException("选择器 $key 必须为非空字符串数组")
                }
                if (rawValue.isEmpty() || rawValue.size > MAX_LIST_ITEMS) {
                    throw IllegalArgumentException("选择器 $key 数组元素数量须在 1..$MAX_LIST_ITEMS 之间")
                }
                rawValue.map { item -> cleanSingleSelector(key, item) }
            }
            else -> throw IllegalArgumentException("不支持覆盖的选择器类型：$key")
        }
    }

    private fun cleanSingleSelector(key: String, raw: Any?): String {
        if (raw !is String) {
            throw IllegalArgumentException("选择器 $key 必须为字符串")
        }
        if (raw.any { it == '\u0000' || it == '\r' || it == '\n' }) {
            throw IllegalArgumentException("选择器 $key 不能包含换行或控制字符")
        }
        val cleaned = raw.trim()
        if (cleaned.isEmpty()) {
            throw IllegalArgumentException("选择器 $key 不能为空字符串")
        }
        if (cleaned.length > MAX_SELECTOR_LENGTH) {
            throw IllegalArgumentException("选择器 $key 长度不能超过 $MAX_SELECTOR_LENGTH 个字符")
        }
        return cleaned
    }
}
