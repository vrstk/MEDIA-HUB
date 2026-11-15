package com.mediahub.mobile.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mediahub.mobile.domain.CloudFileCopy
import com.mediahub.mobile.domain.CloudFolderCopy
import com.mediahub.mobile.domain.CopyVerificationException
import com.mediahub.mobile.domain.FolderVerificationPendingException
import com.mediahub.mobile.domain.CloudNode
import com.mediahub.mobile.domain.CloudProvider
import com.mediahub.mobile.domain.CopyPhase
import com.mediahub.mobile.domain.CopyProgress
import com.mediahub.mobile.drive.DriveAuthorizationRequired
import com.mediahub.mobile.drive.DriveProvider
import com.mediahub.mobile.mail.MailProvider
import com.mediahub.mobile.mail.MailWebDavClient
import com.mediahub.mobile.saf.SafFolderProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlin.coroutines.coroutineContext

internal enum class CloudSite(val label: String) {
    MAIL("Облако Mail"),
    DRIVE("Google Drive");

    val other get() = if (this == MAIL) DRIVE else MAIL
}

internal data class CopySelection(
    val site: CloudSite,
    val provider: CloudProvider,
    val file: CloudNode,
    val target: CloudProvider
)

internal sealed interface CopyState {
    val name: String

    data class Running(
        override val name: String,
        val progress: CopyProgress? = null,
        val cancelling: Boolean = false,
        val folderCopy: Boolean = false,
        val reconcileExisting: Boolean = false
    ) : CopyState
    data class Verified(override val name: String, val size: Long, val files: Int = 1, val folders: Int = 0, val reusedFiles: Int = 0) : CopyState
    data class VerificationPending(override val name: String, val message: String) : CopyState
    data class Failed(override val name: String, val message: String) : CopyState
    data class Cancelled(override val name: String) : CopyState
}

internal class CloudTransferViewModel : ViewModel() {
    private val _drive = MutableStateFlow<DriveProvider?>(null)
    val drive = _drive.asStateFlow()
    private val _safDrive = MutableStateFlow<SafFolderProvider?>(null)
    val safDrive = _safDrive.asStateFlow()
    fun attachSafDrive(provider: SafFolderProvider?) {
        if (_copy.value is CopyState.Running) return
        _safDrive.value = provider
        _selection.value = null
    }
    private fun activeDrive(): CloudProvider? = _safDrive.value ?: _drive.value
    private val _driveError = MutableStateFlow<String?>(null)
    val driveError = _driveError.asStateFlow()
    private val _authorizing = MutableStateFlow(false)
    val authorizing = _authorizing.asStateFlow()
    private val _selection = MutableStateFlow<CopySelection?>(null)
    val selection = _selection.asStateFlow()
    private val _copy = MutableStateFlow<CopyState?>(null)
    val copy = _copy.asStateFlow()
    private var journalStore: TransferJournalStore? = null
    private val _history = MutableStateFlow<List<TransferJournalEntry>>(emptyList())
    val history = _history.asStateFlow()

    fun initializeJournal(context: Context) {
        if (journalStore != null) return
        val store = TransferJournalStore(context)
        journalStore = store
        _history.value = store.restore()
    }
    private var copiedJob: Job? = null
    private var token: String? = null
    private var invalidToken: String? = null

    fun beginDriveAuthorization(): Boolean {
        if (_authorizing.value || _copy.value is CopyState.Running) return false
        _authorizing.value = true
        _driveError.value = null
        return true
    }

    fun acceptDriveToken(value: String) {
        viewModelScope.launch {
            val provider = DriveProvider(value)
            try {
                runInterruptible(Dispatchers.IO) { provider.checkAccess() }
                token = value
                _drive.value = provider
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e is DriveAuthorizationRequired) invalidToken = value
                _driveError.value = e.userMessage("Не удалось проверить доступ к Google Drive.")
            } finally {
                _authorizing.value = false
            }
        }
    }

    fun failDriveAuthorization(message: String) {
        _driveError.value = message
        _authorizing.value = false
    }

    fun expireDrive() {
        disconnectDrive()?.let { invalidToken = it }
        _driveError.value = "Доступ к Google Drive истёк. Подключите диск снова."
    }

    fun takeInvalidToken(): String? = invalidToken.also { invalidToken = null }

    fun disconnectDrive(): String? {
        _drive.value = null
        _driveError.value = null
        _selection.value = null
        return token.also { token = null }
    }

    fun selectMail(client: MailWebDavClient, file: CloudNode) {
        val destination = activeDrive() ?: return
        _selection.value = CopySelection(CloudSite.MAIL, MailProvider(client), file, destination)
    }

    fun selectDrive(mail: MailProvider, file: CloudNode) {
        val provider = activeDrive() ?: return
        _selection.value = CopySelection(CloudSite.DRIVE, provider, file, mail)
    }

    fun clearSelection() { _selection.value = null }

    fun startCopy(selection: CopySelection, folder: CloudNode, reconcileExisting: Boolean = false): Boolean {
        if (_selection.value !== selection || _copy.value is CopyState.Running) return false
        if (reconcileExisting && !selection.file.isFolder) return false
        val name = selection.file.name
        // Journal is committed before anything can be written to the destination.
        val journalEntry = try {
            journalStore?.begin(name, selection.site)
        } catch (e: Exception) {
            _copy.value = CopyState.Failed(name, "Не удалось сохранить журнал операций. Копирование не запускалось.")
            return false
        }
        _history.value = journalStore?.restoreForDisplay() ?: _history.value
        _selection.value = null
        _copy.value = CopyState.Running(name, folderCopy = selection.file.isFolder, reconcileExisting = reconcileExisting)
        copiedJob = viewModelScope.launch {
            val execution = coroutineContext
            try {
                val result = runInterruptible(Dispatchers.IO) {
                    if (selection.file.isFolder) {
                        val result = CloudFolderCopy.run(
                            selection.provider, selection.file, selection.target, folder,
                            checkpoint = { execution.ensureActive() },
                            report = { progress ->
                                _copy.update { current ->
                                    if (current is CopyState.Running && !current.cancelling) {
                                        current.copy(progress = CopyProgress(
                                            progress.phase ?: CopyPhase.COPYING,
                                            progress.completedFiles.toLong(),
                                            progress.totalFiles.toLong()
                                        ))
                                    } else current
                                }
                            },
                            reconcileExisting = reconcileExisting
                        )
                        listOf(result.copiedBytes, result.copiedFiles.toLong(),
                            result.createdFolders.toLong(), result.reusedFiles.toLong())
                    } else {
                        var lastPhase: CopyPhase? = null
                        var lastUpdate = 0L
                        val bytes = CloudFileCopy.run(
                        selection.provider, selection.file, selection.target, folder,
                        checkpoint = { execution.ensureActive() },
                        report = { progress ->
                            val now = System.nanoTime()
                            if (lastPhase != progress.phase || progress.done == progress.total ||
                                now - lastUpdate >= 150_000_000
                            ) {
                                _copy.update { current ->
                                    if (current is CopyState.Running && !current.cancelling) {
                                        current.copy(progress = progress)
                                    } else current
                                }
                                lastPhase = progress.phase
                                lastUpdate = now
                            }
                        }
                        )
                        listOf(bytes, 1L, 0L, 0L)
                    }
                }
                _copy.value = CopyState.Verified(name, result[0], result[1].toInt(), result[2].toInt(),
                    result[3].toInt())
                finishJournal(journalEntry, TransferJournalStatus.VERIFIED, result[1].toInt(),
                    result[0], if (reconcileExisting) "Сверено: ${result[1]}, ранее существовали: ${result[3]}, новых: ${result[1] - result[3]}." else "")
            } catch (e: CancellationException) {
                _copy.value = CopyState.Cancelled(name)
                finishJournal(journalEntry, TransferJournalStatus.CANCELLED, note = "Отменено. Проверьте папку назначения.")
                throw e
            } catch (e: Exception) {
                if (e is DriveAuthorizationRequired || e.cause is DriveAuthorizationRequired) expireDrive()
                _copy.value = when (e) {
                    is FolderVerificationPendingException -> CopyState.VerificationPending(
                        name,
                        "Провайдер сообщил о создании последнего файла «${e.pendingFile}», " +
                            "но проверить его пока не удалось. Подтверждено " +
                            "${e.verifiedFiles} из ${e.totalFiles}. " +
                            "Проверьте все файлы в назначении. Не запускайте перенос заново."
                    )
                    is CopyVerificationException -> CopyState.VerificationPending(
                        name,
                        "Провайдер создал файл, но подтвердить его содержимое " +
                            "пока не удалось. Проверьте файл в назначении, не копируя повторно."
                    )
                    else -> CopyState.Failed(name,
                        e.userMessage("Передача прервалась. Проверьте папку назначения."))
                }
                val status = if (_copy.value is CopyState.VerificationPending)
                    TransferJournalStatus.NEEDS_REVIEW else TransferJournalStatus.FAILED
                finishJournal(journalEntry, status, note = (_copy.value as? CopyState.VerificationPending)?.message
                    ?: (_copy.value as? CopyState.Failed)?.message.orEmpty())
            }
        }
        return true
    }

    private fun finishJournal(
        entry: TransferJournalEntry?,
        status: TransferJournalStatus,
        count: Int = 0,
        bytes: Long = 0,
        note: String = ""
    ) {
        if (entry == null) return
        try {
            _history.value = journalStore?.finish(entry.id, status, count, bytes, note)
                ?: _history.value
        } catch (_: Exception) {
            // Current transfer result remains visible; a RUNNING journal record is
            // conservatively classified as interrupted on the next launch.
        }
    }

    fun cancelCopy() {
        val current = _copy.value as? CopyState.Running ?: return
        _copy.value = current.copy(cancelling = true)
        copiedJob?.cancel()
    }
}

internal fun Exception.userMessage(fallback: String): String =
    when (this) {
        is java.net.SocketTimeoutException -> "Сервер не ответил вовремя. Проверьте подключение."
        is java.net.UnknownHostException -> "Сервер недоступен. Проверьте интернет."
        is java.io.IOException -> message?.takeIf { it.length < 240 && !it.contains("://") } ?: fallback
        is IllegalArgumentException -> message?.takeIf { it.length < 240 && !it.contains("://") } ?: fallback
        else -> fallback
    }
