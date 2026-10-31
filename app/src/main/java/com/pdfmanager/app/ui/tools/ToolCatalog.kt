package com.pdfmanager.app.ui.tools

import androidx.annotation.StringRes
import androidx.compose.ui.graphics.vector.ImageVector
import com.pdfmanager.app.R
import com.pdfmanager.app.ui.AppIcons
import com.pdfmanager.app.ui.ToolAccent

/** Tool categories, in display order. Grid and List views both follow this order. */
enum class ToolCategory(@StringRes val titleRes: Int, val accent: ToolAccent) {
    ORGANIZE(R.string.cat_organize, ToolAccent.Coral),
    OPTIMIZE(R.string.cat_optimize, ToolAccent.Green),
    TO_PDF(R.string.cat_to_pdf, ToolAccent.Blue),
    FROM_PDF(R.string.cat_from_pdf, ToolAccent.Amber),
    EDIT(R.string.cat_edit, ToolAccent.Purple),
    PROTECT(R.string.cat_protect, ToolAccent.Rose)
}

/**
 * One tool. [route] is the existing NavHost destination in MainActivity; the catalog never
 * invents destinations.
 */
class ToolDef(
    val id: String,
    @StringRes val nameRes: Int,
    val icon: ImageVector,
    val route: String,
    val category: ToolCategory
)

/**
 * The single source of truth for the Tools screen: every existing tool exactly once, ordered by
 * category. Grid view (and, later, List view) only present this data differently.
 */
object ToolCatalog {
    val all: List<ToolDef> = listOf(
        // Organize PDF
        ToolDef("merge", R.string.tool_merge, AppIcons.MergeType, "merge", ToolCategory.ORGANIZE),
        ToolDef("split", R.string.tool_split, AppIcons.ContentCut, "split", ToolCategory.ORGANIZE),
        ToolDef("extract", R.string.tool_extract, AppIcons.Checklist, "extract", ToolCategory.ORGANIZE),
        ToolDef("reorder", R.string.tool_reorder, AppIcons.SwapVert, "reorder", ToolCategory.ORGANIZE),
        ToolDef("blank", R.string.tool_blank, AppIcons.NoteAdd, "blank", ToolCategory.ORGANIZE),
        ToolDef("nup", R.string.tool_nup, AppIcons.GridView, "nup", ToolCategory.ORGANIZE),
        ToolDef("booklet", R.string.tool_booklet, AppIcons.AutoStories, "booklet", ToolCategory.ORGANIZE),
        // Optimize PDF
        ToolDef("compress", R.string.tool_compress, AppIcons.Compress, "compress", ToolCategory.OPTIMIZE),
        ToolDef("repair", R.string.tool_repair, AppIcons.Handyman, "repair", ToolCategory.OPTIMIZE),
        // Convert to PDF
        ToolDef("img2pdf", R.string.tool_img2pdf, AppIcons.Image, "img2pdf", ToolCategory.TO_PDF),
        ToolDef("scan", R.string.tool_scan, AppIcons.DocumentScanner, "scan", ToolCategory.TO_PDF),
        ToolDef("text2pdf", R.string.tool_text2pdf, AppIcons.TextFields, "text2pdf", ToolCategory.TO_PDF),
        ToolDef("word2pdf", R.string.tool_word2pdf, AppIcons.WordFile, "word2pdf", ToolCategory.TO_PDF),
        ToolDef("excel2pdf", R.string.tool_excel2pdf, AppIcons.SheetFile, "excel2pdf", ToolCategory.TO_PDF),
        ToolDef("ppt2pdf", R.string.tool_ppt2pdf, AppIcons.SlidesFile, "ppt2pdf", ToolCategory.TO_PDF),
        // Convert from PDF
        ToolDef("pdf2img", R.string.tool_pdf2img, AppIcons.PhotoLibrary, "pdf2img", ToolCategory.FROM_PDF),
        ToolDef("pdf2text", R.string.tool_pdf2text, AppIcons.Description, "pdf2text", ToolCategory.FROM_PDF),
        ToolDef("extractimg", R.string.tool_extractimg, AppIcons.ExtractImages, "extractimg", ToolCategory.FROM_PDF),
        // Edit PDF
        ToolDef("watermark", R.string.tool_watermark, AppIcons.Watermark, "watermark", ToolCategory.EDIT),
        ToolDef("pagenum", R.string.tool_pagenum, AppIcons.Numbers, "pagenum", ToolCategory.EDIT),
        ToolDef("sign", R.string.tool_sign, AppIcons.Edit, "sign", ToolCategory.EDIT),
        ToolDef("notes", R.string.tool_notes, AppIcons.StickyNote, "notes", ToolCategory.EDIT),
        ToolDef("resize", R.string.tool_resize, AppIcons.AspectRatio, "resize", ToolCategory.EDIT),
        ToolDef("poster", R.string.tool_poster, AppIcons.Poster, "poster", ToolCategory.EDIT),
        ToolDef("croprotate", R.string.tool_croprotate, AppIcons.CropRotate, "croprotate", ToolCategory.EDIT),
        // Protect & Info
        ToolDef("lock", R.string.tool_lock, AppIcons.Lock, "lock", ToolCategory.PROTECT),
        ToolDef("metadata", R.string.tool_metadata, AppIcons.DriveFileRenameOutline, "metadata", ToolCategory.PROTECT)
    )

    /** The same tools grouped by category, for the List view. */
    val grouped: List<Pair<ToolCategory, List<ToolDef>>> by lazy { groupByCategory(all) }

    /** [tools] split by category in display order; categories without a match are left out. */
    fun groupByCategory(tools: List<ToolDef>): List<Pair<ToolCategory, List<ToolDef>>> =
        ToolCategory.entries.map { c -> c to tools.filter { it.category == c } }.filter { it.second.isNotEmpty() }

    /**
     * Tools matching [query]. [searchTerms] returns every text a tool can be found by
     * (its name and its category name, in the current language and in English).
     */
    fun search(query: String, searchTerms: (ToolDef) -> List<String>): List<ToolDef> =
        if (query.isBlank()) all else all.filter { ToolSearch.matches(query, searchTerms(it)) }
}
