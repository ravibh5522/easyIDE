package dev.easyide.app.ui.icons

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CallSplit
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Commit
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.DataObject
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Difference
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderCopy
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Inventory
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Numbers
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.VerticalSplit
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material.icons.filled.ViewHeadline
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * The Material Symbols side of the resolver: the generic verbs identity.md 6 keeps on Material
 * (close, add, search, more, back, check, edit, delete, copy, refresh...), a stand-in for each
 * custom glyph (used when `appearance.iconStyle` is `material`), and the workspace and pack
 * tokens that have no custom drawing yet. This is the only file outside the icon set that may
 * import Material icons for the app chrome.
 */
internal val MATERIAL_ICONS: Map<String, ImageVector> = mapOf(
    // Stand-ins for the custom glyphs.
    "prompt" to Icons.Filled.Terminal, "palette" to Icons.Filled.Keyboard, "settings" to Icons.Filled.Settings,
    "swatch" to Icons.Filled.Palette, "keycap" to Icons.Filled.Keyboard, "density" to Icons.Filled.ViewHeadline,
    "dock" to Icons.Filled.ViewAgenda, "project" to Icons.Filled.Folder, "new_project" to Icons.Filled.CreateNewFolder,
    "terminal" to Icons.Filled.Terminal, "language_server" to Icons.Filled.DataObject, "agent" to Icons.Filled.SmartToy,
    "branch" to Icons.Filled.CallSplit, "commit" to Icons.Filled.Commit, "diff" to Icons.Filled.Difference,
    "stash" to Icons.Filled.Archive, "sync" to Icons.Filled.Sync, "sandbox" to Icons.Filled.Inventory,
    "environment" to Icons.Filled.Layers, "root" to Icons.Filled.Numbers, "extension_pack" to Icons.Filled.Extension,
    "install" to Icons.Filled.Download, "theme" to Icons.Filled.Contrast,
    // Shell and workspace names that alias a custom glyph (see [CUSTOM_ALIASES]).
    "extensions" to Icons.Filled.Extension, "git" to Icons.Filled.Difference, "files" to Icons.Filled.Folder,
    "commands" to Icons.Filled.Keyboard,
    // Generic verbs and names that stay Material.
    "home" to Icons.Filled.Home, "search" to Icons.Filled.Search, "problems" to Icons.Filled.ErrorOutline,
    "outline" to Icons.Filled.AccountTree, "output" to Icons.Filled.Description, "file" to Icons.Filled.Description, "warning" to Icons.Filled.Warning,
    "back" to Icons.AutoMirrored.Filled.ArrowBack, "close" to Icons.Filled.Close, "more" to Icons.Filled.MoreHoriz,
    "more_vert" to Icons.Filled.MoreVert, "add" to Icons.Filled.Add, "check" to Icons.Filled.Check,
    "expand_more" to Icons.Filled.ExpandMore, "split" to Icons.Filled.VerticalSplit, "folder_copy" to Icons.Filled.FolderCopy,
    "save" to Icons.Filled.Save, "play_arrow" to Icons.Filled.PlayArrow, "stop" to Icons.Filled.Stop,
    "refresh" to Icons.Filled.Refresh, "edit" to Icons.Filled.Edit, "delete" to Icons.Filled.Delete,
    "content_copy" to Icons.Filled.ContentCopy, "code" to Icons.Filled.Code, "build" to Icons.Filled.Build,
    "bug_report" to Icons.Filled.BugReport, "history" to Icons.Filled.History, "upload" to Icons.Filled.Upload,
    "download" to Icons.Filled.Download, "arrow_upward" to Icons.Filled.ArrowUpward,
    "arrow_downward" to Icons.Filled.ArrowDownward,
)

/** What an unknown token draws, so an extension's item is always visible and tappable. */
internal val UNKNOWN_ICON: ImageVector = Icons.Filled.Widgets
