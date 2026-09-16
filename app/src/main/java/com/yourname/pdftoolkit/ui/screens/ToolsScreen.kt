package com.yourname.pdftoolkit.ui.screens
import com.yourname.pdftoolkit.util.safeLaunch

import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yourname.pdftoolkit.BuildConfig
import com.yourname.pdftoolkit.R
import com.yourname.pdftoolkit.data.SafUriManager
import com.yourname.pdftoolkit.data.ToolPreferences
import com.yourname.pdftoolkit.data.ToolPrefs
import com.yourname.pdftoolkit.ui.navigation.Screen
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Categories of the tool list, in the order they appear on the home screen.
 *
 * The order follows what people open the app for: making a PDF out of what is in front of them,
 * then working on the document they already have, then getting things out of it, then reading
 * it, and finally the occasional jobs.
 */
enum class ToolSection {
    CREATE,
    DOCUMENT,
    EXPORT,
    VIEW,
    PROTECT,
    IMAGES
}

/**
 * Data class representing a PDF/Image tool.
 */
data class ToolItem(
    val id: String,
    val titleResId: Int,
    val descResId: Int,
    val icon: ImageVector,
    val section: ToolSection,
    val screen: Screen
) {
    @Composable
    fun getTitle(): String = stringResource(titleResId)

    @Composable
    fun getDescription(): String = stringResource(descResId)
}

/**
 * Tools Screen - the home screen.
 *
 * Pinned tools come first, then the ones just used, then the categories, and last a collapsed
 * section for everything the user has hidden. Long-pressing a row pins or hides it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolsScreen(
    onNavigateToScreen: (Screen) -> Unit,
    onNavigateToRoute: ((String) -> Unit)? = null,
    onOpenPdfViewer: (Uri, String) -> Unit = { _, _ -> }
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    /**
     * Copy content URI to app cache for reliable access.
     * This is critical for picker URIs that lose permission quickly.
     */
    suspend fun copyUriToCache(context: android.content.Context, uri: Uri): Uri? = withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            val cacheDir = File(context.cacheDir, "viewer_cache")
            if (!cacheDir.exists()) cacheDir.mkdirs()

            val tempFile = File(cacheDir, "pdf_${System.currentTimeMillis()}.pdf")

            // Try to copy the file
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            } ?: return@withContext null

            // Return file:// URI for direct file access
            Uri.fromFile(tempFile)
        } catch (e: Exception) {
            android.util.Log.e("ToolsScreen", "Failed to copy URI to cache", e)
            null
        }
    }

    /**
     * PDF picker using SAF (ACTION_OPEN_DOCUMENT).
     * Immediately copies picked file to cache before opening to avoid permission issues.
     */
    val pdfPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { selectedUri ->
            scope.launch {
                // Take persistable URI permission immediately
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                val persistedFile = SafUriManager.addRecentFile(context, selectedUri, flags)

                val name = persistedFile?.name?.substringBeforeLast('.') ?: run {
                    var displayName = "PDF Document"
                    context.contentResolver.query(selectedUri, null, null, null, null)?.use { c ->
                        if (c.moveToFirst()) {
                            val nameIndex = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            if (nameIndex >= 0) {
                                displayName = c.getString(nameIndex)?.substringBeforeLast('.') ?: displayName
                            }
                        }
                    }
                    displayName
                }

                // CRITICAL: Copy to cache before opening to avoid permission expiration
                val cachedUri = copyUriToCache(context, selectedUri)
                if (cachedUri != null) {
                    onOpenPdfViewer(cachedUri, name)
                } else {
                    // Fallback to direct URI if copy fails
                    onOpenPdfViewer(selectedUri, name)
                }
            }
        }
    }

    val allTools = getAllTools()
    val toolsById = remember(allTools) { allTools.associateBy { it.id } }
    val prefs by ToolPreferences.flow(context).collectAsState(initial = ToolPrefs())

    var hiddenExpanded by remember { mutableStateOf(false) }
    var menuToolId by remember { mutableStateOf<String?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    val trimmedQuery = searchQuery.trim()
    // Derived so typing only recomputes the filtered list instead of
    // re-running string lookups for every row on each keystroke.
    val searchResults by remember(trimmedQuery, allTools) {
        derivedStateOf {
            if (trimmedQuery.isEmpty()) {
                emptyList()
            } else {
                allTools.filter { tool ->
                    context.getString(tool.titleResId).contains(trimmedQuery, ignoreCase = true) ||
                        context.getString(tool.descResId).contains(trimmedQuery, ignoreCase = true)
                }
            }
        }
    }

    val hiddenTools = prefs.hidden.mapNotNull { toolsById[it] }
    val favoriteTools = prefs.favorites.mapNotNull { toolsById[it] }
        .filter { it.id !in prefs.hidden }
    val recentTools = prefs.recent.mapNotNull { toolsById[it] }
        .filter { it.id !in prefs.hidden && it.id !in prefs.favorites }
        .take(4)

    fun openTool(tool: ToolItem) {
        scope.launch { ToolPreferences.recordUse(context, tool.id) }

        if (tool.screen == Screen.Home && tool.id == "view_pdf") {
            // Special handling for View PDF
            pdfPickerLauncher.safeLaunch(arrayOf("application/pdf"), context)
            return
        }

        // Check if this is an image tool that needs special routing
        val imageToolIds = listOf("image_compress", "image_resize", "image_convert", "image_metadata")
        if (imageToolIds.contains(tool.id) && onNavigateToRoute != null) {
            // Use route with operation parameter for image tools
            onNavigateToRoute(Screen.getRouteForToolId(tool.id))
        } else {
            // Use screen object for other tools
            onNavigateToScreen(tool.screen)
        }
    }

    val onToggleFavorite: (String) -> Unit = { id ->
        scope.launch { ToolPreferences.toggleFavorite(context, id) }
    }
    val onToggleHidden: (String) -> Unit = { id ->
        scope.launch { ToolPreferences.toggleHidden(context, id) }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(vertical = 16.dp)
    ) {
        // Subtitle
        item {
            Text(
                text = stringResource(R.string.home_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Search
        item {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text(stringResource(R.string.tools_search_hint)) },
                leadingIcon = {
                    Icon(Icons.Default.Search, contentDescription = null)
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_cancel))
                        }
                    }
                },
                singleLine = true,
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.fillMaxWidth()
            )
        }

        if (trimmedQuery.isNotEmpty()) {
            // Search results replace the sections while searching
            item { SectionHeader(title = stringResource(R.string.tools_search_results)) }
            if (searchResults.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.tools_search_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }
            } else {
                item {
                    ToolList(
                        tools = searchResults,
                        prefs = prefs,
                        menuToolId = menuToolId,
                        onMenuRequest = { menuToolId = it },
                        onToolClick = ::openTool,
                        onToggleFavorite = onToggleFavorite,
                        onToggleHidden = onToggleHidden
                    )
                }
            }
        } else {
            if (favoriteTools.isNotEmpty()) {
                item { SectionHeader(title = stringResource(R.string.category_favorites)) }
                item {
                    ToolList(
                        tools = favoriteTools,
                        prefs = prefs,
                        menuToolId = menuToolId,
                        onMenuRequest = { menuToolId = it },
                        onToolClick = ::openTool,
                        onToggleFavorite = onToggleFavorite,
                        onToggleHidden = onToggleHidden
                    )
                }
            }

            if (recentTools.isNotEmpty()) {
                item { SectionHeader(title = stringResource(R.string.category_recent)) }
                item {
                    ToolList(
                        tools = recentTools,
                        prefs = prefs,
                        menuToolId = menuToolId,
                        onMenuRequest = { menuToolId = it },
                        onToolClick = ::openTool,
                        onToggleFavorite = onToggleFavorite,
                        onToggleHidden = onToggleHidden
                    )
                }
            }

            // Categories
            ToolSection.entries.forEach { section ->
                val sectionTools = allTools.filter {
                    it.section == section && it.id !in prefs.hidden
                }
                if (sectionTools.isNotEmpty()) {
                    item { SectionHeader(title = getSectionTitle(section)) }
                    item {
                        ToolList(
                            tools = sectionTools,
                            prefs = prefs,
                            menuToolId = menuToolId,
                            onMenuRequest = { menuToolId = it },
                            onToolClick = ::openTool,
                            onToggleFavorite = onToggleFavorite,
                            onToggleHidden = onToggleHidden
                        )
                    }
                }
            }

            // Hidden tools, collapsed until asked for
            if (hiddenTools.isNotEmpty()) {
                item {
                    ExpandableSectionHeader(
                        title = stringResource(R.string.category_hidden, hiddenTools.size),
                        expanded = hiddenExpanded,
                        onToggle = { hiddenExpanded = !hiddenExpanded }
                    )
                }
                item {
                    AnimatedVisibility(visible = hiddenExpanded) {
                        ToolList(
                            tools = hiddenTools,
                            prefs = prefs,
                            menuToolId = menuToolId,
                            onMenuRequest = { menuToolId = it },
                            onToolClick = ::openTool,
                            onToggleFavorite = onToggleFavorite,
                            onToggleHidden = onToggleHidden
                        )
                    }
                }
            }
        }

        // Bottom spacing
        item {
            Spacer(modifier = Modifier.height(80.dp))
        }
    }
}

/**
 * Get localized title for a ToolSection.
 */
@Composable
private fun getSectionTitle(section: ToolSection): String {
    return when (section) {
        ToolSection.CREATE -> stringResource(R.string.category_create)
        ToolSection.DOCUMENT -> stringResource(R.string.category_document)
        ToolSection.EXPORT -> stringResource(R.string.category_export)
        ToolSection.VIEW -> stringResource(R.string.category_view)
        ToolSection.PROTECT -> stringResource(R.string.category_protect)
        ToolSection.IMAGES -> stringResource(R.string.category_image_tools)
    }
}

@Composable
private fun SectionHeader(title: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.width(8.dp))
        Divider(
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.outlineVariant
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExpandableSectionHeader(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit
) {
    Surface(
        onClick = onToggle,
        color = MaterialTheme.colorScheme.background,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(8.dp))
            Divider(
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.outlineVariant
            )
        }
    }
}

@Composable
private fun ToolList(
    tools: List<ToolItem>,
    prefs: ToolPrefs,
    menuToolId: String?,
    onMenuRequest: (String?) -> Unit,
    onToolClick: (ToolItem) -> Unit,
    onToggleFavorite: (String) -> Unit,
    onToggleHidden: (String) -> Unit
) {
    // Full-width rows rather than a grid of square tiles: the tool names and their
    // descriptions need the horizontal space, tiles truncated both.
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        tools.forEach { tool ->
            ToolRow(
                tool = tool,
                isFavorite = tool.id in prefs.favorites,
                isHidden = tool.id in prefs.hidden,
                menuExpanded = menuToolId == tool.id,
                onMenuRequest = onMenuRequest,
                onClick = { onToolClick(tool) },
                onToggleFavorite = { onToggleFavorite(tool.id) },
                onToggleHidden = { onToggleHidden(tool.id) }
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ToolRow(
    tool: ToolItem,
    isFavorite: Boolean,
    isHidden: Boolean,
    menuExpanded: Boolean,
    onMenuRequest: (String?) -> Unit,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    onToggleHidden: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box {
        Card(
            modifier = modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = { onMenuRequest(tool.id) }
                ),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(44.dp)
                ) {
                    Icon(
                        imageVector = tool.icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier
                            .padding(10.dp)
                            .size(24.dp)
                    )
                }

                Spacer(modifier = Modifier.width(14.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = tool.getTitle(),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = tool.getDescription(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                if (isFavorite) {
                    Icon(
                        imageVector = Icons.Default.Star,
                        contentDescription = stringResource(R.string.category_favorites),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                }

                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { onMenuRequest(null) }
        ) {
            DropdownMenuItem(
                text = {
                    Text(
                        stringResource(
                            if (isFavorite) R.string.tool_menu_favorite_remove
                            else R.string.tool_menu_favorite_add
                        )
                    )
                },
                leadingIcon = {
                    Icon(
                        if (isFavorite) Icons.Default.StarBorder else Icons.Default.Star,
                        contentDescription = null
                    )
                },
                onClick = {
                    onMenuRequest(null)
                    onToggleFavorite()
                }
            )
            DropdownMenuItem(
                text = {
                    Text(
                        stringResource(
                            if (isHidden) R.string.tool_menu_unhide else R.string.tool_menu_hide
                        )
                    )
                },
                leadingIcon = {
                    Icon(
                        if (isHidden) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                        contentDescription = null
                    )
                },
                onClick = {
                    onMenuRequest(null)
                    onToggleHidden()
                }
            )
        }
    }
}

/**
 * Every tool the app offers, grouped by what the user is trying to get done.
 */
@Composable
fun getAllTools(): List<ToolItem> = listOf(
    // MAKE A PDF OUT OF SOMETHING
    ToolItem(
        id = "scan_to_pdf",
        titleResId = R.string.tool_scan_to_pdf,
        descResId = R.string.desc_scan_to_pdf,
        icon = Icons.Default.DocumentScanner,
        section = ToolSection.CREATE,
        screen = Screen.ScanToPdf
    ),
    ToolItem(
        id = "html_to_pdf",
        titleResId = R.string.tool_html_to_pdf,
        descResId = R.string.desc_html_to_pdf,
        icon = Icons.Default.Language,
        section = ToolSection.CREATE,
        screen = Screen.HtmlToPdf
    ),
    ToolItem(
        id = "doc_to_pdf",
        titleResId = R.string.tool_doc_to_pdf,
        descResId = R.string.desc_doc_to_pdf,
        icon = Icons.Default.Description,
        section = ToolSection.CREATE,
        screen = Screen.DocToPdf
    ),

    // WORK ON THE DOCUMENT
    ToolItem(
        id = "merge",
        titleResId = R.string.tool_merge_pdf,
        descResId = R.string.desc_merge_pdfs,
        icon = Icons.Default.MergeType,
        section = ToolSection.DOCUMENT,
        screen = Screen.Merge
    ),
    ToolItem(
        id = "split",
        titleResId = R.string.tool_split_pdf,
        descResId = R.string.desc_split_pdf,
        icon = Icons.Default.CallSplit,
        section = ToolSection.DOCUMENT,
        screen = Screen.Split
    ),
    ToolItem(
        id = "compress",
        titleResId = R.string.tool_compress_pdf,
        descResId = R.string.desc_compress_pdf,
        icon = Icons.Default.Compress,
        section = ToolSection.DOCUMENT,
        screen = Screen.Compress
    ),
    ToolItem(
        id = "reorder",
        titleResId = R.string.tool_reorder_pages,
        descResId = R.string.desc_reorder_pages,
        icon = Icons.Default.SwapVert,
        section = ToolSection.DOCUMENT,
        screen = Screen.Reorder
    ),
    ToolItem(
        id = "rotate",
        titleResId = R.string.tool_rotate_pages,
        descResId = R.string.desc_rotate_pages,
        icon = Icons.Default.RotateRight,
        section = ToolSection.DOCUMENT,
        screen = Screen.Rotate
    ),
    ToolItem(
        id = "extract",
        titleResId = R.string.tool_extract_pages,
        descResId = R.string.desc_extract_pages,
        icon = Icons.Default.ContentCopy,
        section = ToolSection.DOCUMENT,
        screen = Screen.Extract
    ),
    ToolItem(
        id = "delete_pages",
        titleResId = R.string.tool_delete_pages,
        descResId = R.string.desc_delete_pages,
        icon = Icons.Default.Delete,
        section = ToolSection.DOCUMENT,
        screen = Screen.Organize
    ),
    ToolItem(
        id = "print_studio",
        titleResId = R.string.tool_print_studio,
        descResId = R.string.desc_print_studio,
        icon = Icons.Default.Print,
        section = ToolSection.DOCUMENT,
        screen = Screen.PrintStudio
    ),

    // GET SOMETHING OUT OF THE DOCUMENT
    ToolItem(
        id = "pdf_to_image",
        titleResId = R.string.tool_pdf_to_images,
        descResId = R.string.desc_pdf_to_images,
        icon = Icons.Default.PhotoLibrary,
        section = ToolSection.EXPORT,
        screen = Screen.PdfToImage
    ),
    ToolItem(
        id = "extract_text",
        titleResId = R.string.tool_extract_text,
        descResId = R.string.desc_extract_text,
        icon = Icons.Default.TextFields,
        section = ToolSection.EXPORT,
        screen = Screen.ExtractText
    ),
    ToolItem(
        id = "ocr",
        titleResId = R.string.tool_ocr,
        descResId = R.string.desc_ocr,
        icon = Icons.Default.FindInPage,
        section = ToolSection.EXPORT,
        screen = Screen.Ocr
    ),

    // READ IT
    ToolItem(
        id = "view_pdf",
        titleResId = R.string.tool_view_pdf,
        descResId = R.string.desc_view_pdf,
        icon = Icons.Default.PictureAsPdf,
        section = ToolSection.VIEW,
        screen = Screen.Home // Special handling
    ),
    ToolItem(
        id = "page_numbers",
        titleResId = R.string.tool_page_numbers,
        descResId = R.string.desc_page_numbers,
        icon = Icons.Default.FormatListNumbered,
        section = ToolSection.VIEW,
        screen = Screen.PageNumber
    ),
    ToolItem(
        id = "metadata",
        titleResId = R.string.tool_view_metadata,
        descResId = R.string.desc_view_metadata,
        icon = Icons.Default.Info,
        section = ToolSection.VIEW,
        screen = Screen.Metadata
    ),

    // PROTECT AND SIGN
    ToolItem(
        id = "lock",
        titleResId = R.string.tool_lock_pdf,
        descResId = R.string.desc_lock_pdf,
        icon = Icons.Default.Lock,
        section = ToolSection.PROTECT,
        screen = Screen.Security
    ),
    ToolItem(
        id = "unlock",
        titleResId = R.string.tool_unlock_pdf,
        descResId = R.string.desc_unlock_pdf,
        icon = Icons.Default.LockOpen,
        section = ToolSection.PROTECT,
        screen = Screen.Unlock
    ),
    ToolItem(
        id = "sign",
        titleResId = R.string.tool_sign_pdf,
        descResId = R.string.desc_sign,
        icon = Icons.Default.Draw,
        section = ToolSection.PROTECT,
        screen = Screen.SignPdf
    ),
    ToolItem(
        id = "watermark",
        titleResId = R.string.tool_add_watermark,
        descResId = R.string.desc_add_watermark,
        icon = Icons.Default.WaterDrop,
        section = ToolSection.PROTECT,
        screen = Screen.Watermark
    ),
    ToolItem(
        id = "fill_forms",
        titleResId = R.string.tool_fill_forms,
        descResId = R.string.desc_fill_forms,
        icon = Icons.Default.EditNote,
        section = ToolSection.PROTECT,
        screen = Screen.FillForms
    ),
    ToolItem(
        id = "flatten",
        titleResId = R.string.tool_flatten_pdf,
        descResId = R.string.desc_flatten_pdf,
        icon = Icons.Default.Layers,
        section = ToolSection.PROTECT,
        screen = Screen.Flatten
    ),

    // IMAGE TOOLS
    ToolItem(
        id = "image_compress",
        titleResId = R.string.tool_image_compress,
        descResId = R.string.desc_compress_image,
        icon = Icons.Default.Compress,
        section = ToolSection.IMAGES,
        screen = Screen.ImageTools
    ),
    ToolItem(
        id = "image_resize",
        titleResId = R.string.tool_image_resize,
        descResId = R.string.desc_resize_image,
        icon = Icons.Default.AspectRatio,
        section = ToolSection.IMAGES,
        screen = Screen.ImageTools
    ),
    ToolItem(
        id = "image_convert",
        titleResId = R.string.tool_image_convert,
        descResId = R.string.desc_convert_format,
        icon = Icons.Default.Transform,
        section = ToolSection.IMAGES,
        screen = Screen.ImageTools
    ),
    ToolItem(
        id = "image_metadata",
        titleResId = R.string.tool_image_metadata,
        descResId = R.string.desc_strip_metadata,
        icon = Icons.Default.DeleteSweep,
        section = ToolSection.IMAGES,
        screen = Screen.ImageTools
    )
)
