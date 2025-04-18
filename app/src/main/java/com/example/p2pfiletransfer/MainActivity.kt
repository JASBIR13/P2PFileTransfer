package com.example.p2pfiletransfer

import android.Manifest
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.p2pfiletransfer.ui.theme.P2PFileTransferTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlinx.coroutines.delay
import androidx.compose.foundation.text.selection.SelectionContainer
import java.io.FileOutputStream
import java.io.InputStream
import java.net.Inet4Address
import java.net.NetworkInterface


class MainActivity : ComponentActivity() {



    private lateinit var server: SimpleWebServer
    private lateinit var clipboardManager: ClipboardManager
    private var isServerStarted = false




    // Permissions
    private val requiredPermissions = mutableListOf(
        Manifest.permission.INTERNET,
        Manifest.permission.ACCESS_WIFI_STATE,
        Manifest.permission.CHANGE_WIFI_STATE
    )

    private lateinit var fileSelectorLauncher: ActivityResultLauncher<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)


        // Request permissions based on SDK version
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
            requiredPermissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requiredPermissions.addAll(
                listOf(
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VIDEO,
                    Manifest.permission.READ_MEDIA_AUDIO
                )
            )
        }



        // Initialize Clipboard Manager
        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

        // Request necessary permissions
        requestPermissions()

        // Initialize file selector
        fileSelectorLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            if (uri != null) {
                lifecycleScope.launch {
                    try {
                        val file = getFileFromUriAsync(this@MainActivity, uri)
                        if (file != null && file.exists()) {
                            uploadFile(file)
                            updateSelectedFileName(file.name)
                        }
                    } catch (e: Exception) {
                        Toast.makeText(this@MainActivity, "Error selecting file: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }

        // Set up UI with Jetpack Compose
        setContent {
            P2PFileTransferTheme {
                MainContent()
            }
        }

        ContextCompat.registerReceiver(
            this,
            downloadReceiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
            if (downloadId != -1L) {
                Toast.makeText(context, "Download completed", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Updated method to update the file name after selection
    private var onFileSelectedCallback: ((String) -> Unit)? = null

    private fun updateSelectedFileName(fileName: String) {
        onFileSelectedCallback?.invoke(fileName)
    }

    /**
     * Main composable content function.
     */
    @Composable
    fun MainContent() {
        var serverStarted by remember { mutableStateOf(false) }
        var uploadProgress by remember { mutableStateOf(0f) }
        var serverUrl by remember { mutableStateOf("") }
        var selectedFileName by remember { mutableStateOf("") }
        var fileList by remember { mutableStateOf(listOf<String>()) }
        var downloadStatus by remember { mutableStateOf("") }
        var deleteStatus by remember { mutableStateOf("") }

        // Callback to update selected file name
        onFileSelectedCallback = { fileName -> selectedFileName = fileName }

        // Function to fetch files from the server
        fun fetchFiles() {
            if (!isServerStarted) {
                return
            }
            lifecycleScope.launch {
                // Now you can safely access server after checking it's started
                val files = server.getAvailableFiles()
                fileList = files // Update the file list state
            }
        }

        LaunchedEffect(serverStarted) {
            if (serverStarted) {
                while (serverStarted) {
                    fetchFiles()  // Fetch files continuously
                    delay(1000)  // Set a delay to periodically fetch files (1 seconds)
                }
            }
        }


            // Download file function
                fun downloadFile(fileName: String) {
                val ipAddress = getLocalIpAddress() ?: return
                val url = "http://$ipAddress:8080/files/$fileName"

                // Create an intent to open the URL in a browser
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    data = Uri.parse(url)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK // Ensures the browser starts in a new task
                }

                try {
                    // Start the browser activity to download the file
                    startActivity(intent)
                } catch (e: ActivityNotFoundException) {
                    // Handle the case where no browser is available
                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(this, "No browser found to download the file", Toast.LENGTH_SHORT).show()
                    }
                }
            }





        // Delete file function
        fun deleteFile(fileName: String) {
            lifecycleScope.launch {
                val success = server.deleteFile(fileName) // Implement deleteFile() in SimpleWebServer
                fetchFiles() // Refresh file list after deletion
            }
        }

        LaunchedEffect(Unit) {
            if (serverStarted) fetchFiles() // Fetch files when server starts
        }

        MainScreen(
            onStartServer = {
                startWebServer(
                    onServerUrl = { url -> serverUrl = url }
                )
                serverStarted = true
                fetchFiles() // Load the files when server starts
            },
            onStopServer = {
                stopWebServer()
                serverStarted = false
                serverUrl = ""
                selectedFileName = ""
                fileList = emptyList() // Clear the file list when server stops
            },
            onSelectFile = { fileSelectorLauncher.launch("*/*") },
            onFileSelected = { fileName -> selectedFileName = fileName },
            onFetchFiles = { fetchFiles() },
            onDownloadFile = { fileName -> downloadFile(fileName) },
            onDeleteFile = { fileName -> deleteFile(fileName) },
            serverStarted = serverStarted,
            fileList = fileList,
            serverUrl = serverUrl,
            downloadStatus = downloadStatus,
            deleteStatus = deleteStatus,
            selectedFileName = selectedFileName
        )
    }

    /**
     * Requests the necessary runtime permissions.
     */
    private fun requestPermissions() {
        val permissionsToRequest = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (permissionsToRequest.isNotEmpty()) {
            requestPermissionsLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }

    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val deniedPermissions = permissions.filter { !it.value }
        if (deniedPermissions.isNotEmpty()) {
            val permanentlyDeniedPermissions = deniedPermissions.keys.filter {
                !shouldShowRequestPermissionRationale(it)
            }

            if (permanentlyDeniedPermissions.isNotEmpty()) {
                Toast.makeText(this, "Permissions permanently denied: $permanentlyDeniedPermissions. Please enable them from settings.", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, "Permissions denied: ${deniedPermissions.keys}. App may not function correctly.", Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * Starts the web server asynchronously.
     */
    private fun startWebServer(onServerUrl: (String) -> Unit) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                val uploadDir = File(getExternalFilesDir(null), "uploads").apply {
                    if (!exists()) mkdirs()
                }
                server = SimpleWebServer(8080, uploadDir) { clipboardText ->
                    updateLocalClipboard(clipboardText)
                }
                try {
                    server.start()
                    val ipAddress = getLocalIpAddress()
                    val url = ipAddress?.let { "http://$it:8080/" } ?: "http://localhost:8080/"
                    //val url = "http://simple-transfer.local:8080/"
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "Server started", Toast.LENGTH_SHORT).show()
                        onServerUrl(url)
                    }
                    isServerStarted = true  // Mark the server as started
                } catch (e: Exception) {
                    Log.e("MainActivity", "Error starting server", e)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "Failed to start server: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }



    /**
     * Stops the web server asynchronously.
     */
    private fun stopWebServer() {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    server.stop()
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "Server stopped", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Log.e("MainActivity", "Error stopping server", e)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "Failed to stop server: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    /**
     * Uploads the selected file to the server asynchronously.
     */
    private fun uploadFile(file: File) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    moveFile(file, server.uploadDir)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "File uploaded: ${file.name}", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Log.e("MainActivity", "Error uploading file", e)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "Failed to upload file: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    /**
     * Updates the clipboard with the provided text.
     */
    private fun updateLocalClipboard(text: String) {
        val clipData = ClipData.newPlainText("P2P File Transfer", text)
        clipboardManager.setPrimaryClip(clipData)
        Toast.makeText(this, "Copied to clipboard: $text", Toast.LENGTH_SHORT).show()
    }

    fun getLocalIpAddress(): String? {
        return try {
            NetworkInterface.getNetworkInterfaces().asSequence()
                .flatMap { it.inetAddresses.asSequence() }
                .filter { !it.isLoopbackAddress && it is Inet4Address }
                .map { it.hostAddress }
                .firstOrNull()
        } catch (ex: Exception) {
            Log.e("IPADDRESS", "Error getting local IP address", ex)
            null
        }
    }

    /**
     * Converts the content URI to a file in the internal storage directory asynchronously.
     */
    suspend fun getFileFromUriAsync(context: Context, uri: Uri): File? {
        return withContext(Dispatchers.IO) {
            when (uri.scheme) {
                ContentResolver.SCHEME_CONTENT -> handleContentUri(context, uri)
                ContentResolver.SCHEME_FILE -> handleFileUri(uri)
                else -> {
                    Log.e("FileProviderUtils", "Unsupported URI scheme: ${uri.scheme}")
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
            Log.e("FileProviderUtils", "Failed to retrieve file name from URI: $uri")
            return null
        }

        val sanitizedFileName = sanitizeFileName(fileName)
        val uniqueFile = getUniqueFile(context, sanitizedFileName)

        // Write the content of the file
        try {
            val inputStream = contentResolver.openInputStream(uri)
            if (inputStream == null) {
                Log.e("FileProviderUtils", "InputStream is null for URI: $uri")
                return null
            }
            copyStreamToFile(inputStream, uniqueFile)
        } catch (e: Exception) {
            Log.e("FileProviderUtils", "Error copying file from URI: $uri", e)
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
            Log.e("FileProviderUtils", "Error handling file URI: $uri", e)
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
                    Log.e("FileProviderUtils", "Cursor did not move to first item for URI: $uri")
                }
            }
        } else {
            Log.e("FileProviderUtils", "Cursor is null for URI: $uri")
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

    fun moveFile(sourceFile: File, destinationDir: File): Boolean {
        return try {
            if (!destinationDir.exists()) {
                destinationDir.mkdirs()
                Log.d("FileOperationUtils", "Created directory: ${destinationDir.absolutePath}")
            }
            val destinationFile = File(destinationDir, sourceFile.name)
            sourceFile.copyTo(destinationFile, overwrite = true)
            val deleted = sourceFile.delete()
            if (deleted) {
                Log.d("FileOperationUtils", "File moved from ${sourceFile.absolutePath} to ${destinationFile.absolutePath}")
                true
            } else {
                Log.e("FileOperationUtils", "Failed to delete source file after copy: ${sourceFile.absolutePath}")
                false
            }
        } catch (e: Exception) {
            Log.e("FileOperationUtils", "Error moving file from ${sourceFile.absolutePath} to ${destinationDir.absolutePath}", e)
            false
        }
    }

    override fun onDestroy() {
        stopWebServer()  // Stop the server when the activity is destroyed
        super.onDestroy()
        unregisterReceiver(downloadReceiver)
    }
}

/**
 * Main screen composable function.
 */
@Composable
fun MainScreen(
    onStartServer: () -> Unit,
    onStopServer: () -> Unit,
    onSelectFile: () -> Unit,
    onFileSelected: (String) -> Unit,
    onFetchFiles: () -> Unit,
    onDownloadFile: (String) -> Unit,
    onDeleteFile: (String) -> Unit,
    serverStarted: Boolean,
    fileList: List<String>,
    serverUrl: String,
    downloadStatus: String,
    deleteStatus: String,
    selectedFileName: String
) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("P2P File Transfer", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(16.dp))

        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Button(onClick = { if (!serverStarted) onStartServer() },
                enabled = !serverStarted,
                colors = ButtonDefaults.buttonColors(
                    disabledContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f), // Lighten the color when disabled
                    disabledContentColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.5f) // Lighten the text color when disabled
                )) {
                Text("Start Server")
            }

            Button(onClick = { onStopServer() },
                enabled = serverStarted,
                colors = ButtonDefaults.buttonColors(
                    disabledContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f), // Lighten the color when disabled
                    disabledContentColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.5f) // Lighten the text color when disabled
                )) {
                Text("Stop Server")
            }
        }


        Spacer(modifier = Modifier.height(16.dp))

        if (selectedFileName.isNotEmpty()) {
            Text("Selected File: $selectedFileName", fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (serverStarted) {
                SelectionContainer {
                    Text("Copy Server Link: $serverUrl", fontWeight = FontWeight.Bold)
                }

            Spacer(modifier = Modifier.height(16.dp))

            // File List
            Text("Files on Server:", fontWeight = FontWeight.Bold)
            LazyColumn(modifier = Modifier.fillMaxHeight(0.8f)) {
                items(fileList) { fileName ->
                    FileItem(fileName, onDownloadFile, onDeleteFile)
                }
            }

            // Download & Delete statuses
            if (downloadStatus.isNotEmpty()) {
                Text(downloadStatus, color = MaterialTheme.colorScheme.primary)
            }
            if (deleteStatus.isNotEmpty()) {
                Text(deleteStatus, color = MaterialTheme.colorScheme.error)
            }

            Spacer(modifier = Modifier.height(16.dp))
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (serverStarted) {
            Button(onClick = { onSelectFile() }, modifier = Modifier.fillMaxWidth()) {
                Text("Select File to Upload")
            }
        }
    }
}

@Composable
fun FileItem(
    fileName: String,
    onDownloadFile: (String) -> Unit,
    onDeleteFile: (String) -> Unit
) {
    Row(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
        Text(fileName, fontSize = 16.sp, modifier = Modifier.weight(1f))
        Button(onClick = { onDownloadFile(fileName) }) {
            Text("Download")
        }
        Spacer(modifier = Modifier.width(8.dp))
        Button(onClick = { onDeleteFile(fileName) }, colors = ButtonDefaults.buttonColors(MaterialTheme.colorScheme.error)) {
            Text("Delete")
        }
    }
}
