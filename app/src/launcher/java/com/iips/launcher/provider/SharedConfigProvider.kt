package com.iips.launcher.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import com.iips.launcher.storage.SecurePreferences

class SharedConfigProvider : ContentProvider() {

    companion object {
        const val AUTHORITY = "com.iips.launcher.config"
        val CONTENT_URI: Uri = Uri.parse("content://$AUTHORITY/params")
    }

    override fun onCreate(): Boolean {
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? {
        if (uri.path == "/params") {
            val json = context?.let { SecurePreferences.getSharedJsonParams(it) } ?: "{}"
            val cursor = MatrixCursor(arrayOf("json_data"))
            cursor.addRow(arrayOf(json))
            return cursor
        }
        return null
    }

    override fun getType(uri: Uri): String? {
        if (uri.path == "/params") {
            return "vnd.android.cursor.item/vnd.$AUTHORITY.params"
        }
        return null
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        throw UnsupportedOperationException("Insert not supported")
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        throw UnsupportedOperationException("Delete not supported")
    }

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
        throw UnsupportedOperationException("Update not supported")
    }
}
