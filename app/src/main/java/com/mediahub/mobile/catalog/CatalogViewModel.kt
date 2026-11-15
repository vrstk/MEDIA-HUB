package com.mediahub.mobile.catalog

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

internal sealed interface CatalogImportState {
    data object Idle : CatalogImportState
    data class Working(val phase: ImportPhase) : CatalogImportState
    data class Finished(val result: CatalogImportResult) : CatalogImportState
    data class Failed(val message: String) : CatalogImportState
}

internal enum class CatalogSection(val title: String, val kind: String?) {
    PROFILES("Профили", null),
    CATEGORIES("Категории", "category"),
    TAGS("Теги", "tag")
}

internal data class CatalogCriteria(
    val section: CatalogSection,
    val text: String,
    val unnamed: Boolean,
    val termId: String?
)

internal sealed interface CatalogItem {
    val id: String
    data class Profile(val row: ProfileRow) : CatalogItem { override val id get() = row.id }
    data class Term(val row: TaxonomyTerm) : CatalogItem { override val id get() = row.id }
}

internal data class CatalogPage(val items: List<CatalogItem>, val hasMore: Boolean)

internal data class CatalogProfile(
    val row: ProfileRow,
    val terms: List<TaxonomyTerm>,
    val folders: List<ProfileFolder>
)

@OptIn(ExperimentalCoroutinesApi::class)
internal class CatalogViewModel(private val app: Application) : AndroidViewModel(app) {
    private val database = CatalogDatabase.open(app)
    private val dao = database.catalog()
    private val importer = CatalogImporter(database, app.cacheDir)
    private val _importState = MutableStateFlow<CatalogImportState>(CatalogImportState.Idle)
    val importState = _importState.asStateFlow()
    private val _revision = MutableStateFlow(0)
    val revision = _revision.asStateFlow()
    private val summaryReload = MutableStateFlow(0)
    private val _summaryError = MutableStateFlow(false)
    val summaryError = _summaryError.asStateFlow()
    val summary = summaryReload.flatMapLatest {
        dao.summary().onStart { _summaryError.value = false }
            .catch { _summaryError.value = true }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun retrySummary() { summaryReload.update { it + 1 } }

    fun import(uri: Uri) {
        if (_importState.value is CatalogImportState.Working) return
        _importState.value = CatalogImportState.Working(ImportPhase.READING)
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val source = app.contentResolver.openInputStream(uri)
                        ?: throw IOException("Archive stream unavailable")
                    source.use { importer.importArchive(it) { phase ->
                        _importState.value = CatalogImportState.Working(phase)
                    } }
                }
                if (result.added) _revision.update { it + 1 }
                _importState.value = CatalogImportState.Finished(result)
            } catch (e: CancellationException) {
                throw e
            } catch (e: InvalidCatalog) {
                _importState.value = CatalogImportState.Failed(e.message.orEmpty())
            } catch (_: Exception) {
                _importState.value = CatalogImportState.Failed("Не удалось открыть файл. Выберите его ещё раз и проверьте свободное место.")
            }
        }
    }

    fun dismissImportStatus() {
        if (_importState.value !is CatalogImportState.Working) _importState.value = CatalogImportState.Idle
    }

    suspend fun page(criteria: CatalogCriteria, offset: Int): CatalogPage {
        val query = normalizedSearch(criteria.text)
        val kind = criteria.section.kind
        val result = if (kind == null) {
            dao.profiles(query, criteria.unnamed, criteria.termId, offset, PAGE_SIZE + 1)
                .map(CatalogItem::Profile)
        } else {
            dao.terms(kind, query, offset, PAGE_SIZE + 1).map(CatalogItem::Term)
        }
        return CatalogPage(result.take(PAGE_SIZE), result.size > PAGE_SIZE)
    }

    suspend fun profileDetails(profileId: String): CatalogProfile? {
        val row = dao.profile(profileId) ?: return null
        return CatalogProfile(row, dao.profileTerms(profileId), dao.profileFolders(profileId))
    }

    suspend fun saveFolder(folder: ProfileFolder) {
        dao.saveFolder(folder)
        _revision.update { it + 1 }
    }

    suspend fun removeFolder(folder: ProfileFolder) {
        dao.removeFolder(folder)
        _revision.update { it + 1 }
    }

    suspend fun saveName(profileId: String, value: String?): String? {
        if (value == null) {
            dao.resetProfileName(profileId)
            _revision.update { it + 1 }
            return null
        }
        val name = value.trim()
        require(name.isNotEmpty() && name.length <= 120)
        dao.saveProfileName(ProfileName(profileId, name, normalizedSearch(name)))
        _revision.update { it + 1 }
        return name
    }

    private companion object { const val PAGE_SIZE = 60 }
}
