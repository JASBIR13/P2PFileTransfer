package com.example.p2pfiletransfer

import android.Manifest
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
                        val file = FileProviderUtils.getFileFromUriAsync(this@MainActivity, uri)
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
                val ipAddress = IPAddressUtils.getLocalIpAddress() ?: return
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
                    val ipAddress = IPAddressUtils.getLocalIpAddress()
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
                    FileOperationUtils.moveFile(file, server.uploadDir)
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
