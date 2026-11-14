package com.mediahub.mobile.ui

import androidx.compose.foundation.*
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.mediahub.mobile.saf.SafFolderStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.mediahub.mobile.R
import com.mediahub.mobile.mail.MailProvider
import com.mediahub.mobile.mail.MailWebDavClient
import com.mediahub.mobile.mail.SecureMailAccountStore
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private enum class Screen(val title: String, val symbol: String) {
    HOME("Главная", "⌂"),
    LIBRARY("Медиатека", "▦"),
    FILES("Файлы", "▤"),
    TASKS("Задачи", "✓"),
    MAIL("Mail", "☁")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MediaHubScreen(darkTheme: Boolean, onThemeToggle: () -> Unit, transfers: CloudTransferViewModel) {
    val context = LocalContext.current
    val mailStore = remember(context) { SecureMailAccountStore(context) }
    var mailAccount by remember { mutableStateOf(mailStore.load()) }
    val mail = remember(mailAccount) { mailAccount?.let { MailProvider(MailWebDavClient(it.email, it.password)) } }
    val drive by transfers.drive.collectAsState()
    val safDrive by transfers.safDrive.collectAsState()
    val safStore = remember(context) { SafFolderStore(context) }
    val scope = rememberCoroutineScope()
    var safError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        val restored = withContext(Dispatchers.IO) { safStore.restore() }
        if (restored != null) transfers.attachSafDrive(restored)
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) scope.launch {
            try {
                val selectedFolder = withContext(Dispatchers.IO) { safStore.connect(uri) }
                transfers.attachSafDrive(selectedFolder)
                safError = null
            } catch (e: Exception) {
                safError = e.message ?: "Не удалось открыть выбранную папку."
            }
        }
    }
    val driveError by transfers.driveError.collectAsState()
    val authorizing by transfers.authorizing.collectAsState()
    val selection by transfers.selection.collectAsState()
    val copy by transfers.copy.collectAsState()
    val copying = copy is CopyState.Running
    val driveActions = rememberDriveActions(transfers)
    val screenState = rememberSaveableStateHolder()
    var showDrive by rememberSaveable { mutableStateOf(false) }

    var selected by rememberSaveable { mutableIntStateOf(0) }
    val current = Screen.entries[selected]
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name), fontWeight = FontWeight.SemiBold, maxLines = 1) },
                actions = {
                    TextButton(onClick = onThemeToggle) {
                        Text(if (darkTheme) "Светлая" else "Тёмная")
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                Screen.entries.forEachIndexed { index, screen ->
                    NavigationBarItem(
                        selected = selected == index,
                        onClick = {
                            if (selected == Screen.FILES.ordinal && index == selected) showDrive = false
                            selected = index
                        },
                        icon = { Text(screen.symbol) },
                        label = { Text(screen.title) }
                    )
                }
            }
        }
    ) { padding ->
        if (current == Screen.MAIL) {
            Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                screenState.SaveableStateProvider("mail") {
                    MailPanel(
                        account = mailAccount,
                        store = mailStore,
                        onAccountChanged = { mailAccount = it },
                        driveConnected = drive != null || safDrive != null,
                        copying = copying,
                        onCopy = transfers::selectMail
                    )
                }
            }
        } else if (current == Screen.FILES) {
            Column(
                Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Файлы", style = MaterialTheme.typography.headlineMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(!showDrive, { showDrive = false }, label = { Text("На устройстве") })
                    FilterChip(showDrive, { showDrive = true }, label = { Text("Google Drive") })
                }
                if (showDrive) {
                    DrivePanel(safDrive ?: drive, safDrive != null, safError ?: driveError, authorizing, mail != null, copying, driveActions, transfers::expireDrive, onSelectFolder = { folderPicker.launch(null) }, onDisconnectFolder = {
                        safStore.disconnect()
                        transfers.attachSafDrive(null)
                        safError = null
                    }) {
                        mail?.let { provider -> transfers.selectDrive(provider, it) }
                    }
                } else {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        LocalFilesPanel()
                    }
                }
            }
        } else Column(
            modifier = Modifier.fillMaxSize().padding(padding)
                .verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(current.title, style = MaterialTheme.typography.headlineMedium)
            when (current) {
                Screen.HOME -> {
                    InfoCard("Добро пожаловать", "Медиафайлы и облака в одном приложении. Это первая техническая версия.")
                    InfoCard("Локальные файлы", "Откройте вкладку «Файлы», чтобы выбрать папку Android и работать с ней без доступа ко всему накопителю.")
                    Button(onClick = { selected = Screen.FILES.ordinal }, modifier = Modifier.fillMaxWidth()) {
                        Text("Открыть файловый менеджер")
                    }
                    OutlinedButton(onClick = { showDrive = true; selected = Screen.FILES.ordinal },
                        modifier = Modifier.fillMaxWidth()) { Text("Открыть Google Drive") }
                    OutlinedButton(onClick = { selected = Screen.MAIL.ordinal },
                        modifier = Modifier.fillMaxWidth()) { Text("Открыть Облако Mail") }
                }
                Screen.LIBRARY -> {
                    InfoCard("Каталог", "Подготовлен отдельный архивный каталог: 22 739 профилей, 1 187 категорий и тегов. Импорт в приложение пока не реализован.")
                    InfoCard("Библиотеки", "Модели, личные фотографии и другие коллекции будут разделены. Фотографии из каталога не загружены.")
                    InfoCard("Умная группировка", "Анализ сходства лиц будет доступен только после включения пользователем. Сейчас он отключён.")
                }
                Screen.FILES, Screen.MAIL -> Unit
                Screen.TASKS -> CloudCopyTask(copy, transfers::cancelCopy)
            }
        }
    }
    selection?.let { currentCopy ->
        CloudCopyDialog(currentCopy, transfers::clearSelection, transfers::expireDrive) { folder ->
            if (transfers.startCopy(currentCopy, folder)) selected = Screen.TASKS.ordinal
        }
    }
}

@Composable
internal fun InfoCard(title: String, description: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
            Text(description, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
