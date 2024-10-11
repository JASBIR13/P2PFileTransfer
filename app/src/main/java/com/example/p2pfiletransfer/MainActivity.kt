package com.example.p2pfiletransfer

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.p2pfiletransfer.ui.theme.P2PFileTransferTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {

    private lateinit var server: SimpleWebServer
    private lateinit var clipboardManager: ClipboardManager

    // Define required permissions
    private val requiredPermissions = mutableListOf(
        Manifest.permission.INTERNET,
        Manifest.permission.ACCESS_WIFI_STATE,
        Manifest.permission.CHANGE_WIFI_STATE
    )

    private lateinit var fileSelectorLauncher: ActivityResultLauncher<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Add permissions based on SDK version
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

        // Request necessary permissions at runtime
        requestPermissions()

        // Initialize the file selection activity result launcher
        fileSelectorLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            if (uri != null) {
                Log.d("MainActivity", "File URI received: $uri")
                lifecycleScope.launch {
                    try {
                        // Try to retrieve the file from the URI asynchronously
                        val file = FileProviderUtils.getFileFromUriAsync(this@MainActivity, uri)

                        if (file != null && file.exists()) {
                            Log.d("MainActivity", "File retrieved: ${file.absolutePath}")
                            uploadFile(file)
                            // Update the selected file name using the callback
                            updateSelectedFileName(file.name)
                        } else {
                            Log.e("MainActivity", "Failed to retrieve the file or file does not exist.")
                            Toast.makeText(this@MainActivity, "Failed to retrieve the file.", Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Exception) {
                        Log.e("MainActivity", "Error selecting file: ${e.message}", e)
                        Toast.makeText(this@MainActivity, "Error selecting file: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            } else {
                Log.d("MainActivity", "No file selected (URI is null).")
                Toast.makeText(this@MainActivity, "No file selected.", Toast.LENGTH_SHORT).show()
            }
        }

        // Set up the UI with Jetpack Compose
        setContent {
            P2PFileTransferTheme {
                MainContent()
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

        // Set the onFileSelectedCallback to update the state in MainContent
        onFileSelectedCallback = { fileName ->
            selectedFileName = fileName
        }

        MainScreen(
            onStartServer = {
                startWebServer(
                    onProgress = { progress -> uploadProgress = progress },
                    onServerUrl = { url -> serverUrl = url }
                )
                serverStarted = true
            },
            onStopServer = {
                stopWebServer()
                serverStarted = false
                serverUrl = ""
                selectedFileName = "" // Reset selected file name when server stops
            },
            onSelectFile = {
                // Launch the file selector
                fileSelectorLauncher.launch("*/*")
            },
            onFileSelected = { fileName ->
                // Update selected file name
                selectedFileName = fileName
            },
            serverStarted = serverStarted,
            uploadProgress = uploadProgress,
            serverUrl = serverUrl,
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
    private fun startWebServer(onProgress: (Float) -> Unit, onServerUrl: (String) -> Unit) {
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
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "Server started at $url", Toast.LENGTH_SHORT).show()
                        onServerUrl(url)
                    }
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
     * Updates the local clipboard with text received from the server.
     */
    private fun updateLocalClipboard(text: String) {
        runOnUiThread {
            val clip = ClipData.newPlainText("clipboard", text)
            clipboardManager.setPrimaryClip(clip)
            Toast.makeText(this, "Clipboard updated from server", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Clean up resources, e.g., stop server
        if (this::server.isInitialized) {
            server.stop()
        }
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
    onFileSelected: (String) -> Unit, // New callback to update file name
    serverStarted: Boolean,
    uploadProgress: Float,
    serverUrl: String,
    selectedFileName: String
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            text = "P2P File Transfer",
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.headlineMedium
        )
        Spacer(modifier = Modifier.height(16.dp))
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            Button(onClick = { if (!serverStarted) onStartServer() }) {
                Text(text = if (serverStarted) "Server Running" else "Start Server")
            }
            if (serverStarted) {
                Button(onClick = { onStopServer() }) {
                    Text("Stop Server")
                }
            }
        }
        Spacer(modifier = Modifier.height(16.dp))

        Button(onClick = { onSelectFile() }) {
            Text("Select File to Upload")
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (selectedFileName.isNotEmpty()) {
            Text(text = "Selected File: $selectedFileName", fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.height(16.dp))
        if (serverStarted) {
            Text(text = "Server URL: $serverUrl", fontWeight = FontWeight.Bold)
            Text(text = "Upload Progress: ${uploadProgress * 100}%", fontWeight = FontWeight.Bold)
        }
    }
}
