package com.example.p2pfiletransfer

import android.util.Log
import com.google.gson.Gson
import fi.iki.elonen.NanoHTTPD
import java.io.*

class SimpleWebServer(port: Int, val uploadDir: File, private val onClipboardUpdate: (String) -> Unit) : NanoHTTPD(port) {

    companion object {
        private const val TAG = "SimpleWebServer"
    }

    @Volatile
    var serverIsAlive: Boolean = false // Renamed property to avoid conflict
        private set

    override fun start() {
        super.start(SOCKET_READ_TIMEOUT, false)
        serverIsAlive = true // Updated reference to renamed property
        Log.d(TAG, "Server started on port $listeningPort")
    }


    override fun stop() {
        super.stop()
        serverIsAlive = false // Updated reference to renamed property
        Log.d(TAG, "Server stopped")
    }


    override fun serve(session: IHTTPSession?): Response {
        val uri = session?.uri ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Invalid request")
        val method = session.method

        Log.d(TAG, "Received request: $method $uri")

        return when {
            uri == "/" && method == Method.GET -> serveFileList()
            uri.startsWith("/files/") && method == Method.GET -> serveFile(uri.substringAfter("/files/"))
            uri == "/upload" && method == Method.POST -> handleFileUpload(session)
            uri.startsWith("/delete/") && method == Method.POST -> deleteFile(uri.substringAfter("/delete/"))
            uri == "/clipboard" && method == Method.GET -> serveClipboard()
            uri == "/clipboard" && method == Method.POST -> handleClipboardUpdate(session)
            uri == "/api/files" && method == Method.GET -> serveFileListJson() // New API endpoint
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "File not found")
        }
    }


    private var clipboardText: String = ""

    private fun serveFileListJson(): Response {
        val files = uploadDir.listFiles()?.map { it.name } ?: emptyList()
        val json = Gson().toJson(files) // Serialize the file list to JSON

        val response = newFixedLengthResponse(Response.Status.OK, "application/json", json)
        response.addHeader("Content-Type", "application/json")
        // Optional: Disable caching to ensure the latest data is fetched
        response.addHeader("Cache-Control", "no-cache, no-store, must-revalidate")
        response.addHeader("Pragma", "no-cache")
        response.addHeader("Expires", "0")
        return response
    }

    /**
     * Serves the HTML page listing uploaded files and clipboard information.
     */
    private fun serveFileList(): Response {
        val html = StringBuilder(
            """
    <html>
    <head>
        <title>Uploaded Files</title>
        <style>
            body {
                font-family: 'Arial', sans-serif;
                margin: 20px;
                background-color: #f0f4f8;
                color: #333;
                animation: fadeIn 0.8s ease-in-out;
            }
            h1 {
                color: #0077cc;
                font-size: 28px;
                margin-bottom: 20px;
            }
            h3 {
                color: #005f99;
                font-size: 22px;
                margin-bottom: 10px;
            }
            a {
                text-decoration: none;
                color: #0066cc;
                transition: color 0.3s ease;
            }
            a:hover {
                text-decoration: underline;
                color: #003d66;
            }
            .file-list {
                margin: 20px 0;
                padding-left: 0;
                list-style-type: none;
            }
            .file-list li {
                margin: 10px 0;
                background: #fff;
                padding: 10px;
                border-radius: 8px;
                box-shadow: 0 4px 8px rgba(0, 0, 0, 0.1);
                animation: fadeInUp 0.4s ease-out;
            }
            button {
                color: red;
                background: none;
                border: none;
                cursor: pointer;
                transition: transform 0.2s ease, color 0.3s ease;
            }
            button:hover {
                transform: scale(1.05);
                color: darkred;
            }
            input[type="submit"] {
                background-color: #0077cc;
                color: white;
                padding: 8px 16px;
                border: none;
                border-radius: 5px;
                cursor: pointer;
                transition: background-color 0.3s ease;
            }
            input[type="submit"]:hover {
                background-color: #005f99;
            }
            textarea {
                width: 100%;
                padding: 10px;
                border-radius: 8px;
                border: 1px solid #ccc;
                transition: border-color 0.3s ease;
            }
            textarea:focus {
                border-color: #0077cc;
            }
            
        </style>
        <script>
            // Function to fetch and update the file list
            async function updateFileList() {
                try {
                    const response = await fetch('/api/files');
                    if (!response.ok) {
                        console.error('Failed to fetch file list:', response.statusText);
                        return;
                    }
                    const files = await response.json();
                    const fileList = document.getElementById('file-list');
                    fileList.innerHTML = ''; // Clear existing list

                    files.forEach(fileName => {
                        const encodedFileName = encodeURIComponent(fileName);
                        const listItem = document.createElement('li');

                        const fileLink = document.createElement('a');
                        fileLink.href = "/files/" + encodedFileName;
                        fileLink.textContent = fileName;
                        listItem.appendChild(fileLink);

                        listItem.appendChild(document.createTextNode(' - '));

                        const deleteForm = document.createElement('form');
                        deleteForm.action = "/delete/" + encodedFileName;
                        deleteForm.method = 'post';
                        deleteForm.style.display = 'inline';

                        const deleteButton = document.createElement('button');
                        deleteButton.type = 'submit';
                        deleteButton.textContent = 'Delete';
                        deleteForm.appendChild(deleteButton);

                        listItem.appendChild(deleteForm);
                        fileList.appendChild(listItem);
                    });
                } catch (error) {
                    console.error('Error updating file list:', error);
                }
            }

            // Initial load and periodic updates every 5 seconds
            window.onload = function() {
                updateFileList();
                setInterval(updateFileList, 1000); // Update every 5 seconds
            };
        </script>
    </head>
    <body>
        <h1>Uploaded Files</h1>
        <ul class="file-list" id="file-list">
            <!-- The file list will be dynamically populated by JavaScript -->
        </ul>
        <h3>Upload New File</h3>
        <form action="/upload" method="post" enctype="multipart/form-data">
            <input type="file" name="file" />
            <input type="submit" value="Upload" />
        </form>
        <h3>Clipboard</h3>
        <form action="/clipboard" method="post">
            <textarea name="clipboard" rows="4" cols="50" placeholder="Paste text to synchronize clipboard"></textarea>
            <br/>
            <input type="submit" value="Update Clipboard" />
        </form>
        <p>Current Clipboard: ${escapeHtml(clipboardText)}</p>
    </body>
    </html>
    """
        )

        return newFixedLengthResponse(Response.Status.OK, "text/html", html.toString())
    }







    /**
     * Serves an individual file for download.
     */
    private fun serveFile(fileName: String): Response {
        if (!isValidFileName(fileName)) {
            return newFixedLengthResponse(Response.Status.FORBIDDEN, "text/plain", "Invalid file path")
        }

        val file = File(uploadDir, fileName)
        return if (file.exists()) {
            val mimeType = getMimeType(fileName)
            newChunkedResponse(Response.Status.OK, mimeType, file.inputStream())
        } else {
            newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "File not found")
        }
    }

    fun getAvailableFiles(): List<String> {
        val files = uploadDir.listFiles()?.map { it.name } ?: emptyList()
        Log.d(TAG, "Available files: $files")
        return files
    }



    /**
     * Handles file uploads via POST requests.
     */
    private fun handleFileUpload(session: IHTTPSession): Response {
        return try {
            val files = mutableMapOf<String, String>()
            session.parseBody(files)

            val fileNames = session.parameters["file"]
            if (fileNames.isNullOrEmpty()) {
                return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "File parameter is missing")
            }
            val fileName = fileNames.first()
            if (!isValidFileName(fileName)) {
                return newFixedLengthResponse(Response.Status.FORBIDDEN, "text/plain", "Invalid file name")
            }

            val tempFilePath = files["file"] ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "File data is missing")
            val uploadedFile = File(tempFilePath)
            val destFile = File(uploadDir, fileName)

            uploadedFile.copyTo(destFile, overwrite = true)
            uploadedFile.delete()

            Log.d(TAG, "File uploaded: $fileName")

            // HTML Response to show a toast without redirection


            return newFixedLengthResponse(Response.Status.NO_CONTENT, "text/html", "File Uploaded Successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error handling file upload", e)
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "An error occurred during file upload")
        }
    }




    /**
     * Handles file deletion via POST requests.
     */
    fun deleteFile(fileName: String): Response {
        return try {
            if (!isValidFileName(fileName)) {
                return newFixedLengthResponse(Response.Status.FORBIDDEN, "text/plain", "Invalid file name")
            }

            val file = File(uploadDir, fileName)
            if (file.exists()) {
                val deleted = file.delete()
                if (deleted) {
                    Log.d(TAG, "File deleted: $fileName")
                    newFixedLengthResponse(Response.Status.NO_CONTENT, "text/plain", "File deleted successfully: $fileName")
                } else {
                    Log.e(TAG, "Failed to delete file: $fileName")
                    newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Failed to delete file: $fileName")
                }
            } else {
                newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "File not found")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting file", e)
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "An error occurred while deleting the file")
        }
    }

    /**
     * Serves the current clipboard content.
     */
    private fun serveClipboard(): Response {
        val escapedClipboard = escapeHtml(clipboardText)
        val responseText = """
            <html>
            <body>
                <h3>Clipboard</h3>
                <p>Current Clipboard: $escapedClipboard</p>
            </body>
            </html>
        """.trimIndent()
        return newFixedLengthResponse(Response.Status.OK, "text/html", responseText)
    }

    /**
     * Handles clipboard updates via POST requests.
     */
    private fun handleClipboardUpdate(session: IHTTPSession): Response {
        return try {
            session.parseBody(mutableMapOf())

            val params = session.parameters["clipboard"] ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Clipboard parameter is missing")

            val newClipboardText = params.firstOrNull()?.trim() ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Clipboard text is empty")

            clipboardText = newClipboardText
            onClipboardUpdate(newClipboardText)

            Log.d(TAG, "Clipboard updated: $clipboardText")

            newFixedLengthResponse(Response.Status.OK, "text/plain", "Clipboard updated successfully.")
        } catch (e: Exception) {
            Log.e(TAG, "Error handling clipboard update", e)
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "An error occurred during clipboard update")
        }
    }

    /**
     * Validates the file name to prevent directory traversal attacks.
     */
    private fun isValidFileName(fileName: String): Boolean {
        return !fileName.contains("..") && !fileName.contains(File.separator)
    }

    /**
     * Escapes HTML characters to prevent injection attacks.
     */
    private fun escapeHtml(input: String): String {
        return input.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
    }

    /**
     * Determines the MIME type based on the file extension.
     */
    private fun getMimeType(fileName: String): String {
        val extension = fileName.substringAfterLast('.', "")
        return when (extension.lowercase()) {
            "html", "htm" -> "text/html"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "pdf" -> "application/pdf"
            "txt" -> "text/plain"
            "mp3" -> "audio/mpeg"
            "mp4" -> "video/mp4"
            else -> "application/octet-stream"
        }
    }

}
