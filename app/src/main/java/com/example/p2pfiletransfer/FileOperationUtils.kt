package com.example.p2pfiletransfer

import android.util.Log
import java.io.File

object FileOperationUtils {

    private const val TAG = "FileOperationUtils"

    /**
     * Deletes a file from the given directory.
     * Returns true if deletion is successful, otherwise false.
     */
    fun deleteFile(directory: File, fileName: String): Boolean {
        val fileToDelete = File(directory, fileName)
        return if (fileToDelete.exists()) {
            val deleted = fileToDelete.delete()
            if (deleted) {
                Log.d(TAG, "File deleted: ${fileToDelete.absolutePath}")
            } else {
                Log.e(TAG, "Failed to delete file: ${fileToDelete.absolutePath}")
            }
            deleted
        } else {
            Log.w(TAG, "File not found for deletion: ${fileToDelete.absolutePath}")
            false
        }
    }

    /**
     * Lists all files present in the directory.
     */
    fun listFiles(directory: File): List<String> {
        return directory.listFiles()?.map { it.name }?.sorted() ?: emptyList()
    }

    /**
     * Moves or copies a file from one location to another.
     * Returns true if successful, otherwise false.
     */
    fun moveFile(sourceFile: File, destinationDir: File): Boolean {
        return try {
            if (!destinationDir.exists()) {
                destinationDir.mkdirs()
                Log.d(TAG, "Created directory: ${destinationDir.absolutePath}")
            }
            val destinationFile = File(destinationDir, sourceFile.name)
            sourceFile.copyTo(destinationFile, overwrite = true)
            val deleted = sourceFile.delete()
            if (deleted) {
                Log.d(TAG, "File moved from ${sourceFile.absolutePath} to ${destinationFile.absolutePath}")
                true
            } else {
                Log.e(TAG, "Failed to delete source file after copy: ${sourceFile.absolutePath}")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error moving file from ${sourceFile.absolutePath} to ${destinationDir.absolutePath}", e)
            false
        }
    }

    /**
     * Returns the size of a file in a human-readable format.
     */
    fun getFileSize(file: File): String {
        val fileSizeInBytes = file.length()
        val fileSizeInKB = fileSizeInBytes / 1024.0
        val fileSizeInMB = fileSizeInKB / 1024.0
        return when {
            fileSizeInMB >= 1 -> String.format("%.2f MB", fileSizeInMB)
            fileSizeInKB >= 1 -> String.format("%.2f KB", fileSizeInKB)
            else -> "$fileSizeInBytes bytes"
        }
    }
}
