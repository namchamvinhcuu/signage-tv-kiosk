package com.youngmin.signagekiosk

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller

/**
 * Status of the self-update session committed by [Updater]. It may run in a fresh process with no activity, so it
 * only writes the local log + a prefs record; MainActivity reports the record remotely as `app_update stage=result`.
 * A session that still wants the user's confirmation is abandoned: nobody is in front of the TV (B6, D11).
 */
class UpdateResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        KLog.init(context)
        val store = ConfigStore(context)
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, STATUS_UNKNOWN)
        val sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        val code = intent.getIntExtra(EXTRA_CODE, 0)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        val now = System.currentTimeMillis()
        KLog.i("update session $sessionId for $code: status $status ${message.orEmpty()}")
        val outcome = when (status) {
            // MY_PACKAGE_REPLACED reopens the app (UpdateReplacedReceiver).
            PackageInstaller.STATUS_SUCCESS -> UpdateResult.Outcome.SUCCESS
            PackageInstaller.STATUS_PENDING_USER_ACTION -> UpdateResult.Outcome.PENDING_USER_ACTION
            // Also what our own abandon below produces, as a second broadcast for the same session.
            PackageInstaller.STATUS_FAILURE_ABORTED -> UpdateResult.Outcome.ABORTED
            else -> UpdateResult.Outcome.FAILURE
        }
        if (outcome == UpdateResult.Outcome.PENDING_USER_ACTION) {
            // Never launch EXTRA_INTENT: a confirmation dialog would sit over the signage with nobody to answer.
            try {
                context.packageManager.packageInstaller.abandonSession(sessionId)
            } catch (e: Exception) {
                KLog.w("abandon session $sessionId failed", e)
            }
        }
        if (UpdateResult.clearsPending(outcome)) store.takePendingUpdateCode()
        store.saveUpdateState(UpdateResult.nextState(outcome, store.loadUpdateState(), now))
        val stored = store.peekUpdateResult()
        if (UpdateResult.shouldRecord(outcome, stored?.status, stored?.code ?: 0, code)) {
            store.saveUpdateResult(statusName(status), code, message)
        } else {
            KLog.i("update session $sessionId: ${statusName(status)} after our abandon, pending_user_action record kept")
        }
    }

    private fun statusName(status: Int): String = when (status) {
        PackageInstaller.STATUS_SUCCESS -> "success"
        PackageInstaller.STATUS_PENDING_USER_ACTION -> UpdateResult.PENDING_USER_ACTION_NAME
        PackageInstaller.STATUS_FAILURE_TIMEOUT -> "failure_timeout"
        PackageInstaller.STATUS_FAILURE_ABORTED -> "failure_aborted"
        PackageInstaller.STATUS_FAILURE_BLOCKED -> "failure_blocked"
        PackageInstaller.STATUS_FAILURE_CONFLICT -> "failure_conflict"
        PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "failure_incompatible"
        PackageInstaller.STATUS_FAILURE_INVALID -> "failure_invalid"
        PackageInstaller.STATUS_FAILURE_STORAGE -> "failure_storage"
        PackageInstaller.STATUS_FAILURE -> "failure"
        else -> "status_$status"
    }

    companion object {
        const val EXTRA_CODE = "update_code"
        private const val STATUS_UNKNOWN = -999
    }
}
