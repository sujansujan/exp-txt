package com.plaintextfiles.explorer

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.FileProvider
import com.plaintextfiles.explorer.data.FileSystemRepository
import com.plaintextfiles.explorer.ui.ExplorerViewModel
import com.plaintextfiles.explorer.ui.screens.ExplorerScreen
import com.plaintextfiles.explorer.ui.theme.PlainTextTheme
import java.io.File

class MainActivity : ComponentActivity() {

    private val viewModel: ExplorerViewModel by viewModels()

    private val legacyPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        viewModel.refreshAllPanes()
    }

    private val manageAllFilesLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        viewModel.refreshAllPanes()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (!viewModel.navigateBackOnSystemPress()) {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                        isEnabled = true
                    }
                }
            }
        )

        if (!viewModel.fileRepo.hasAllFilesAccess()) {
            requestAllFilesPermission()
        }

        handleIncomingShareIntent(intent)

        setContent {
            val state by viewModel.uiState.collectAsState()
            PlainTextTheme(themeMode = state.settings.themeMode) {
                ExplorerScreen(
                    viewModel = viewModel,
                    onRequestAllFilesPermission = { requestAllFilesPermission() },
                    onOpenExternalFile = { file -> openFileWithSystem(file) },
                    onShareExternalFile = { file -> shareFileWithSystem(file) }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingShareIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshPermissionState()
    }

    @Suppress("DEPRECATION")
    private fun handleIncomingShareIntent(incoming: Intent?) {
        val action = incoming?.action ?: return
        if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) {
            return
        }

        val collectedUris = linkedSetOf<Uri>()

        if (action == Intent.ACTION_SEND) {
            val singleUri: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                incoming.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                incoming.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
            }
            if (singleUri != null) {
                collectedUris.add(singleUri)
            }
        } else if (action == Intent.ACTION_SEND_MULTIPLE) {
            val multiUris: ArrayList<Uri>? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                incoming.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                incoming.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
            }
            if (multiUris != null) {
                collectedUris.addAll(multiUris)
            }
        }

        val clip = incoming.clipData
        if (clip != null) {
            for (i in 0 until clip.itemCount) {
                val u = clip.getItemAt(i)?.uri
                if (u != null) {
                    collectedUris.add(u)
                }
            }
        }

        val sharedText = incoming.getStringExtra(Intent.EXTRA_TEXT)
        incoming.action = Intent.ACTION_MAIN

        if (collectedUris.isNotEmpty() || !sharedText.isNullOrBlank()) {
            viewModel.onReceiveExternalShare(collectedUris.toList(), sharedText)
        }
    }

    private fun requestAllFilesPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                val intent = Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                manageAllFilesLauncher.launch(intent)
            }.onFailure {
                val fallback = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                manageAllFilesLauncher.launch(fallback)
            }
        } else {
            legacyPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                )
            )
        }
    }

    private fun openFileWithSystem(file: File) {
        runCatching {
            val uri = FileProvider.getUriForFile(
                this,
                "$packageName.fileprovider",
                file
            )
            val mime = FileSystemRepository.resolveMimeType(file.name, file.isDirectory)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                        Intent.FLAG_ACTIVITY_NEW_TASK
                )
            }
            startActivity(intent)
        }.onFailure {
            runCatching {
                val uri = FileProvider.getUriForFile(
                    this,
                    "$packageName.fileprovider",
                    file
                )
                val fallbackIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "*/*")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(fallbackIntent, "Open ${file.name}"))
            }.onFailure { err ->
                Toast.makeText(
                    this,
                    "No app found to open ${file.name}: ${err.message}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun shareFileWithSystem(file: File) {
        runCatching {
            val uri = FileProvider.getUriForFile(
                this,
                "$packageName.fileprovider",
                file
            )
            val mime = FileSystemRepository.resolveMimeType(file.name, file.isDirectory)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Share ${file.name}"))
        }.onFailure { err ->
            Toast.makeText(this, "Cannot share ${file.name}: ${err.message}", Toast.LENGTH_SHORT).show()
        }
    }
}
