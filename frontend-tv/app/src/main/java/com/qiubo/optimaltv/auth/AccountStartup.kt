package com.qiubo.optimaltv.auth

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Local ownership gates the UI; remote failure leaves that safe cache usable. */
internal suspend fun prepareAccountStartup(
    prepareLocal: suspend () -> Unit,
    onPrepared: () -> Unit,
    syncRemote: suspend () -> Unit,
    onSyncFailure: (Exception) -> Unit,
) {
    prepareLocal()
    currentCoroutineContext().ensureActive()
    onPrepared()
    try {
        syncRemote()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        onSyncFailure(failure)
    }
}
