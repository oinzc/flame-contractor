package io.github.oinzc.flamecontractor.ui.templates

import android.graphics.BitmapFactory
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.oinzc.flamecontractor.core.storage.TemplateImageStorage
import io.github.oinzc.flamecontractor.data.db.ContractTargetEntity
import io.github.oinzc.flamecontractor.data.db.DEFAULT_ACCOUNT_ID
import io.github.oinzc.flamecontractor.data.db.FlameDatabase
import io.github.oinzc.flamecontractor.data.db.MessageTemplateEntity
import io.github.oinzc.flamecontractor.data.db.TemplatePoolItemEntity
import io.github.oinzc.flamecontractor.data.db.TemplateType
import io.github.oinzc.flamecontractor.domain.TemplatePoolPicker
import java.io.File
import java.io.InputStream
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 模板与文案池仓库接口（纯 Kotlin，故意不暴露 `markPoolItemUsed`，
 * 从结构上杜绝列表预览或编辑误写 `lastUsedAt`）。
 */
interface TemplatesRepository {
    fun observeTemplates(accountId: String = DEFAULT_ACCOUNT_ID): Flow<List<MessageTemplateEntity>>
    fun observePoolItems(templateId: String): Flow<List<TemplatePoolItemEntity>>
    fun observeActiveTargets(accountId: String = DEFAULT_ACCOUNT_ID): Flow<List<ContractTargetEntity>>

    suspend fun listTemplates(accountId: String = DEFAULT_ACCOUNT_ID): List<MessageTemplateEntity>
    suspend fun getTemplateById(id: String): MessageTemplateEntity?
    suspend fun listPoolItems(templateId: String): List<TemplatePoolItemEntity>
    suspend fun countActiveTargetsByTemplate(templateId: String): Int
    suspend fun listAllReferencedImagePaths(): List<String> = emptyList()

    suspend fun upsertTemplate(template: MessageTemplateEntity)
    suspend fun updateTemplate(template: MessageTemplateEntity)
    suspend fun deleteTemplateById(id: String)

    suspend fun upsertPoolItem(item: TemplatePoolItemEntity)
    suspend fun updatePoolItem(item: TemplatePoolItemEntity)
    suspend fun deletePoolItemById(id: String)
}

class RoomTemplatesRepository(
    database: FlameDatabase,
) : TemplatesRepository {

    private val templateDao = database.templateDao()
    private val targetDao = database.targetDao()

    override fun observeTemplates(accountId: String): Flow<List<MessageTemplateEntity>> =
        templateDao.observeTemplates(accountId)

    override fun observePoolItems(templateId: String): Flow<List<TemplatePoolItemEntity>> =
        templateDao.observePoolItems(templateId)

    override fun observeActiveTargets(accountId: String): Flow<List<ContractTargetEntity>> =
        targetDao.observeActiveTargets(accountId)

    override suspend fun listTemplates(accountId: String): List<MessageTemplateEntity> =
        templateDao.listTemplates(accountId)

    override suspend fun getTemplateById(id: String): MessageTemplateEntity? =
        templateDao.getTemplateById(id)

    override suspend fun listPoolItems(templateId: String): List<TemplatePoolItemEntity> =
        templateDao.listPoolItems(templateId)

    override suspend fun countActiveTargetsByTemplate(templateId: String): Int =
        targetDao.countActiveByTemplate(templateId)

    override suspend fun listAllReferencedImagePaths(): List<String> =
        templateDao.listAllReferencedImagePaths()

    override suspend fun upsertTemplate(template: MessageTemplateEntity) {
        templateDao.upsertTemplate(template)
    }

    override suspend fun updateTemplate(template: MessageTemplateEntity) {
        templateDao.updateTemplate(template)
    }

    override suspend fun deleteTemplateById(id: String) {
        templateDao.deleteTemplateById(id)
    }

    override suspend fun upsertPoolItem(item: TemplatePoolItemEntity) {
        templateDao.upsertPoolItem(item)
    }

    override suspend fun updatePoolItem(item: TemplatePoolItemEntity) {
        templateDao.updatePoolItem(item)
    }

    override suspend fun deletePoolItemById(id: String) {
        templateDao.deletePoolItemById(id)
    }
}

data class PoolItemUi(
    val item: TemplatePoolItemEntity,
    val weekdaySummary: String,
    val lastUsedSummary: String,
)

data class InlinePoolInputState(
    val content: String = "",
    val weekdayMask: Int = TemplatePoolPicker.WEEKDAY_MASK_ALL,
    val errorMessage: String? = null,
)

data class TemplateCardUi(
    val template: MessageTemplateEntity,
    val poolItems: List<PoolItemUi>,
    val activeReferenceCount: Int,
    val nextPoolPreviewText: String?,
    val expanded: Boolean,
    val inlineInput: InlinePoolInputState = InlinePoolInputState(),
    val imageFile: File? = null,
    val imageAvailable: Boolean = false,
    val imageSummary: String? = null,
)

data class PoolDraftItem(
    val id: String? = null,
    val content: String = "",
    val weekdayMask: Int = TemplatePoolPicker.WEEKDAY_MASK_ALL,
    val enabled: Boolean = true,
    val lastUsedAt: Long? = null,
)

data class TemplateEditorState(
    val isNew: Boolean,
    val editingTemplateId: String?,
    val name: String,
    val type: TemplateType,
    val content: String,
    val stickerName: String,
    val stickerLabel: String,
    val stickerCategory: String,
    val stickerFallbackIndexInput: String,
    val enabled: Boolean,
    val poolDrafts: List<PoolDraftItem>,
    val imagePath: String? = null,
    val imageAvailable: Boolean = false,
    val imageMimeType: String? = null,
    val imageSizeBytes: Long? = null,
    val imageWidthPx: Int? = null,
    val imageHeightPx: Int? = null,
    val isImportingImage: Boolean = false,
    val errorMessage: String? = null,
)

data class PoolItemEditDialogState(
    val templateId: String,
    val itemId: String,
    val content: String,
    val weekdayMask: Int,
    val enabled: Boolean,
    val lastUsedAt: Long?,
    val errorMessage: String? = null,
)

data class DeleteTemplateConfirmState(
    val template: MessageTemplateEntity,
    val activeReferenceCount: Int,
)

data class TemplatesUiState(
    val textTemplates: List<TemplateCardUi> = emptyList(),
    val poolTemplates: List<TemplateCardUi> = emptyList(),
    val stickerTemplates: List<TemplateCardUi> = emptyList(),
    val imageTemplates: List<TemplateCardUi> = emptyList(),
    val sampleTargets: List<ContractTargetEntity> = emptyList(),
    val selectedPreviewTargetId: String? = null,
    val editorState: TemplateEditorState? = null,
    val poolItemEditDialog: PoolItemEditDialogState? = null,
    val deleteConfirmState: DeleteTemplateConfirmState? = null,
    val feedbackMessage: String? = null,
    val isBusy: Boolean = false,
) {
    val totalCount: Int
        get() = textTemplates.size + poolTemplates.size + stickerTemplates.size + imageTemplates.size
}

private data class TemplatesLocalControls(
    val expandedOverrides: Map<String, Boolean> = emptyMap(),
    val inlineInputs: Map<String, InlinePoolInputState> = emptyMap(),
    val selectedPreviewTargetId: String? = null,
    val editorState: TemplateEditorState? = null,
    val stagedImagePaths: Set<String> = emptySet(),
    val poolItemEditDialog: PoolItemEditDialogState? = null,
    val deleteConfirmState: DeleteTemplateConfirmState? = null,
    val feedbackMessage: String? = null,
    val isBusy: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class)
class TemplatesViewModel(
    private val repository: TemplatesRepository,
    private val accountId: String = DEFAULT_ACCOUNT_ID,
    private val imageStore: TemplateImageStorage? = null,
    private val nowInstant: () -> Instant = { Instant.now() },
    private val zoneIdProvider: () -> ZoneId = { ZoneId.systemDefault() },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val externalScope: CoroutineScope? = null,
) : ViewModel() {

    private val scope: CoroutineScope
        get() = externalScope ?: viewModelScope

    private val localControls = MutableStateFlow(TemplatesLocalControls())
    private val refreshTick = MutableStateFlow(0L)

    private val _uiState = MutableStateFlow(TemplatesUiState())
    val uiState: StateFlow<TemplatesUiState> = _uiState.asStateFlow()

    init {
        val templatesFlow = repository.observeTemplates(accountId)
        val targetsFlow = repository.observeActiveTargets(accountId)
        val poolMapFlow = templatesFlow.flatMapLatest { templates ->
            val pools = templates.filter { it.type == TemplateType.POOL }
            if (pools.isEmpty()) {
                flowOf(emptyMap())
            } else {
                combine(
                    pools.map { tpl ->
                        repository.observePoolItems(tpl.id).map { items -> tpl.id to items }
                    },
                ) { pairs -> pairs.toMap() }
            }
        }

        scope.launch {
            combine(
                templatesFlow,
                poolMapFlow,
                targetsFlow,
                localControls,
                refreshTick,
            ) { templates, poolMap, targets, controls, _ ->
                withContext(ioDispatcher) {
                    buildUiState(templates, poolMap, targets, controls)
                }
            }.collect { state ->
                _uiState.value = state
            }
        }
    }

    fun refreshImageStates() {
        refreshTick.value += 1L
    }

    private fun buildUiState(
        templates: List<MessageTemplateEntity>,
        poolMap: Map<String, List<TemplatePoolItemEntity>>,
        targets: List<ContractTargetEntity>,
        controls: TemplatesLocalControls,
    ): TemplatesUiState {
        val zoneId = zoneIdProvider()
        val nowZdt = ZonedDateTime.ofInstant(nowInstant(), zoneId)
        val weekdayIndex = TemplatePoolPicker.weekdayIndexOf(nowZdt.dayOfWeek)
        val refCountByTemplate = targets.mapNotNull { it.templateId }.groupingBy { it }.eachCount()
        val previewTarget = targets.firstOrNull { it.id == controls.selectedPreviewTargetId }
            ?: targets.firstOrNull()

        val cards = templates.map { template ->
            val rawPoolItems = poolMap[template.id].orEmpty()
            val poolUis = rawPoolItems.map { item ->
                PoolItemUi(
                    item = item,
                    weekdaySummary = formatWeekdayMask(item.weekdayMask),
                    lastUsedSummary = formatLastUsedAt(item.lastUsedAt, zoneId),
                )
            }
            val nextPick = if (template.type == TemplateType.POOL) {
                // 纯只读预览今日轮换命中句，绝不调用 markPoolItemUsed
                TemplatePoolPicker.pick(rawPoolItems, weekdayIndex)?.let { picked ->
                    renderTemplatePreview(picked.content, previewTarget, zoneId, nowZdt)
                }
            } else {
                null
            }
            val resolvedFile = if (template.type == TemplateType.IMAGE) {
                imageStore?.resolveExistingFile(template.imagePath)
            } else {
                null
            }
            val imageAvailable = if (template.type == TemplateType.IMAGE) {
                if (imageStore != null) {
                    resolvedFile != null
                } else {
                    !template.imagePath.isNullOrBlank()
                }
            } else {
                false
            }
            val imageSummary = if (template.type == TemplateType.IMAGE && resolvedFile != null) {
                formatImageSummary(
                    mimeType = mimeFromExtension(resolvedFile.extension),
                    sizeBytes = resolvedFile.length(),
                    widthPx = null,
                    heightPx = null,
                )
            } else {
                null
            }
            val expanded = controls.expandedOverrides[template.id] ?: (template.type == TemplateType.POOL)
            val inlineInput = controls.inlineInputs[template.id] ?: InlinePoolInputState()
            TemplateCardUi(
                template = template,
                poolItems = poolUis,
                activeReferenceCount = refCountByTemplate[template.id] ?: 0,
                nextPoolPreviewText = nextPick,
                expanded = expanded,
                inlineInput = inlineInput,
                imageFile = resolvedFile,
                imageAvailable = imageAvailable,
                imageSummary = imageSummary,
            )
        }

        return TemplatesUiState(
            textTemplates = cards.filter { it.template.type == TemplateType.TEXT },
            poolTemplates = cards.filter { it.template.type == TemplateType.POOL },
            stickerTemplates = cards.filter { it.template.type == TemplateType.STICKER },
            imageTemplates = cards.filter { it.template.type == TemplateType.IMAGE },
            sampleTargets = targets,
            selectedPreviewTargetId = previewTarget?.id,
            editorState = controls.editorState,
            poolItemEditDialog = controls.poolItemEditDialog,
            deleteConfirmState = controls.deleteConfirmState,
            feedbackMessage = controls.feedbackMessage,
            isBusy = controls.isBusy,
        )
    }

    fun selectPreviewTarget(targetId: String?) {
        localControls.update { it.copy(selectedPreviewTargetId = targetId) }
    }

    fun togglePoolExpanded(templateId: String) {
        localControls.update { controls ->
            val current = controls.expandedOverrides[templateId] ?: true
            controls.copy(expandedOverrides = controls.expandedOverrides + (templateId to !current))
        }
    }

    fun openCreateTemplate(type: TemplateType = TemplateType.TEXT) {
        val previousStaged = localControls.value.stagedImagePaths
        localControls.update {
            it.copy(
                stagedImagePaths = emptySet(),
                editorState = TemplateEditorState(
                    isNew = true,
                    editingTemplateId = null,
                    name = "",
                    type = type,
                    content = if (type == TemplateType.TEXT) "每日续火打卡 🔥" else "",
                    stickerName = if (type == TemplateType.STICKER) DEFAULT_STICKER_NAME else "",
                    stickerLabel = if (type == TemplateType.STICKER) DEFAULT_STICKER_NAME else "",
                    stickerCategory = "",
                    stickerFallbackIndexInput = "",
                    enabled = true,
                    poolDrafts = listOf(PoolDraftItem()),
                    imagePath = null,
                    imageAvailable = false,
                ),
            )
        }
        if (previousStaged.isNotEmpty()) {
            scope.launch {
                withContext(ioDispatcher) {
                    cleanupOrphanPaths(previousStaged)
                }
            }
        }
    }

    fun openEditTemplate(template: MessageTemplateEntity) {
        scope.launch {
            val previousStaged = localControls.value.stagedImagePaths
            val poolDrafts = if (template.type == TemplateType.POOL) {
                val items = withContext(ioDispatcher) { repository.listPoolItems(template.id) }
                if (items.isEmpty()) {
                    listOf(PoolDraftItem())
                } else {
                    items.map { item ->
                        PoolDraftItem(
                            id = item.id,
                            content = item.content,
                            weekdayMask = normalizeWeekdayMask(item.weekdayMask),
                            enabled = item.enabled,
                            lastUsedAt = item.lastUsedAt,
                        )
                    }
                }
            } else {
                listOf(PoolDraftItem())
            }

            val imageMeta = if (template.type == TemplateType.IMAGE) {
                withContext(ioDispatcher) {
                    val existingFile = imageStore?.resolveExistingFile(template.imagePath)
                    val available = if (imageStore != null) {
                        existingFile != null
                    } else {
                        !template.imagePath.isNullOrBlank()
                    }
                    var widthPx: Int? = null
                    var heightPx: Int? = null
                    if (existingFile != null) {
                        try {
                            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                            BitmapFactory.decodeFile(existingFile.absolutePath, bounds)
                            if (bounds.outWidth > 0 && bounds.outHeight > 0) {
                                widthPx = bounds.outWidth
                                heightPx = bounds.outHeight
                            }
                        } catch (_: Throwable) {
                        }
                    }
                    ImageMeta(
                        available = available,
                        mimeType = existingFile?.let { mimeFromExtension(it.extension) },
                        sizeBytes = existingFile?.length(),
                        widthPx = widthPx,
                        heightPx = heightPx,
                    )
                }
            } else {
                ImageMeta()
            }

            localControls.update {
                it.copy(
                    stagedImagePaths = emptySet(),
                    editorState = TemplateEditorState(
                        isNew = false,
                        editingTemplateId = template.id,
                        name = template.name,
                        type = template.type,
                        content = template.content,
                        stickerName = template.stickerName.orEmpty(),
                        stickerLabel = template.stickerLabel.orEmpty(),
                        stickerCategory = template.stickerCategory.orEmpty(),
                        stickerFallbackIndexInput = template.stickerFallbackIndex?.toString().orEmpty(),
                        enabled = template.enabled,
                        poolDrafts = poolDrafts,
                        imagePath = template.imagePath,
                        imageAvailable = imageMeta.available,
                        imageMimeType = imageMeta.mimeType,
                        imageSizeBytes = imageMeta.sizeBytes,
                        imageWidthPx = imageMeta.widthPx,
                        imageHeightPx = imageMeta.heightPx,
                    ),
                )
            }
            if (previousStaged.isNotEmpty()) {
                withContext(ioDispatcher) {
                    cleanupOrphanPaths(previousStaged)
                }
            }
        }
    }

    private data class ImageMeta(
        val available: Boolean = false,
        val mimeType: String? = null,
        val sizeBytes: Long? = null,
        val widthPx: Int? = null,
        val heightPx: Int? = null,
    )

    fun dismissTemplateEditor() {
        val staged = localControls.value.stagedImagePaths
        localControls.update { it.copy(editorState = null, stagedImagePaths = emptySet()) }
        if (staged.isNotEmpty()) {
            scope.launch {
                withContext(ioDispatcher) {
                    cleanupOrphanPaths(staged)
                }
            }
        }
    }

    fun importEditorImage(
        mimeTypeHint: String?,
        openInputStream: () -> InputStream?,
    ) {
        val store = imageStore
        if (store == null) {
            setEditorError("图片存储组件未就绪，请稍后重试")
            return
        }
        val currentEditor = localControls.value.editorState ?: return
        if (currentEditor.type != TemplateType.IMAGE || currentEditor.isImportingImage) return

        localControls.update { state ->
            state.copy(
                editorState = state.editorState?.copy(
                    isImportingImage = true,
                    errorMessage = null,
                ),
            )
        }

        scope.launch {
            try {
                val imported = withContext(ioDispatcher) {
                    val stream = openInputStream()
                        ?: throw IllegalArgumentException("无法读取所选图片内容，请重新选择")
                    stream.use { input ->
                        store.importFromStream(input = input, mimeTypeHint = mimeTypeHint)
                    }
                }
                var replacedPreviousStagedPath: String? = null
                localControls.update { state ->
                    val ed = state.editorState ?: run {
                        replacedPreviousStagedPath = imported.storedPath
                        return@update state
                    }
                    val prevPath = ed.imagePath
                    if (prevPath != null && prevPath != imported.storedPath && prevPath in state.stagedImagePaths) {
                        replacedPreviousStagedPath = prevPath
                    }
                    state.copy(
                        stagedImagePaths = state.stagedImagePaths + imported.storedPath,
                        editorState = ed.copy(
                            imagePath = imported.storedPath,
                            imageAvailable = true,
                            imageMimeType = imported.mimeType,
                            imageSizeBytes = imported.sizeBytes,
                            imageWidthPx = imported.widthPx,
                            imageHeightPx = imported.heightPx,
                            isImportingImage = false,
                            errorMessage = null,
                        ),
                    )
                }
                val orphanPath = replacedPreviousStagedPath
                if (orphanPath != null) {
                    withContext(ioDispatcher) {
                        cleanupOrphanPaths(setOf(orphanPath))
                    }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (ex: Throwable) {
                val message = ex.message?.takeIf { it.isNotBlank() }
                    ?: "导入图片失败（${ex.javaClass.simpleName}）"
                localControls.update { state ->
                    state.copy(
                        editorState = state.editorState?.copy(
                            isImportingImage = false,
                            errorMessage = message,
                        ),
                    )
                }
            }
        }
    }

    fun updateEditorName(name: String) {
        updateEditor { it.copy(name = name, errorMessage = null) }
    }

    fun updateEditorType(type: TemplateType) {
        updateEditor { current ->
            if (!current.isNew) return@updateEditor current
            current.copy(
                type = type,
                stickerName = if (type == TemplateType.STICKER && current.stickerName.isBlank()) {
                    DEFAULT_STICKER_NAME
                } else {
                    current.stickerName
                },
                stickerLabel = if (type == TemplateType.STICKER && current.stickerLabel.isBlank()) {
                    DEFAULT_STICKER_NAME
                } else {
                    current.stickerLabel
                },
                errorMessage = null,
            )
        }
    }

    fun updateEditorContent(content: String) {
        updateEditor { it.copy(content = content, errorMessage = null) }
    }

    fun updateEditorStickerName(stickerName: String) {
        updateEditor { it.copy(stickerName = stickerName, errorMessage = null) }
    }

    fun updateEditorStickerLabel(stickerLabel: String) {
        updateEditor { it.copy(stickerLabel = stickerLabel, errorMessage = null) }
    }

    fun applyStickerPreset(presetName: String) {
        val trimmed = presetName.trim()
        if (trimmed.isEmpty()) return
        updateEditor { current ->
            if (current.type != TemplateType.STICKER) return@updateEditor current
            val shouldSyncName = current.isNew &&
                (current.name.isBlank() || current.name.trim() in PRESET_STICKER_NAMES)
            val shouldSyncLabel = current.stickerLabel.isBlank() ||
                current.stickerLabel.trim() == current.stickerName.trim()
            current.copy(
                name = if (shouldSyncName) trimmed else current.name,
                stickerName = trimmed,
                stickerLabel = if (shouldSyncLabel) trimmed else current.stickerLabel,
                errorMessage = null,
            )
        }
    }

    fun updateEditorStickerCategory(stickerCategory: String) {
        updateEditor { it.copy(stickerCategory = stickerCategory, errorMessage = null) }
    }

    fun updateEditorStickerFallbackIndex(raw: String) {
        updateEditor { it.copy(stickerFallbackIndexInput = raw, errorMessage = null) }
    }

    fun updateEditorEnabled(enabled: Boolean) {
        updateEditor { it.copy(enabled = enabled, errorMessage = null) }
    }

    fun addEditorPoolDraftRow() {
        updateEditor { ed ->
            ed.copy(poolDrafts = ed.poolDrafts + PoolDraftItem(), errorMessage = null)
        }
    }

    fun removeEditorPoolDraftRow(index: Int) {
        updateEditor { ed ->
            if (ed.poolDrafts.size <= 1 || index !in ed.poolDrafts.indices) return@updateEditor ed
            ed.copy(
                poolDrafts = ed.poolDrafts.filterIndexed { i, _ -> i != index },
                errorMessage = null,
            )
        }
    }

    fun updateEditorPoolDraftContent(index: Int, content: String) {
        updateEditorPoolDraft(index) { it.copy(content = content) }
    }

    fun toggleEditorPoolDraftWeekday(index: Int, weekdayIndex: Int) {
        updateEditorPoolDraft(index) { it.copy(weekdayMask = toggleWeekdayBit(it.weekdayMask, weekdayIndex)) }
    }

    fun setEditorPoolDraftEveryday(index: Int) {
        updateEditorPoolDraft(index) { it.copy(weekdayMask = TemplatePoolPicker.WEEKDAY_MASK_ALL) }
    }

    fun toggleEditorPoolDraftEnabled(index: Int, enabled: Boolean) {
        updateEditorPoolDraft(index) { it.copy(enabled = enabled) }
    }

    private fun updateEditorPoolDraft(
        index: Int,
        transform: (PoolDraftItem) -> PoolDraftItem,
    ) {
        updateEditor { ed ->
            if (index !in ed.poolDrafts.indices) return@updateEditor ed
            val updated = ed.poolDrafts.mapIndexed { i, row -> if (i == index) transform(row) else row }
            ed.copy(poolDrafts = updated, errorMessage = null)
        }
    }

    private fun updateEditor(transform: (TemplateEditorState) -> TemplateEditorState) {
        localControls.update { state ->
            val current = state.editorState ?: return@update state
            state.copy(editorState = transform(current))
        }
    }

    fun saveTemplateEditor() {
        val editor = localControls.value.editorState ?: return
        if (editor.isImportingImage) return
        val cleanName = editor.name.trim()
        if (cleanName.isEmpty()) {
            setEditorError("请输入模板名称")
            return
        }

        var parsedFallbackIndex: Int? = null
        var cleanStickerName: String? = null
        var cleanStickerLabel: String? = null
        var cleanStickerCategory: String? = null
        var cleanImagePath: String? = null
        var cleanContent = ""

        when (editor.type) {
            TemplateType.TEXT -> {
                cleanContent = editor.content.trim()
                if (cleanContent.isEmpty()) {
                    setEditorError("固定文本模板内容不能为空")
                    return
                }
            }

            TemplateType.STICKER -> {
                cleanStickerName = editor.stickerName.trim()
                if (cleanStickerName.isEmpty()) {
                    setEditorError("原生表情名称不能为空（默认「比心」）")
                    return
                }
                cleanStickerLabel = editor.stickerLabel.trim().takeIf { it.isNotEmpty() } ?: cleanStickerName
                cleanStickerCategory = editor.stickerCategory.trim().takeIf { it.isNotEmpty() }
                val rawIndex = editor.stickerFallbackIndexInput.trim()
                if (rawIndex.isNotEmpty()) {
                    val idx = rawIndex.toIntOrNull()
                    if (idx == null || idx < 0) {
                        setEditorError("兜底索引须为非负整数（如 0、1、2）")
                        return
                    }
                    parsedFallbackIndex = idx
                }
            }

            TemplateType.POOL -> {
                val validDrafts = editor.poolDrafts.filter { it.content.trim().isNotEmpty() }
                if (validDrafts.isEmpty()) {
                    setEditorError("文案池模板至少需要 1 条非空候选句子")
                    return
                }
            }

            TemplateType.IMAGE -> {
                cleanImagePath = editor.imagePath?.trim()?.takeIf { it.isNotEmpty() }
                if (cleanImagePath == null) {
                    setEditorError("缺少本地图片，请重新选择")
                    return
                }
            }
        }

        val stagedSnapshot = localControls.value.stagedImagePaths
        localControls.update { it.copy(isBusy = true) }
        scope.launch {
            try {
                val nowMs = nowInstant().toEpochMilli()
                val outcome = withContext(ioDispatcher) {
                    if (editor.type == TemplateType.IMAGE &&
                        imageStore != null &&
                        !imageStore.isImageAvailable(cleanImagePath)
                    ) {
                        return@withContext SaveResult.Error("缺少本地图片，请重新选择")
                    }

                    val existingTemplates = repository.listTemplates(accountId)
                    val dup = existingTemplates.firstOrNull {
                        it.name == cleanName && it.id != editor.editingTemplateId
                    }
                    if (dup != null) {
                        return@withContext SaveResult.Error("模板名称「$cleanName」已存在")
                    }

                    if (editor.isNew) {
                        val templateId = UUID.randomUUID().toString()
                        val entity = MessageTemplateEntity(
                            id = templateId,
                            accountId = accountId,
                            name = cleanName,
                            type = editor.type,
                            content = cleanContent,
                            stickerName = cleanStickerName,
                            stickerLabel = cleanStickerLabel,
                            stickerCategory = cleanStickerCategory,
                            stickerFallbackIndex = parsedFallbackIndex,
                            enabled = editor.enabled,
                            createdAt = nowMs,
                            updatedAt = nowMs,
                            imagePath = if (editor.type == TemplateType.IMAGE) cleanImagePath else null,
                        )
                        repository.upsertTemplate(entity)
                        if (editor.type == TemplateType.POOL) {
                            editor.poolDrafts
                                .filter { it.content.trim().isNotEmpty() }
                                .forEachIndexed { idx, draft ->
                                    repository.upsertPoolItem(
                                        TemplatePoolItemEntity(
                                            id = UUID.randomUUID().toString(),
                                            templateId = templateId,
                                            content = draft.content.trim(),
                                            weekdayMask = normalizeWeekdayMask(draft.weekdayMask),
                                            enabled = draft.enabled,
                                            lastUsedAt = null,
                                            createdAt = nowMs + idx,
                                            updatedAt = nowMs + idx,
                                        ),
                                    )
                                }
                        }
                        val unusedStaged = if (editor.type == TemplateType.IMAGE && cleanImagePath != null) {
                            stagedSnapshot - cleanImagePath
                        } else {
                            stagedSnapshot
                        }
                        cleanupOrphanPaths(unusedStaged)
                        SaveResult.Success("已创建模板「$cleanName」")
                    } else {
                        val editingId = editor.editingTemplateId
                            ?: return@withContext SaveResult.Error("模板 ID 丢失")
                        val existing = repository.getTemplateById(editingId)
                            ?: return@withContext SaveResult.Error("模板不存在或已被删除")
                        val previousImagePath = existing.imagePath
                        val nextImagePath = if (existing.type == TemplateType.IMAGE) {
                            cleanImagePath
                        } else {
                            existing.imagePath
                        }
                        repository.updateTemplate(
                            existing.copy(
                                name = cleanName,
                                content = if (existing.type == TemplateType.TEXT) cleanContent else existing.content,
                                stickerName = if (existing.type == TemplateType.STICKER) cleanStickerName else existing.stickerName,
                                stickerLabel = if (existing.type == TemplateType.STICKER) cleanStickerLabel else existing.stickerLabel,
                                stickerCategory = if (existing.type == TemplateType.STICKER) cleanStickerCategory else existing.stickerCategory,
                                stickerFallbackIndex = if (existing.type == TemplateType.STICKER) parsedFallbackIndex else existing.stickerFallbackIndex,
                                enabled = editor.enabled,
                                updatedAt = nowMs,
                                imagePath = nextImagePath,
                            ),
                        )

                        if (existing.type == TemplateType.POOL) {
                            // 严格保留既有候选的 lastUsedAt 与 createdAt，绝不因编辑重置轮换序
                            val currentDbItems = repository.listPoolItems(existing.id)
                            val currentById = currentDbItems.associateBy { it.id }
                            val cleanedDrafts = editor.poolDrafts.filter { it.content.trim().isNotEmpty() }
                            val retainedIds = cleanedDrafts.mapNotNull { it.id }.toSet()

                            for (old in currentDbItems) {
                                if (old.id !in retainedIds) {
                                    repository.deletePoolItemById(old.id)
                                }
                            }
                            cleanedDrafts.forEachIndexed { idx, draft ->
                                val old = draft.id?.let { currentById[it] }
                                if (old != null) {
                                    repository.updatePoolItem(
                                        old.copy(
                                            content = draft.content.trim(),
                                            weekdayMask = normalizeWeekdayMask(draft.weekdayMask),
                                            enabled = draft.enabled,
                                            lastUsedAt = old.lastUsedAt,
                                            updatedAt = nowMs,
                                        ),
                                    )
                                } else {
                                    repository.upsertPoolItem(
                                        TemplatePoolItemEntity(
                                            id = UUID.randomUUID().toString(),
                                            templateId = existing.id,
                                            content = draft.content.trim(),
                                            weekdayMask = normalizeWeekdayMask(draft.weekdayMask),
                                            enabled = draft.enabled,
                                            lastUsedAt = draft.lastUsedAt,
                                            createdAt = nowMs + idx,
                                            updatedAt = nowMs + idx,
                                        ),
                                    )
                                }
                            }
                        }

                        val orphanCandidates = buildSet {
                            addAll(stagedSnapshot)
                            if (existing.type == TemplateType.IMAGE &&
                                !previousImagePath.isNullOrBlank() &&
                                previousImagePath != nextImagePath
                            ) {
                                add(previousImagePath)
                            }
                            if (nextImagePath != null) {
                                remove(nextImagePath)
                            }
                        }
                        cleanupOrphanPaths(orphanCandidates)
                        SaveResult.Success("已保存模板「$cleanName」")
                    }
                }

                when (outcome) {
                    is SaveResult.Error -> localControls.update { state ->
                        state.copy(
                            isBusy = false,
                            editorState = state.editorState?.copy(errorMessage = outcome.message),
                        )
                    }

                    is SaveResult.Success -> localControls.update {
                        it.copy(
                            isBusy = false,
                            editorState = null,
                            stagedImagePaths = emptySet(),
                            feedbackMessage = outcome.message,
                        )
                    }
                }
            } catch (ex: Throwable) {
                localControls.update { state ->
                    state.copy(
                        isBusy = false,
                        editorState = state.editorState?.copy(
                            errorMessage = "保存模板失败（${ex.javaClass.simpleName}）",
                        ),
                    )
                }
            }
        }
    }

    private suspend fun cleanupOrphanPaths(candidates: Set<String>) {
        val store = imageStore ?: return
        val cleanCandidates = candidates.mapNotNull { it.trim().takeIf { p -> p.isNotEmpty() } }.toSet()
        if (cleanCandidates.isEmpty()) return
        val referenced = repository.listAllReferencedImagePaths().toSet()
        for (path in cleanCandidates) {
            store.deleteIfUnreferenced(path, referenced)
        }
    }

    private sealed interface SaveResult {
        data class Success(val message: String) : SaveResult
        data class Error(val message: String) : SaveResult
    }

    private fun setEditorError(message: String) {
        updateEditor { it.copy(errorMessage = message) }
    }

    fun toggleTemplateEnabled(template: MessageTemplateEntity, enabled: Boolean) {
        scope.launch {
            withContext(ioDispatcher) {
                repository.updateTemplate(
                    template.copy(
                        enabled = enabled,
                        updatedAt = nowInstant().toEpochMilli(),
                    ),
                )
            }
        }
    }

    fun requestDeleteTemplate(template: MessageTemplateEntity) {
        scope.launch {
            val count = withContext(ioDispatcher) {
                repository.countActiveTargetsByTemplate(template.id)
            }
            localControls.update {
                it.copy(
                    deleteConfirmState = DeleteTemplateConfirmState(
                        template = template,
                        activeReferenceCount = count,
                    ),
                )
            }
        }
    }

    fun dismissDeleteConfirm() {
        localControls.update { it.copy(deleteConfirmState = null) }
    }

    fun confirmDeleteTemplate() {
        val confirm = localControls.value.deleteConfirmState ?: return
        localControls.update { it.copy(deleteConfirmState = null, isBusy = true) }
        scope.launch {
            try {
                withContext(ioDispatcher) {
                    repository.deleteTemplateById(confirm.template.id)
                    if (confirm.template.type == TemplateType.IMAGE && !confirm.template.imagePath.isNullOrBlank()) {
                        cleanupOrphanPaths(setOf(confirm.template.imagePath))
                    }
                }
                localControls.update {
                    it.copy(
                        isBusy = false,
                        feedbackMessage = if (confirm.activeReferenceCount > 0) {
                            "已删除模板「${confirm.template.name}」，原绑定的 ${confirm.activeReferenceCount} 位好友已转为未绑定"
                        } else {
                            "已删除模板「${confirm.template.name}」"
                        },
                    )
                }
            } finally {
                localControls.update { it.copy(isBusy = false) }
            }
        }
    }

    // ---- 展开文案池直接内联增删改查 ----

    fun updateInlinePoolInput(templateId: String, content: String) {
        localControls.update { controls ->
            val prev = controls.inlineInputs[templateId] ?: InlinePoolInputState()
            controls.copy(
                inlineInputs = controls.inlineInputs + (
                    templateId to prev.copy(content = content, errorMessage = null)
                    ),
            )
        }
    }

    fun toggleInlinePoolWeekday(templateId: String, weekdayIndex: Int) {
        localControls.update { controls ->
            val prev = controls.inlineInputs[templateId] ?: InlinePoolInputState()
            val nextMask = toggleWeekdayBit(prev.weekdayMask, weekdayIndex)
            controls.copy(
                inlineInputs = controls.inlineInputs + (
                    templateId to prev.copy(weekdayMask = nextMask, errorMessage = null)
                    ),
            )
        }
    }

    fun setInlinePoolEveryday(templateId: String) {
        localControls.update { controls ->
            val prev = controls.inlineInputs[templateId] ?: InlinePoolInputState()
            controls.copy(
                inlineInputs = controls.inlineInputs + (
                    templateId to prev.copy(
                        weekdayMask = TemplatePoolPicker.WEEKDAY_MASK_ALL,
                        errorMessage = null,
                    )
                    ),
            )
        }
    }

    fun addInlinePoolItem(templateId: String) {
        val current = localControls.value.inlineInputs[templateId] ?: InlinePoolInputState()
        val cleanContent = current.content.trim()
        if (cleanContent.isEmpty()) {
            localControls.update { controls ->
                controls.copy(
                    inlineInputs = controls.inlineInputs + (
                        templateId to current.copy(errorMessage = "请输入候选文案内容")
                        ),
                )
            }
            return
        }
        val mask = normalizeWeekdayMask(current.weekdayMask)
        localControls.update { controls ->
            controls.copy(
                inlineInputs = controls.inlineInputs + (templateId to InlinePoolInputState()),
            )
        }
        addPoolItem(templateId = templateId, content = cleanContent, weekdayMask = mask, enabled = true)
    }

    fun addPoolItem(
        templateId: String,
        content: String,
        weekdayMask: Int,
        enabled: Boolean = true,
    ) {
        val cleanContent = content.trim()
        if (cleanContent.isEmpty()) return
        val normalizedMask = normalizeWeekdayMask(weekdayMask)
        scope.launch {
            withContext(ioDispatcher) {
                val nowMs = nowInstant().toEpochMilli()
                repository.upsertPoolItem(
                    TemplatePoolItemEntity(
                        id = UUID.randomUUID().toString(),
                        templateId = templateId,
                        content = cleanContent,
                        weekdayMask = normalizedMask,
                        enabled = enabled,
                        lastUsedAt = null,
                        createdAt = nowMs,
                        updatedAt = nowMs,
                    ),
                )
            }
        }
    }

    fun openEditPoolItemDialog(item: TemplatePoolItemEntity) {
        localControls.update {
            it.copy(
                poolItemEditDialog = PoolItemEditDialogState(
                    templateId = item.templateId,
                    itemId = item.id,
                    content = item.content,
                    weekdayMask = normalizeWeekdayMask(item.weekdayMask),
                    enabled = item.enabled,
                    lastUsedAt = item.lastUsedAt,
                ),
            )
        }
    }

    fun dismissPoolItemEditDialog() {
        localControls.update { it.copy(poolItemEditDialog = null) }
    }

    fun updatePoolItemEditContent(content: String) {
        localControls.update { state ->
            state.copy(
                poolItemEditDialog = state.poolItemEditDialog?.copy(
                    content = content,
                    errorMessage = null,
                ),
            )
        }
    }

    fun togglePoolItemEditWeekday(weekdayIndex: Int) {
        localControls.update { state ->
            val dlg = state.poolItemEditDialog ?: return@update state
            state.copy(
                poolItemEditDialog = dlg.copy(
                    weekdayMask = toggleWeekdayBit(dlg.weekdayMask, weekdayIndex),
                    errorMessage = null,
                ),
            )
        }
    }

    fun setPoolItemEditEveryday() {
        localControls.update { state ->
            val dlg = state.poolItemEditDialog ?: return@update state
            state.copy(
                poolItemEditDialog = dlg.copy(
                    weekdayMask = TemplatePoolPicker.WEEKDAY_MASK_ALL,
                    errorMessage = null,
                ),
            )
        }
    }

    fun updatePoolItemEditEnabled(enabled: Boolean) {
        localControls.update { state ->
            state.copy(
                poolItemEditDialog = state.poolItemEditDialog?.copy(enabled = enabled),
            )
        }
    }

    fun savePoolItemEditDialog() {
        val dlg = localControls.value.poolItemEditDialog ?: return
        val cleanContent = dlg.content.trim()
        if (cleanContent.isEmpty()) {
            localControls.update { state ->
                state.copy(
                    poolItemEditDialog = state.poolItemEditDialog?.copy(
                        errorMessage = "候选句子内容不能为空",
                    ),
                )
            }
            return
        }
        localControls.update { it.copy(poolItemEditDialog = null) }
        updatePoolItem(
            templateId = dlg.templateId,
            itemId = dlg.itemId,
            content = cleanContent,
            weekdayMask = dlg.weekdayMask,
            enabled = dlg.enabled,
        )
    }

    fun updatePoolItem(
        templateId: String,
        itemId: String,
        content: String,
        weekdayMask: Int,
        enabled: Boolean,
    ) {
        val cleanContent = content.trim()
        if (cleanContent.isEmpty()) return
        val normalizedMask = normalizeWeekdayMask(weekdayMask)
        scope.launch {
            withContext(ioDispatcher) {
                val existing = repository.listPoolItems(templateId).firstOrNull { it.id == itemId }
                    ?: return@withContext
                repository.updatePoolItem(
                    existing.copy(
                        content = cleanContent,
                        weekdayMask = normalizedMask,
                        enabled = enabled,
                        // 严禁修改 lastUsedAt
                        lastUsedAt = existing.lastUsedAt,
                        updatedAt = nowInstant().toEpochMilli(),
                    ),
                )
            }
        }
    }

    fun togglePoolItemWeekday(templateId: String, itemId: String, weekdayIndex: Int) {
        scope.launch {
            withContext(ioDispatcher) {
                val existing = repository.listPoolItems(templateId).firstOrNull { it.id == itemId }
                    ?: return@withContext
                val nextMask = toggleWeekdayBit(existing.weekdayMask, weekdayIndex)
                repository.updatePoolItem(
                    existing.copy(
                        weekdayMask = nextMask,
                        lastUsedAt = existing.lastUsedAt,
                        updatedAt = nowInstant().toEpochMilli(),
                    ),
                )
            }
        }
    }

    fun setPoolItemEveryday(templateId: String, itemId: String) {
        scope.launch {
            withContext(ioDispatcher) {
                val existing = repository.listPoolItems(templateId).firstOrNull { it.id == itemId }
                    ?: return@withContext
                repository.updatePoolItem(
                    existing.copy(
                        weekdayMask = TemplatePoolPicker.WEEKDAY_MASK_ALL,
                        lastUsedAt = existing.lastUsedAt,
                        updatedAt = nowInstant().toEpochMilli(),
                    ),
                )
            }
        }
    }

    fun togglePoolItemEnabled(templateId: String, itemId: String, enabled: Boolean) {
        scope.launch {
            withContext(ioDispatcher) {
                val existing = repository.listPoolItems(templateId).firstOrNull { it.id == itemId }
                    ?: return@withContext
                repository.updatePoolItem(
                    existing.copy(
                        enabled = enabled,
                        lastUsedAt = existing.lastUsedAt,
                        updatedAt = nowInstant().toEpochMilli(),
                    ),
                )
            }
        }
    }

    fun deletePoolItem(itemId: String) {
        scope.launch {
            withContext(ioDispatcher) {
                repository.deletePoolItemById(itemId)
            }
        }
    }

    /**
     * 纯只读预览当前文案池下一次会被 `TemplatePoolPicker` 命中的句子，绝不修改 `lastUsedAt`。
     */
    fun previewPoolNextPick(
        templateId: String,
        weekdayIndex: Int? = null,
    ): TemplatePoolItemEntity? {
        val card = _uiState.value.poolTemplates.firstOrNull { it.template.id == templateId }
            ?: return null
        val targetWeekday = weekdayIndex ?: TemplatePoolPicker.weekdayIndexOf(
            ZonedDateTime.ofInstant(nowInstant(), zoneIdProvider()).dayOfWeek,
        )
        return TemplatePoolPicker.pick(card.poolItems.map { it.item }, targetWeekday)
    }

    fun clearFeedbackMessage() {
        localControls.update { it.copy(feedbackMessage = null) }
    }

    companion object {
        const val DEFAULT_STICKER_NAME = "比心"
        val PRESET_STICKER_NAMES: List<String> = listOf("比心", "早上好", "续火花")
        val WEEKDAY_LABELS = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
        val WEEKDAY_SHORT_LABELS = listOf("一", "二", "三", "四", "五", "六", "日")
        val TEMPLATE_VARIABLES = listOf(
            "{{target_name}}" to "好友昵称",
            "{{streak_days}}" to "连续天数",
            "{{weekday}}" to "星期几",
            "{{date}}" to "今日日期",
        )

        private val LAST_USED_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        private val PREVIEW_DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy/MM/dd")
        private val TEMPLATE_TOKEN_REGEX = Regex("""\{\{\s*([a-z_]+)\s*\}\}""")

        fun normalizeWeekdayMask(mask: Int): Int {
            val clamped = mask and TemplatePoolPicker.WEEKDAY_MASK_MAX
            return if (clamped == TemplatePoolPicker.WEEKDAY_MASK_MAX) {
                TemplatePoolPicker.WEEKDAY_MASK_ALL
            } else {
                clamped
            }
        }

        fun toggleWeekdayBit(currentMask: Int, weekdayIndex: Int): Int {
            require(weekdayIndex in 0..6) { "weekdayIndex must be in 0..6, got $weekdayIndex" }
            val normalizedCurrent = normalizeWeekdayMask(currentMask)
            val base = if (normalizedCurrent == TemplatePoolPicker.WEEKDAY_MASK_ALL) {
                0
            } else {
                normalizedCurrent
            }
            val toggled = base xor (1 shl weekdayIndex)
            return normalizeWeekdayMask(toggled)
        }

        fun maskFromSelectedDays(selectedDays: Set<Int>): Int {
            var mask = 0
            for (day in selectedDays) {
                require(day in 0..6) { "weekdayIndex must be in 0..6, got $day" }
                mask = mask or (1 shl day)
            }
            return normalizeWeekdayMask(mask)
        }

        fun isEverydayMask(mask: Int): Boolean =
            normalizeWeekdayMask(mask) == TemplatePoolPicker.WEEKDAY_MASK_ALL

        fun isSpecificDayChecked(mask: Int, weekdayIndex: Int): Boolean {
            require(weekdayIndex in 0..6) { "weekdayIndex must be in 0..6, got $weekdayIndex" }
            if (isEverydayMask(mask)) return false
            return ((mask shr weekdayIndex) and 1) == 1
        }

        fun formatWeekdayMask(mask: Int): String {
            val normalized = normalizeWeekdayMask(mask)
            if (normalized == TemplatePoolPicker.WEEKDAY_MASK_ALL) {
                return "每天"
            }
            val labels = WEEKDAY_LABELS.indices.filter { ((normalized shr it) and 1) == 1 }
                .map { WEEKDAY_LABELS[it] }
            return if (labels.isEmpty()) "每天" else labels.joinToString("、")
        }

        fun formatLastUsedAt(lastUsedAt: Long?, zoneId: ZoneId = ZoneId.systemDefault()): String {
            if (lastUsedAt == null) return "从未发送"
            val zdt = ZonedDateTime.ofInstant(Instant.ofEpochMilli(lastUsedAt), zoneId)
            return "上次发送: ${LAST_USED_FORMATTER.format(zdt)}"
        }

        fun renderTemplatePreview(
            template: String,
            target: ContractTargetEntity?,
            zoneId: ZoneId = ZoneId.systemDefault(),
            now: ZonedDateTime = ZonedDateTime.now(zoneId),
        ): String {
            val weekdayChar = "一二三四五六日"[now.dayOfWeek.value - 1]
            val values = mapOf(
                "target_name" to (target?.nicknameSnapshot ?: "测试好友"),
                "streak_days" to (target?.streakDays ?: 7).toString(),
                "weekday" to "星期$weekdayChar",
                "date" to PREVIEW_DATE_FORMATTER.format(now),
            )
            return TEMPLATE_TOKEN_REGEX.replace(template) { match ->
                val key = match.groupValues[1]
                values[key] ?: match.value
            }
        }

        fun mimeFromExtension(ext: String): String = when (ext.lowercase(Locale.ROOT)) {
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            else -> "image/jpeg"
        }

        fun formatImageSize(sizeBytes: Long): String = when {
            sizeBytes < 1024L -> "$sizeBytes B"
            sizeBytes < 1024L * 1024L -> String.format(Locale.US, "%.1f KB", sizeBytes / 1024.0)
            else -> String.format(Locale.US, "%.2f MB", sizeBytes / (1024.0 * 1024.0))
        }

        fun formatImageSummary(
            mimeType: String?,
            sizeBytes: Long?,
            widthPx: Int?,
            heightPx: Int?,
        ): String {
            val formatLabel = when (mimeType?.lowercase(Locale.ROOT)) {
                "image/png" -> "PNG"
                "image/gif" -> "GIF"
                "image/webp" -> "WebP"
                "image/jpeg" -> "JPEG"
                else -> "图片"
            }
            val parts = mutableListOf(formatLabel)
            if (widthPx != null && heightPx != null && widthPx > 0 && heightPx > 0) {
                parts += "${widthPx}×${heightPx}"
            }
            if (sizeBytes != null && sizeBytes > 0L) {
                parts += formatImageSize(sizeBytes)
            }
            return parts.joinToString(" · ")
        }

        fun factory(
            database: FlameDatabase,
            imageStore: TemplateImageStorage? = null,
            accountId: String = DEFAULT_ACCOUNT_ID,
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return TemplatesViewModel(
                        repository = RoomTemplatesRepository(database),
                        accountId = accountId,
                        imageStore = imageStore,
                    ) as T
                }
            }
    }
}
