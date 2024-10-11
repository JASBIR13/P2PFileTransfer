package com.example.p2pfiletransfer

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.*

object FileProviderUtils {

    private const val TAG = "FileProviderUtils"

    /**
     * Converts the content URI to a file in the internal storage directory asynchronously.
     */
    suspend fun getFileFromUriAsync(context: Context, uri: Uri): File? {
        return withContext(Dispatchers.IO) {
            when (uri.scheme) {
                ContentResolver.SCHEME_CONTENT -> handleContentUri(context, uri)
                ContentResolver.SCHEME_FILE -> handleFileUri(uri)
                else -> {
                    Log.e(TAG, "Unsupported URI scheme: ${uri.scheme}")
                    null
                }
            }
        }
    }

    /**
     * Handles content URIs by retrieving the file name and copying the content to internal storage.
     */
    private fun handleContentUri(context: Context, uri: Uri): File? {
        val contentResolver: ContentResolver = context.contentResolver

        // Query the filename from the URI
        val fileName = queryFileName(contentResolver, uri)
        if (fileName == null) {
            Log.e(TAG, "Failed to retrieve file name from URI: $uri")
            return null
        }

        val sanitizedFileName = sanitizeFileName(fileName)
        val uniqueFile = getUniqueFile(context, sanitizedFileName)

        // Write the content of the file
        try {
            val inputStream = contentResolver.openInputStream(uri)
            if (inputStream == null) {
                Log.e(TAG, "InputStream is null for URI: $uri")
                return null
            }
            copyStreamToFile(inputStream, uniqueFile)
        } catch (e: Exception) {
            Log.e(TAG, "Error copying file from URI: $uri", e)
            return null
        }

        return uniqueFile
    }

    /**
     * Handles file URIs by creating a File object directly.
     */
    private fun handleFileUri(uri: Uri): File? {
        return try {
            File(uri.path ?: throw IllegalArgumentException("URI path is null: $uri"))
        } catch (e: Exception) {
            Log.e(TAG, "Error handling file URI: $uri", e)
            null
        }
    }

    /**
     * Retrieves the display name of the file from the URI.
     */
    private fun queryFileName(contentResolver: ContentResolver, uri: Uri): String? {
        var name: String? = null
        val cursor = contentResolver.query(uri, null, null, null, null)

        if (cursor != null) {
            cursor.use {
                if (it.moveToFirst()) {
                    val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex != -1) {
                        name = it.getString(nameIndex)
                    } else {

                    }
                } else {
                    Log.e(TAG, "Cursor did not move to first item for URI: $uri")
                }
            }
        } else {
            Log.e(TAG, "Cursor is null for URI: $uri")
        }

        return name
    }

    /**
     * Sanitizes the file name by replacing illegal characters.
     */
    private fun sanitizeFileName(fileName: String): String {
        return fileName.replace("[^a-zA-Z0-9._-]".toRegex(), "_")
    }

    /**
     * Generates a unique file to prevent name collisions.
     */
    private fun getUniqueFile(context: Context, fileName: String): File {
        var uniqueFile = File(context.filesDir, fileName)
        var count = 1
        val nameWithoutExtension = fileName.substringBeforeLast(".")
        val extension = fileName.substringAfterLast(".", "")

        while (uniqueFile.exists()) {
            uniqueFile = File(context.filesDir, "${nameWithoutExtension}_$count.$extension")
            count++
        }
        return uniqueFile
    }

    /**
     * Copies the content of an InputStream to a file.
     */
    private fun copyStreamToFile(inputStream: InputStream, file: File) {
        FileOutputStream(file).use { outputStream ->
            val buffer = ByteArray(32768) // Increased buffer size for better performance
            var length: Int
            while (inputStream.read(buffer).also { length = it } > 0) {
                outputStream.write(buffer, 0, length)
            }
        }
    }
}
