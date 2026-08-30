package com.yourname.pdftoolkit.ui.screens
import com.yourname.pdftoolkit.util.safeLaunch

import com.yourname.pdftoolkit.R

import androidx.compose.ui.res.stringResource

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.rememberAsyncImagePainter
import com.yourname.pdftoolkit.domain.operations.*
import com.yourname.pdftoolkit.ui.components.FadingEdgeRow
import com.yourname.pdftoolkit.ui.components.SaveLocationSelector
import com.yourname.pdftoolkit.ui.components.ScanPreviewCard
import com.yourname.pdftoolkit.util.CropHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.Executors

/**
 * ViewModel for Scan to PDF Screen.
 */
class ScanToPdfViewModel : ViewModel() {
    private val _state = MutableStateFlow(ScanToPdfUiState())
    val state: StateFlow<ScanToPdfUiState> = _state.asStateFlow()
    
    fun addImage(uri: Uri) {
        val updatedImages = _state.value.selectedImages + uri
        _state.value = _state.value.copy(selectedImages = updatedImages)
    }
    
    fun removeImage(index: Int) {
        val updatedImages = _state.value.selectedImages.toMutableList()
        if (index in updatedImages.indices) {
            updatedImages.removeAt(index)
            _state.value = _state.value.copy(selectedImages = updatedImages)
        }
    }
    
    fun clearImages() {
        _state.value = _state.value.copy(selectedImages = emptyList())
    }
    
    fun setPageSize(size: ScanPageSize) {
        _state.value = _state.value.copy(pageSize = size)
    }
    
    /**
     * Store the settings the user just changed in the preview: for every page while
     * "apply to all" is on, otherwise only for the page they are looking at.
     */
    fun setAdjustments(uri: Uri, adjustments: ScanAdjustments) {
        val state = _state.value
        _state.value = if (state.applyToAll) {
            state.copy(adjustments = adjustments, perPageAdjustments = emptyMap())
        } else {
            state.copy(perPageAdjustments = state.perPageAdjustments + (uri to adjustments))
        }
    }
    
    fun setApplyToAll(applyToAll: Boolean) {
        val state = _state.value
        _state.value = state.copy(
            applyToAll = applyToAll,
            // Turning it back on makes the current page's settings the ones for all pages.
            perPageAdjustments = if (applyToAll) emptyMap() else state.perPageAdjustments
        )
    }
    
    fun setQuality(quality: ScanQuality) {
        _state.value = _state.value.copy(quality = quality)
    }
    
    fun setFileName(name: String) {
        _state.value = _state.value.copy(fileName = name)
    }
    
    fun setShowCamera(show: Boolean) {
        _state.value = _state.value.copy(showCamera = show)
    }
    
    /** Move a page one step towards the front or the back of the document. */
    fun moveImage(index: Int, offset: Int) {
        val images = _state.value.selectedImages.toMutableList()
        val target = index + offset
        if (index !in images.indices || target !in images.indices) return
        images.add(target, images.removeAt(index))
        _state.value = _state.value.copy(selectedImages = images)
    }
    
    fun replaceImage(index: Int, newUri: Uri) {
        val updatedImages = _state.value.selectedImages.toMutableList()
        if (index in updatedImages.indices) {
            updatedImages[index] = newUri
            _state.value = _state.value.copy(selectedImages = updatedImages)
        }
    }
    
    /** Build the PDF at a location the user picked in the system dialog. */
    fun createPdf(context: android.content.Context, outputUri: Uri) {
        buildPdf(context) { scanner, config, perPage, onProgress ->
            scanner.imagesToPdf(
                imageUris = _state.value.selectedImages,
                outputUri = outputUri,
                config = config,
                perPageAdjustments = perPage,
                progressCallback = onProgress
            )
        }
    }
    
    /** Build the PDF straight into the app's output folder, without a file dialog. */
    fun createPdfInDefaultFolder(context: android.content.Context) {
        buildPdf(context) { scanner, config, perPage, onProgress ->
            val output = com.yourname.pdftoolkit.util.OutputFolderManager
                .createOutputStream(context, _state.value.outputFileName())
                ?: return@buildPdf ScanToPdfResult(
                    success = false,
                    pagesScanned = 0,
                    errorMessage = "Cannot create output file"
                )
            
            output.outputStream.use { stream ->
                scanner.imagesToPdf(
                    imageUris = _state.value.selectedImages,
                    outputStream = stream,
                    config = config,
                    perPageAdjustments = perPage,
                    progressCallback = onProgress
                ).copy(outputUri = output.outputFile.contentUri)
            }
        }
    }
    
    private fun buildPdf(
        context: android.content.Context,
        save: suspend (
            scanner: PdfScanner,
            config: ScanConfig,
            perPageAdjustments: List<ScanAdjustments>,
            onProgress: (Int) -> Unit
        ) -> ScanToPdfResult
    ) {
        if (_state.value.selectedImages.isEmpty()) return
        
        if (_state.value.isProcessing) return
        viewModelScope.launch {
            _state.value = _state.value.copy(isProcessing = true, progress = 0, error = null)
            
            val scanner = PdfScanner(context)
            val config = ScanConfig(
                pageSize = _state.value.pageSize,
                quality = _state.value.quality,
                adjustments = _state.value.adjustments
            )
            
            val result = save(
                scanner,
                config,
                _state.value.selectedImages.map { _state.value.adjustmentsFor(it) }
            ) { progress ->
                _state.value = _state.value.copy(progress = progress)
            }
            val outputUri = result.outputUri
            
            if (result.success && outputUri != null) {
                com.yourname.pdftoolkit.data.SafUriManager.addRecentFile(context, outputUri)
                
                // Record in history
                com.yourname.pdftoolkit.data.HistoryManager.recordSuccess(
                    context = context,
                    operationType = com.yourname.pdftoolkit.data.OperationType.SCAN_TO_PDF,
                    inputFileName = "${_state.value.selectedImages.size} images",
                    outputFileUri = outputUri,
                    outputFileName = _state.value.outputFileName(),
                    details = "Scanned ${result.pagesScanned} pages to PDF"
                )
            } else {
                // Record failure in history
                com.yourname.pdftoolkit.data.HistoryManager.recordFailure(
                    context = context,
                    operationType = com.yourname.pdftoolkit.data.OperationType.SCAN_TO_PDF,
                    inputFileName = "${_state.value.selectedImages.size} images",
                    errorMessage = result.errorMessage
                )
            }

            _state.value = _state.value.copy(
                isProcessing = false,
                isComplete = result.success,
                error = result.errorMessage,
                pagesScanned = result.pagesScanned,
                resultUri = if (result.success) outputUri else null
            )
        }
    }
    
    fun reset() {
        _state.value = ScanToPdfUiState()
    }
}

data class ScanToPdfUiState(
    val selectedImages: List<Uri> = emptyList(),
    val showCamera: Boolean = false,
    val pageSize: ScanPageSize = ScanPageSize.A4,
    val quality: ScanQuality = ScanQuality.MEDIUM,
    /** Name of the document being built; the user can edit it before saving. */
    val fileName: String = defaultScanFileName(),
    /** Settings for every page without an entry in [perPageAdjustments]. */
    val adjustments: ScanAdjustments = ScanAdjustments(),
    val perPageAdjustments: Map<Uri, ScanAdjustments> = emptyMap(),
    val applyToAll: Boolean = true,
    val isProcessing: Boolean = false,
    val progress: Int = 0,
    val isComplete: Boolean = false,
    val error: String? = null,
    val pagesScanned: Int = 0,
    val resultUri: Uri? = null
) {
    fun adjustmentsFor(uri: Uri): ScanAdjustments = perPageAdjustments[uri] ?: adjustments

    /** The file name, made safe to write: no path separators, always a .pdf. */
    fun outputFileName(): String {
        val cleaned = fileName
            .replace(Regex("""[\\/:*?"<>|]"""), "_")
            .trim()
            .ifBlank { defaultScanFileName() }
        return if (cleaned.endsWith(".pdf", ignoreCase = true)) cleaned else "$cleaned.pdf"
    }
}

/** Default document name, e.g. `260830_141205_pdf_toolkit.pdf`. */
private fun defaultScanFileName(): String {
    val stamp = java.text.SimpleDateFormat("yyMMdd_HHmmss", java.util.Locale.US)
        .format(java.util.Date())
    return "${stamp}_pdf_toolkit.pdf"
}

/**
 * Scan to PDF Screen - Capture photos and convert to PDF.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanToPdfScreen(
    onNavigateBack: () -> Unit,
    viewModel: ScanToPdfViewModel = viewModel()
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val state by viewModel.state.collectAsState()
    val scope = rememberCoroutineScope()
    
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
        if (granted) {
            viewModel.setShowCamera(true)
        }
    }
    
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        uris.forEach { uri -> viewModel.addImage(uri) }
    }
    
    val saveDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri ->
        uri?.let { viewModel.createPdf(context, it) }
    }
    
    // Crop state
    var cropImageIndex by remember { mutableStateOf(-1) }
    
    // Where the finished PDF goes: the app's folder, or a place the user picks
    var useCustomLocation by remember { mutableStateOf(false) }
    
    // Crop launcher
    val cropLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val croppedUri = CropHelper.getResultUri(result.resultCode, result.data)
            if (croppedUri != null && cropImageIndex >= 0 && cropImageIndex < state.selectedImages.size) {
                viewModel.replaceImage(cropImageIndex, croppedUri)
            }
        }
        cropImageIndex = -1
    }
    
    if (state.showCamera && hasCameraPermission) {
        CameraScreen(
            onImageCaptured = { uri ->
                viewModel.addImage(uri)
                viewModel.setShowCamera(false)
            },
            onOpenGallery = { imagePickerLauncher.safeLaunch(arrayOf("image/*"), context) },
            onClose = { viewModel.setShowCamera(false) }
        )
    } else {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.tool_scan_to_pdf)) },
                    navigationIcon = {
                        IconButton(onClick = onNavigateBack) {
                            Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.action_back))
                        }
                    }
                )
            }
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Image Source Selection
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.scan_add_images),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    if (hasCameraPermission) {
                                        viewModel.setShowCamera(true)
                                    } else {
                                        permissionLauncher.safeLaunch(Manifest.permission.CAMERA, context)
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.CameraAlt, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(stringResource(R.string.scan_camera))
                            }
                            
                            OutlinedButton(
                                onClick = { imagePickerLauncher.safeLaunch(arrayOf("image/*"), context) },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Image, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(stringResource(R.string.scan_gallery))
                            }
                        }
                    }
                }
                
                // Selected Images
                if (state.selectedImages.isNotEmpty()) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = stringResource(R.string.scan_pages_count, state.selectedImages.size),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                TextButton(onClick = { viewModel.clearImages() }) {
                                    Text(stringResource(R.string.action_clear_all))
                                }
                            }
                            
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(state.selectedImages.size) { index ->
                                  Column(
                                      horizontalAlignment = Alignment.CenterHorizontally
                                  ) {
                                    Box(
                                        modifier = Modifier
                                            .size(100.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .clickable {
                                                // Tap to crop
                                                cropImageIndex = index
                                                val cropIntent = CropHelper.getCropIntent(
                                                    context = context,
                                                    sourceUri = state.selectedImages[index],
                                                    aspectRatio = null,
                                                    maxSize = 2048
                                                )
                                                cropLauncher.safeLaunch(cropIntent, context)
                                            }
                                    ) {
                                        Image(
                                            painter = rememberAsyncImagePainter(state.selectedImages[index]),
                                            contentDescription = stringResource(R.string.cd_page_number, index + 1),
                                            modifier = Modifier.fillMaxSize(),
                                            contentScale = ContentScale.Crop
                                        )
                                        
                                        // Page number badge
                                        Badge(
                                            modifier = Modifier
                                                .align(Alignment.TopStart)
                                                .padding(4.dp)
                                        ) {
                                            Text("${index + 1}")
                                        }
                                        
                                        // Crop button
                                        IconButton(
                                            onClick = {
                                                cropImageIndex = index
                                                val cropIntent = CropHelper.getCropIntent(
                                                    context = context,
                                                    sourceUri = state.selectedImages[index],
                                                    aspectRatio = null,
                                                    maxSize = 2048
                                                )
                                                cropLauncher.safeLaunch(cropIntent, context)
                                            },
                                            modifier = Modifier
                                                .align(Alignment.BottomEnd)
                                                .size(24.dp)
                                                .background(
                                                    MaterialTheme.colorScheme.primary,
                                                    CircleShape
                                                )
                                        ) {
                                            Icon(
                                                Icons.Default.Crop,
                                                contentDescription = stringResource(R.string.action_crop),
                                                tint = MaterialTheme.colorScheme.onPrimary,
                                                modifier = Modifier.size(14.dp)
                                            )
                                        }
                                        
                                        // Delete button
                                        IconButton(
                                            onClick = { viewModel.removeImage(index) },
                                            modifier = Modifier
                                                .align(Alignment.TopEnd)
                                                .size(24.dp)
                                                .background(
                                                    MaterialTheme.colorScheme.error,
                                                    CircleShape
                                                )
                                        ) {
                                            Icon(
                                                Icons.Default.Close,
                                                contentDescription = stringResource(R.string.action_remove),
                                                tint = MaterialTheme.colorScheme.onError,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                    
                                    // Page order — matters as much for a stack of photos as
                                    // for camera captures.
                                    if (state.selectedImages.size > 1) {
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            IconButton(
                                                onClick = { viewModel.moveImage(index, -1) },
                                                enabled = index > 0,
                                                modifier = Modifier.size(28.dp)
                                            ) {
                                                Icon(
                                                    Icons.Default.ChevronLeft,
                                                    contentDescription = stringResource(R.string.scan_move_page_left),
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }
                                            IconButton(
                                                onClick = { viewModel.moveImage(index, 1) },
                                                enabled = index < state.selectedImages.lastIndex,
                                                modifier = Modifier.size(28.dp)
                                            ) {
                                                Icon(
                                                    Icons.Default.ChevronRight,
                                                    contentDescription = stringResource(R.string.scan_move_page_right),
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }
                                        }
                                    }
                                  }
                                }
                            }
                        }
                    }
                }
                
                // Live preview of the first/selected page with the current settings
                if (state.selectedImages.isNotEmpty()) {
                    ScanPreviewCard(
                        images = state.selectedImages,
                        adjustmentsFor = { state.adjustmentsFor(it) },
                        onAdjustmentsChange = { uri, adjustments ->
                            viewModel.setAdjustments(uri, adjustments)
                        },
                        applyToAll = state.applyToAll,
                        onApplyToAllChange = { viewModel.setApplyToAll(it) },
                        pageAspectRatio = if (state.pageSize == ScanPageSize.FIT_IMAGE) {
                            null
                        } else {
                            state.pageSize.rect.width / state.pageSize.rect.height
                        }
                    )
                }
                
                // Scan Settings
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.action_settings),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        
                        // Page Size
                        Text(stringResource(R.string.scan_page_size), style = MaterialTheme.typography.bodyMedium)
                        FadingEdgeRow {
                            items(ScanPageSize.entries) { size ->
                                FilterChip(
                                    selected = state.pageSize == size,
                                    onClick = { viewModel.setPageSize(size) },
                                    label = { Text(size.displayName.split(" ").first()) }
                                )
                            }
                        }
                        
                        // Quality
                        Text(stringResource(R.string.label_quality), style = MaterialTheme.typography.bodyMedium)
                        FadingEdgeRow {
                            items(ScanQuality.entries) { quality ->
                                FilterChip(
                                    selected = state.quality == quality,
                                    onClick = { viewModel.setQuality(quality) },
                                    label = { Text(quality.name) }
                                )
                            }
                        }
                        
                    }
                }
                
                // Processing State
                AnimatedVisibility(visible = state.isProcessing) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CircularProgressIndicator()
                            Text(stringResource(R.string.scan_creating_pdf, state.progress))
                            LinearProgressIndicator(
                                progress = state.progress / 100f,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
                
                // Success State
                AnimatedVisibility(visible = state.isComplete && !state.isProcessing) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.tertiary
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    stringResource(R.string.scan_pdf_created),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    stringResource(R.string.scan_pages_scanned, state.pagesScanned),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }
                }
                
                // Open PDF Button (shown after success)
                if (state.isComplete && state.resultUri != null) {
                    Button(
                        onClick = {
                            scope.launch(Dispatchers.IO) {
                                com.yourname.pdftoolkit.util.FileOpener.openPdf(context, state.resultUri!!)
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.OpenInNew, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.action_open_pdf))
                    }
                    
                    OutlinedButton(
                        onClick = {
                            scope.launch(Dispatchers.IO) {
                                com.yourname.pdftoolkit.util.FileOpener.shareFile(
                                    context = context,
                                    uri = state.resultUri!!,
                                    mimeType = "application/pdf",
                                    title = state.outputFileName(),
                                    fileName = state.outputFileName()
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.action_share))
                    }
                }
                
                // Error State
                state.error?.let { error ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Error,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(error, color = MaterialTheme.colorScheme.onErrorContainer)
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // File name and save location
                if (state.selectedImages.isNotEmpty()) {
                    OutlinedTextField(
                        value = state.fileName,
                        onValueChange = { viewModel.setFileName(it) },
                        label = { Text(stringResource(R.string.scan_file_name)) },
                        singleLine = true,
                        trailingIcon = {
                            Icon(Icons.Default.Edit, contentDescription = null)
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    SaveLocationSelector(
                        useCustomLocation = useCustomLocation,
                        onUseCustomLocationChange = { useCustomLocation = it }
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }
                
                // Create PDF Button
                Button(
                    onClick = {
                        if (useCustomLocation) {
                            saveDocumentLauncher.safeLaunch(state.outputFileName(), context)
                        } else {
                            viewModel.createPdfInDefaultFolder(context)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = state.selectedImages.isNotEmpty() && !state.isProcessing
                ) {
                    Icon(Icons.Default.PictureAsPdf, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.scan_create_pdf))
                }
                
                // Reset Button
                if (state.isComplete) {
                    OutlinedButton(
                        onClick = { viewModel.reset() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.scan_more_documents))
                    }
                }
            }
        }
    }
}

/**
 * Camera preview screen for capturing images.
 */
@Composable
private fun CameraScreen(
    onImageCaptured: (Uri) -> Unit,
    onOpenGallery: () -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    
    var imageCapture: ImageCapture? by remember { mutableStateOf(null) }
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    
    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                PreviewView(ctx).apply {
                    val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                    
                    cameraProviderFuture.addListener({
                        val cameraProvider = cameraProviderFuture.get()
                        
                        val preview = Preview.Builder().build().also {
                            it.setSurfaceProvider(surfaceProvider)
                        }
                        
                        imageCapture = ImageCapture.Builder()
                            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                            .build()
                        
                        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                        
                        try {
                            cameraProvider.unbindAll()
                            cameraProvider.bindToLifecycle(
                                lifecycleOwner,
                                cameraSelector,
                                preview,
                                imageCapture
                            )
                        } catch (e: Exception) {
                            // Handle camera binding error
                        }
                    }, ContextCompat.getMainExecutor(ctx))
                }
            },
            modifier = Modifier.fillMaxSize()
        )
        
        // Controls
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(32.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Gallery shortcut — pick shots that are already on the phone
            FloatingActionButton(
                onClick = onOpenGallery,
                containerColor = MaterialTheme.colorScheme.surface
            ) {
                Icon(
                    Icons.Default.PhotoLibrary,
                    contentDescription = stringResource(R.string.scan_gallery)
                )
            }
            
            // Capture button
            FloatingActionButton(
                onClick = {
                    val photoFile = File(
                        context.cacheDir,
                        "capture_${System.currentTimeMillis()}.jpg"
                    )
                    
                    val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()
                    
                    imageCapture?.takePicture(
                        outputOptions,
                        cameraExecutor,
                        object : ImageCapture.OnImageSavedCallback {
                            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                                onImageCaptured(Uri.fromFile(photoFile))
                            }
                            
                            override fun onError(exception: ImageCaptureException) {
                                // Handle capture error
                            }
                        }
                    )
                },
                containerColor = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(72.dp)
            ) {
                Icon(
                    Icons.Default.CameraAlt,
                    contentDescription = stringResource(R.string.cd_capture),
                    modifier = Modifier.size(32.dp)
                )
            }
            
            // Close button
            FloatingActionButton(
                onClick = onClose,
                containerColor = MaterialTheme.colorScheme.surface
            ) {
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_close))
            }
        }
    }
}
