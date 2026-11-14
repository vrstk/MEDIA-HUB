package com.mediahub.mobile.mail

import java.io.EOFException
import java.io.IOException
import java.net.ProtocolException
import java.net.SocketException
import java.net.SocketTimeoutException

/** A single, bounded recovery attempt is safe only for read-only photo GET requests. */
internal object PreviewRetryPolicy {
    fun retryable(failure: IOException): Boolean =
        failure is SocketException ||
        failure is SocketTimeoutException ||
        failure is EOFException ||
        failure is ProtocolException
}
