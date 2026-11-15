package com.mediahub.mobile.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mediahub.mobile.catalog.CatalogCriteria
import com.mediahub.mobile.catalog.CatalogImportState
import com.mediahub.mobile.catalog.CatalogItem
import com.mediahub.mobile.catalog.CatalogSection
import com.mediahub.mobile.catalog.CatalogViewModel
import com.mediahub.mobile.catalog.ImportPhase
import com.mediahub.mobile.catalog.ProfileRow
import com.mediahub.mobile.catalog.TaxonomyTerm
import com.mediahub.mobile.catalog.isCatalogWebUrl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.Locale

@Composable
internal fun CatalogPanel(model: CatalogViewModel, modifier: Modifier = Modifier) {
    val summary by model.summary.collectAsState()
    val importState by model.importState.collectAsState()
    val summaryError by model.summaryError.collectAsState()
    val importing = importState is CatalogImportState.Working
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) model.import(uri)
    }
    Column(
        modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("Медиатека", Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium)
            if ((summary?.profiles ?: 0) > 0) {
                TextButton(onClick = { picker.launch(arrayOf("*/*")) }, enabled = !importing) {
                    Text("Импорт")
                }
            }
        }

        CatalogStatus(importState, model::dismissImportStatus)
        val counts = summary
        when {
            summaryError -> {
                Text("Не удалось открыть локальную медиатеку.", color = MaterialTheme.colorScheme.error)
                TextButton(onClick = model::retrySummary) { Text("Повторить") }
            }
            counts == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
            counts.profiles == 0 -> {
                InfoCard("Архивный каталог", "Выберите стартовый ZIP, проектный ZIP или SQLite-каталог Media Hub. Профили, категории и теги сохранятся на устройстве.")
                Button(enabled = !importing, onClick = { picker.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Импортировать каталог")
                }
            }
            else -> {
                Text(
                    "${countText(counts.profiles)} профилей · ${countText(counts.categories)} категорий · ${countText(counts.tags)} тегов",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                CatalogBrowser(model, counts.unnamed)
            }
        }
    }
}

@Composable
private fun CatalogStatus(status: CatalogImportState, dismiss: () -> Unit) {
    when (status) {
        CatalogImportState.Idle -> Unit
        is CatalogImportState.Working -> {
            Text(when (status.phase) {
                ImportPhase.READING -> "Чтение файла"
                ImportPhase.CHECKING -> "Проверка каталога"
                ImportPhase.SAVING -> "Сохранение профилей и тегов"
            }, style = MaterialTheme.typography.labelMedium)
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        is CatalogImportState.Finished -> {
            val title = if (status.result.added) "Архив импортирован" else "Этот архив уже импортирован"
            CatalogNotice(title, false, dismiss)
        }
        is CatalogImportState.Failed -> CatalogNotice(status.message, true, dismiss)
    }
}

@Composable
private fun CatalogNotice(text: String, error: Boolean, dismiss: () -> Unit) {
    Surface(
        color = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.medium
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            IconButton(onClick = dismiss) { Icon(Icons.Default.Close, "Закрыть сообщение") }
        }
    }
}

private class CatalogItems {
    var rows by mutableStateOf<List<CatalogItem>>(emptyList())
        private set
    var loading by mutableStateOf(true)
        private set
    var more by mutableStateOf(false)
        private set
    var error by mutableStateOf(false)
        private set

    suspend fun fetch(model: CatalogViewModel, criteria: CatalogCriteria) {
        loading = true
        error = false
        try {
            val page = model.page(criteria, rows.size)
            rows = rows + page.items
            more = page.hasMore
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            error = true
        } finally {
            loading = false
        }
    }
}

@Composable
private fun CatalogBrowser(model: CatalogViewModel, unnamedCount: Int) {
    var sectionIndex by rememberSaveable { mutableIntStateOf(CatalogSection.PROFILES.ordinal) }
    var nameQuery by rememberSaveable { mutableStateOf("") }
    var taxonomyQuery by rememberSaveable { mutableStateOf("") }
    var unnamed by rememberSaveable { mutableStateOf(false) }
    var termId by rememberSaveable { mutableStateOf<String?>(null) }
    var termLabel by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedProfile by remember { mutableStateOf<ProfileRow?>(null) }
    val section = CatalogSection.entries[sectionIndex]
    val profiles = section == CatalogSection.PROFILES
    val query = if (profiles) nameQuery else taxonomyQuery
    val criteria = CatalogCriteria(section, query.trim(), profiles && unnamed, if (profiles) termId else null)
    val revision by model.revision.collectAsState()
    val entries = remember(criteria, revision) { CatalogItems() }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(entries) {
        if (criteria.text.isNotEmpty()) delay(180)
        entries.fetch(model, criteria)
    }
    LaunchedEffect(criteria) { listState.scrollToItem(0) }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PrimaryTabRow(selectedTabIndex = sectionIndex) {
            CatalogSection.entries.forEach { tab ->
                Tab(selected = tab == section, onClick = { sectionIndex = tab.ordinal }, text = { Text(tab.title) })
            }
        }
        OutlinedTextField(
            value = query,
            onValueChange = { value ->
                if (value.length <= 180) {
                    if (profiles) nameQuery = value else taxonomyQuery = value
                }
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(if (profiles) "Имя, псевдоним или ссылка" else "Название на русском или английском") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = if (query.isNotEmpty()) {
                { IconButton(onClick = { if (profiles) nameQuery = "" else taxonomyQuery = "" }) {
                    Icon(Icons.Default.Close, "Очистить поиск")
                } }
            } else null
        )
        if (profiles) {
            FilterChip(
                selected = unnamed,
                onClick = { unnamed = !unnamed },
                label = { Text("Без имени · ${countText(unnamedCount)}") }
            )
            termLabel?.let { label ->
                FilterChip(
                    selected = true, onClick = { termId = null; termLabel = null },
                    label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    trailingIcon = { Icon(Icons.Default.Close, "Снять фильтр") }
                )
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(entries.rows, key = { it.id }) { entry ->
                when (entry) {
                    is CatalogItem.Profile -> CatalogRow(
                        title = entry.row.title,
                        description = listOfNotNull(entry.row.sourceName, entry.row.sourceSnapshot).joinToString(" · ")
                    ) { selectedProfile = entry.row }
                    is CatalogItem.Term -> CatalogRow(entry.row.labelRu, entry.row.label) {
                        termId = entry.row.id
                        termLabel = entry.row.labelRu
                        nameQuery = ""
                        unnamed = false
                        sectionIndex = CatalogSection.PROFILES.ordinal
                    }
                }
            }
            when {
                entries.loading -> item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                entries.error -> item {
                    TextButton(onClick = { scope.launch { if (!entries.loading) entries.fetch(model, criteria) } }) {
                        Text("Не удалось загрузить список. Повторить")
                    }
                }
                entries.rows.isEmpty() -> item { Text("Совпадений нет.", Modifier.padding(vertical = 8.dp)) }
                entries.more -> item {
                    TextButton(
                        onClick = { scope.launch { if (!entries.loading) entries.fetch(model, criteria) } },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Показать ещё") }
                }
            }
        }
    }

    selectedProfile?.let { row ->
        ProfileDetails(model, row, onDismiss = { selectedProfile = null }) {
            val term = it
            selectedProfile = null
            termId = term.id
            termLabel = term.labelRu
            nameQuery = ""
            unnamed = false
            sectionIndex = CatalogSection.PROFILES.ordinal
        }
    }
}

@Composable
private fun CatalogRow(title: String, description: String, onOpen: () -> Unit) {
    Card(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(description, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun ProfileDetails(
    model: CatalogViewModel,
    row: ProfileRow,
    onDismiss: () -> Unit,
    onTerm: (TaxonomyTerm) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var localName by remember { mutableStateOf(row.localName) }
    var terms by remember { mutableStateOf<List<TaxonomyTerm>>(emptyList()) }
    var editing by rememberSaveable { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(row.id) {
        try {
            terms = model.terms(row.id)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            saveError = "Связанные теги недоступны."
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(localName ?: row.archiveName ?: "Без имени", maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            LazyColumn(Modifier.heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                item {
                    Text(row.sourceName, style = MaterialTheme.typography.titleSmall)
                    row.sourceSnapshot?.let { Text("Снимок от $it", style = MaterialTheme.typography.bodySmall) }
                    if (localName != null && row.archiveName != null) Text("В архиве — ${row.archiveName}")
                    row.aliases?.takeIf { it.isNotBlank() }?.let { Text("Псевдонимы — $it") }
                    Text(row.sourceUrl, style = MaterialTheme.typography.bodySmall)
                    TextButton(enabled = isCatalogWebUrl(row.sourceUrl), onClick = {
                        try {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(row.sourceUrl)))
                        } catch (_: ActivityNotFoundException) {
                            saveError = "Не найден браузер для открытия ссылки."
                        }
                    }) { Text("Открыть источник") }
                    saveError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
                if (terms.isNotEmpty()) {
                    item { Text("Категории и теги", style = MaterialTheme.typography.titleSmall) }
                    items(terms, key = { it.id }) { term ->
                        TextButton(onClick = { onTerm(term) }, modifier = Modifier.fillMaxWidth()) {
                            Text(term.labelRu, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { editing = true }) { Text("Своё имя") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } }
    )

    if (editing) {
        var value by rememberSaveable { mutableStateOf(localName ?: row.archiveName.orEmpty()) }
        var saving by remember { mutableStateOf(false) }

        fun save(name: String?) {
            saving = true
            scope.launch {
                try {
                    localName = model.saveName(row.id, name)
                    saveError = null
                    editing = false
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    saveError = "Не удалось сохранить имя."
                    editing = false
                } finally {
                    saving = false
                }
            }
        }
        AlertDialog(
            onDismissRequest = { if (!saving) editing = false },
            title = { Text("Имя в медиатеке") },
            text = {
                Column {
                    OutlinedTextField(value, { if (it.length <= 120) value = it },
                        singleLine = true, label = { Text("Имя") }, enabled = !saving)
                    if (localName != null) {
                        TextButton(onClick = { save(null) }, enabled = !saving) { Text("Вернуть имя из архива") }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { save(value) }, enabled = !saving && value.isNotBlank()) { Text("Сохранить") }
            },
            dismissButton = { TextButton(onClick = { editing = false }, enabled = !saving) { Text("Отмена") } }
        )
    }
}

private fun countText(value: Int): String = NumberFormat.getIntegerInstance(Locale.forLanguageTag("ru")).format(value)
