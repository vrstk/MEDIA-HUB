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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.mediahub.mobile.R
import com.mediahub.mobile.catalog.CatalogViewModel
import com.mediahub.mobile.catalog.ProfileFolder
import com.mediahub.mobile.catalog.ProfileFolderAccess
import com.mediahub.mobile.catalog.ProfileStorageKind
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
internal fun MediaHubScreen(
    darkTheme: Boolean,
    onThemeToggle: () -> Unit,
    transfers: CloudTransferViewModel,
    catalog: CatalogViewModel
) {
    val context = LocalContext.current
    val mailStore = remember(context) { SecureMailAccountStore(context) }
    var mailAccount by remember { mutableStateOf(mailStore.load()) }
    val mail = remember(mailAccount) { mailAccount?.let { MailProvider(MailWebDavClient(it.email, it.password)) } }
    val drive by transfers.drive.collectAsState()
    val safDrive by transfers.safDrive.collectAsState()
    val profileStorage = remember(mailAccount, mail, safDrive) {
        buildList {
            mailAccount?.let { account ->
                if (mail != null) add(ProfileFolderAccess.mail(account.email, mail))
            }
            safDrive?.let { add(ProfileFolderAccess.androidDrive(it.treeUri.toString(), it)) }
        }
    }
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
    LaunchedEffect(transfers) { transfers.initializeJournal(context) }
    val transferHistory by transfers.history.collectAsState()
    val driveActions = rememberDriveActions(transfers)
    val screenState = rememberSaveableStateHolder()
    var showDrive by rememberSaveable { mutableStateOf(false) }

    var selected by rememberSaveable { mutableIntStateOf(0) }
    val current = Screen.entries[selected]
    var folderRequest by remember { mutableStateOf<ProfileFolder?>(null) }
    var profileDestination by rememberSaveable { mutableIntStateOf(-1) }
    val requestedKind = folderRequest?.kind
    val requestedTrail = folderRequest?.let { link ->
        profileStorage.firstNotNullOfOrNull { it.resolve(link) }
    }

    fun storageScreen(kind: ProfileStorageKind): Screen = when (kind) {
        ProfileStorageKind.MAIL -> Screen.MAIL
        ProfileStorageKind.ANDROID_DRIVE -> Screen.FILES
    }

    fun goToStorage(kind: ProfileStorageKind) {
        showDrive = kind == ProfileStorageKind.ANDROID_DRIVE
        selected = storageScreen(kind).ordinal
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name), fontWeight = FontWeight.SemiBold, maxLines = 1) },
                navigationIcon = {
                    if (selected == profileDestination) {
                        IconButton(onClick = {
                            profileDestination = -1
                            selected = Screen.LIBRARY.ordinal
                        }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "К профилю") }
                    }
                },
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
                            if (index != selected) profileDestination = -1
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
                        requestedFolder = requestedTrail?.lastOrNull()?.id.takeIf { requestedKind == ProfileStorageKind.MAIL },
                        onFolderRequestHandled = { folderRequest = null },
                        onCopy = transfers::selectMail
                    )
                }
            }
        } else if (current == Screen.LIBRARY) {
            screenState.SaveableStateProvider("library") {
                CatalogPanel(
                    catalog, profileStorage,
                    onConnect = { kind ->
                        profileDestination = storageScreen(kind).ordinal
                        goToStorage(kind)
                        if (kind == ProfileStorageKind.ANDROID_DRIVE && !copying) folderPicker.launch(null)
                    },
                    onOpenFolder = { link ->
                        val kind = link.kind
                        if (kind != null && profileStorage.any { it.resolve(link) != null }) {
                            folderRequest = link
                            profileDestination = storageScreen(kind).ordinal
                            goToStorage(kind)
                        }
                    },
                    modifier = Modifier.padding(padding)
                )
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
                    DrivePanel(
                        drive = safDrive ?: drive,
                        safMode = safDrive != null,
                        driveError = safError ?: driveError,
                        authorizing = authorizing,
                        mailConnected = mail != null,
                        copying = copying,
                        actions = driveActions,
                        onExpired = transfers::expireDrive,
                        onSelectFolder = { folderPicker.launch(null) },
                        onDisconnectFolder = {
                            safStore.disconnect()
                            transfers.attachSafDrive(null)
                            safError = null
                        },
                        requestedTrail = requestedTrail.takeIf { requestedKind == ProfileStorageKind.ANDROID_DRIVE },
                        onFolderRequestHandled = { folderRequest = null }
                    ) { node -> mail?.let { transfers.selectDrive(it, node) } }
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
                    Button(onClick = { selected = Screen.LIBRARY.ordinal }, modifier = Modifier.fillMaxWidth()) {
                        Text("Открыть медиатеку")
                    }
                    Text("Хранилища", style = MaterialTheme.typography.titleMedium)
                    OutlinedButton(onClick = { showDrive = false; selected = Screen.FILES.ordinal },
                        modifier = Modifier.fillMaxWidth()) { Text("Файлы на устройстве") }
                    OutlinedButton(onClick = { showDrive = true; selected = Screen.FILES.ordinal },
                        modifier = Modifier.fillMaxWidth()) { Text("Открыть Google Drive") }
                    OutlinedButton(onClick = { selected = Screen.MAIL.ordinal },
                        modifier = Modifier.fillMaxWidth()) { Text("Открыть Облако Mail") }
                }
                Screen.FILES, Screen.MAIL, Screen.LIBRARY -> Unit
                Screen.TASKS -> {
                    CloudCopyTask(copy, transfers::cancelCopy)
                    TransferHistoryPanel(transferHistory)
                }
            }
        }
    }
    selection?.let { currentCopy ->
        CloudCopyDialog(currentCopy, transfers::clearSelection, transfers::expireDrive) { folder, reconcile ->
            if (transfers.startCopy(currentCopy, folder, reconcile)) selected = Screen.TASKS.ordinal
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
