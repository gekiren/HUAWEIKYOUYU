package com.example.huaweikyouyu.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import java.io.BufferedWriter
import java.io.OutputStreamWriter

object ObsidianStorageManager {
    private const val TAG = "ObsidianStorageManager"
    private const val PREFS_NAME = "obsidian_prefs"
    private const val KEY_VAULT_URI = "vault_uri"

    /**
     * Get Intent to open Directory Picker for SAF
     */
    fun getDirectoryPickerIntent(): Intent {
        return Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
    }

    /**
     * Persist URI permissions and save it in SharedPreferences
     */
    fun saveVaultUri(context: Context, uri: Uri): Boolean {
        return try {
            val contentResolver = context.contentResolver
            val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            contentResolver.takePersistableUriPermission(uri, takeFlags)

            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putString(KEY_VAULT_URI, uri.toString()).apply()
            Log.d(TAG, "Saved Vault URI: $uri")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save Vault URI", e)
            false
        }
    }

    /**
     * Get saved Vault URI from SharedPreferences
     */
    fun getSavedVaultUri(context: Context): Uri? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val uriString = prefs.getString(KEY_VAULT_URI, null) ?: return null
        return Uri.parse(uriString)
    }

    /**
     * Save daily note (Markdown) in the saved Vault directory
     *
     * @param date Date string in format YYYY-MM-DD
     * @param content Markdown content to write
     */
    fun saveDailyNote(context: Context, date: String, content: String): Boolean {
        val vaultUri = getSavedVaultUri(context) ?: return false
        return try {
            val rootDoc = DocumentFile.fromTreeUri(context, vaultUri) ?: return false
            if (!rootDoc.exists() || !rootDoc.canWrite()) {
                Log.e(TAG, "Root document is not writeable or does not exist")
                return false
            }

            val fileName = "$date.md"
            var dailyFile = rootDoc.findFile(fileName)

            if (dailyFile == null) {
                dailyFile = rootDoc.createFile("text/markdown", fileName)
            }

            if (dailyFile == null) {
                Log.e(TAG, "Failed to create daily note file")
                return false
            }

            context.contentResolver.openOutputStream(dailyFile.uri, "rwt")?.use { outputStream ->
                BufferedWriter(OutputStreamWriter(outputStream)).use { writer ->
                    writer.write(content)
                }
            }
            Log.d(TAG, "Successfully wrote daily note to $fileName")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error writing daily note", e)
            false
        }
    }
}
