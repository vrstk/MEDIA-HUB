package com.mediahub.mobile.ui

import android.app.Activity
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Scope

private const val DRIVE_FILE = "https://www.googleapis.com/auth/drive.file"

internal data class DriveActions(val connect: () -> Unit, val disconnect: () -> Unit)

@Composable
internal fun rememberDriveActions(model: CloudTransferViewModel): DriveActions {
    val activity = checkNotNull(LocalActivity.current)
    val authorization = remember(activity) { Identity.getAuthorizationClient(activity) }

    fun failure(error: Exception) {
        val status = (error as? ApiException)?.statusCode
        val detail = when (status) {
            CommonStatusCodes.DEVELOPER_ERROR ->
                "В Google Cloud нужен Android OAuth-клиент с пакетом ${activity.packageName} и SHA-1 этой APK."
            CommonStatusCodes.NETWORK_ERROR -> "Google недоступен. Проверьте интернет."
            CommonStatusCodes.CANCELED -> "Google вернул код отмены авторизации."
            else -> "Не удалось разрешить доступ к Google Drive. Проверьте настройки OAuth и повторите."
        }
        model.failDriveAuthorization(detail + (status?.let { " (код Google: $it)" } ?: ""))
    }

    fun accept(result: AuthorizationResult) {
        val token = result.accessToken
        if (token.isNullOrBlank() || DRIVE_FILE !in result.grantedScopes) {
            model.failDriveAuthorization("Разрешение на доступ к файлам Google Drive не получено.")
        } else model.acceptDriveToken(token)
    }

    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        // RESULT_CANCELED does not prove the user deliberately closed the dialog:
        // OAuth configuration issues can also prevent the flow from completing.
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            try {
                accept(authorization.getAuthorizationResultFromIntent(data))
            } catch (error: ApiException) {
                failure(error)
            }
        } else if (data != null) {
            try {
                // The returned intent may include an actual Google status code.
                authorization.getAuthorizationResultFromIntent(data)
                model.failDriveAuthorization("Google не подтвердил подключение. Откройте диагностику OAuth ниже.")
            } catch (error: ApiException) {
                failure(error)
            }
        } else {
            model.failDriveAuthorization(
                "Диалог Google завершился без разрешения (resultCode=${result.resultCode}). " +
                    "Если вы его не закрывали, проверьте пакет и SHA-1 в диагностике OAuth."
            )
        }
    }

    fun authorize() {
        val request = AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(DRIVE_FILE))).build()
        authorization.authorize(request).addOnSuccessListener { result ->
            val pending = result.pendingIntent
            if (result.hasResolution() && pending != null) {
                try {
                    consent.launch(IntentSenderRequest.Builder(pending).build())
                } catch (error: Exception) {
                    failure(error)
                }
            } else accept(result)
        }.addOnFailureListener(::failure)
    }

    fun clearCached(token: String, done: () -> Unit = {}) {
        authorization.clearToken(ClearTokenRequest.builder().setToken(token).build())
            .addOnCompleteListener { done() }
    }

    return DriveActions(
        connect = {
            if (model.beginDriveAuthorization()) {
                val available = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(activity)
                if (available != com.google.android.gms.common.ConnectionResult.SUCCESS) {
                    model.failDriveAuthorization("Для Google Drive нужны актуальные Сервисы Google Play.")
                } else {
                    val rejected = model.takeInvalidToken()
                    if (rejected == null) authorize() else clearCached(rejected, ::authorize)
                }
            }
        },
        disconnect = { model.disconnectDrive()?.let { clearCached(it) } }
    )
}
