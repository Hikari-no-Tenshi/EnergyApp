package com.mykolaiv.energy

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import com.rajat.pdfviewer.PdfRendererView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import androidx.core.net.toUri

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfViewerScreen(
    url: String,
    title: String,
    @Suppress("unused") navController: NavController,
    snackbarHostState: SnackbarHostState,
    onBackAction: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val messageDownloaded = stringResource(R.string.pdf_downloaded)

    BackHandler {
        onBackAction()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBackAction) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    // КНОПКА ЗАВАНТАЖЕННЯ
                    IconButton(onClick = {
                        // Використовуємо нашу функцію завантаження, яку ми писали раніше
                        scope.launch {
                            val uri = downloadPdfToPublicStorage(context, url)
                            if (uri != null) {
                                scope.launch {
                                    snackbarHostState.showSnackbar(
                                        message = messageDownloaded,
                                        duration = SnackbarDuration.Short
                                    )
                                }
                            }
                        }
                    }) {
                        Icon(imageVector = Icons.Default.Download, contentDescription = "Download")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            )
        }
    ) { padding ->
        AndroidView(
            factory = { context ->
                PdfRendererView(context).apply {
                    // Налаштування за замовчуванням
                }
            },
            update = { view ->
                view.initWithUrl(
                    url = url,
                    lifecycle = lifecycleOwner.lifecycle,
                    lifecycleCoroutineScope = lifecycleOwner.lifecycleScope
                )
            },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        )
    }
}

suspend fun downloadPdfToPublicStorage(context: Context, url: String): Uri? {
    return withContext(Dispatchers.IO) {
        try {
            val client = OkHttpClient()
            val request = Request.Builder().url(url).build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null

                val body = response.body
                val fileName = url.toUri().lastPathSegment ?: "Graph_Energy_${System.currentTimeMillis()}.pdf"

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val contentValues = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                        put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
                        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    }

                    val contentResolver = context.contentResolver
                    val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)

                    uri?.let { targetUri ->
                        contentResolver.openOutputStream(targetUri)?.use { output ->
                            body.byteStream().use { input -> input.copyTo(output) }
                        }
                        targetUri
                    }
                } else {
                    // Для старих версій (Android 7-9)
                    val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    if (!downloadsDir.exists()) downloadsDir.mkdirs()

                    val file = File(downloadsDir, fileName)
                    FileOutputStream(file).use { output ->
                        body.byteStream().use { input -> input.copyTo(output) }
                    }
                    Uri.fromFile(file)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}