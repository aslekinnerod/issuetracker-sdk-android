package io.issuetracker.sdk.internal

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri

/**
 * Auto-init ContentProvider — runs at app startup before
 * Application.onCreate. Lets identify() and recordAction() work even
 * if the host app calls them before Issuetracker.configure(). Same
 * pattern Firebase, WorkManager, etc. use to avoid forcing the host
 * to wire up initialization.
 *
 * Manifest authority is `${applicationId}.io.issuetracker.sdk.init`
 * so two apps using the SDK can coexist on the same device without
 * authority collision.
 */
internal class SdkInitProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        val ctx = context?.applicationContext ?: return false
        ReporterIdentity.install(ctx)
        BreadcrumbStore.install(ctx)
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0
}
