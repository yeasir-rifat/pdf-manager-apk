package com.pdfmanager.app

import android.app.Application
import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pdfmanager.app.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

enum class SortMode { NAME, NEWEST, LARGEST }

/** How far the text index has come: [done] of [total] files read. */
data class IndexProgress(val done: Int, val total: Int)

/** Files whose text contains the search words. [terms] says which words these hits were made for. */
data class SearchHits(val terms: List<String>, val byUri: Map<String, TextHit>)

class LibraryViewModel(app: Application) : AndroidViewModel(app) {
    private val dao = AppDatabase.get(app).dao()
    private val scanner = PdfScanner(app)
    private val textDao = TextIndexDb.get(app).dao()

    /** null = All, 0 = Unfiled, otherwise a folder id */
    val filter = MutableStateFlow<Long?>(null)
    val query = MutableStateFlow("")
    private val uiPrefs = app.getSharedPreferences("ui", android.content.Context.MODE_PRIVATE)
    val sort = MutableStateFlow(
        runCatching { SortMode.valueOf(uiPrefs.getString("sort", null) ?: SortMode.NEWEST.name) }.getOrDefault(SortMode.NEWEST)
    )
    val scanning = MutableStateFlow(false)

    /** Set when another screen (Home) wants the Files search field focused; Files clears it once handled. */
    val searchFocusRequested = MutableStateFlow(false)

    /** Search inside the PDFs' text (a text index is kept on the phone). Off removes the index. */
    val searchInside = MutableStateFlow(uiPrefs.getBoolean("searchInside", true))
    val indexProgress = MutableStateFlow<IndexProgress?>(null)
    private var indexJob: Job? = null

    init {
        // Daily automatic backup, if the person turned it on in Settings. Failures are ignored; the next start tries again
        viewModelScope.launch(Dispatchers.IO) { runCatching { BackupManager.autoBackupIfDue(app) } }
        // Remember the chosen sort order
        sort.onEach { uiPrefs.edit().putString("sort", it.name).apply() }.launchIn(viewModelScope)
    }

    /** One-off messages for the snackbar. */
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 4)

    val folders = dao.observeFolders()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val recents = dao.observeRecent(6)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    @OptIn(FlowPreview::class)
    private val searchText = query.debounce { if (it.isEmpty()) 0L else 150L }

    @OptIn(ExperimentalCoroutinesApi::class)
    val hits = combine(searchText, searchInside) { q, on -> if (on) SearchHelpers.textTerms(q) else emptyList() }
        .distinctUntilChanged()
        .flatMapLatest { terms ->
            if (terms.isEmpty()) flowOf(SearchHits(terms, emptyMap()))
            else textDao.observeHits(TextIndexer.hitQuery(terms))
                .map { list -> SearchHits(terms, list.associateBy { it.uri }) }
                // While PDFs are being indexed the table changes all the time: show the first answer at once,
                // then refresh at most every 300 ms instead of scanning all the text after every file
                .conflate()
                .transform { emit(it); delay(300) }
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SearchHits(emptyList(), emptyMap()))

    @OptIn(ExperimentalCoroutinesApi::class)
    val pdfs = combine(filter.flatMapLatest { dao.observePdfs(it) }, searchText, sort, filter, hits) { list, q, mode, f, h ->
        // Hits made for older words (while typing) are ignored
        val inText = if (h.terms == SearchHelpers.textTerms(q)) h.byUri else emptyMap()
        val found = if (q.isBlank()) list
        else list.filter { it.name.contains(q.trim(), ignoreCase = true) || it.uri in inText }
        // The Recent view is always ordered by when the file was last opened
        if (f == -2L) return@combine found.sortedByDescending { it.lastOpened }
        when (mode) {
            SortMode.NAME -> found
            SortMode.NEWEST -> found.sortedByDescending { it.modifiedSec }
            SortMode.LARGEST -> found.sortedByDescending { it.sizeBytes }
        }
    }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Home's "See all": Files opens on the Recent filter. */
    fun showRecent() { filter.value = -2L }

    /** Home's search action: Files opens on All files with the search field focused. */
    fun openSearch() {
        filter.value = null
        searchFocusRequested.value = true
    }

    private fun files(n: Int) = if (n == 1) "1 file" else "$n files"

    private var lastScan = 0L

    /** [force] is used by pull-to-refresh; automatic rescans are skipped if one just finished. */
    fun rescan(force: Boolean = false) {
        if (scanning.value) return
        if (!force && System.currentTimeMillis() - lastScan < 4000) return
        viewModelScope.launch {
            scanning.value = true
            try {
                withContext(Dispatchers.IO) {
                    dao.sync(scanner.scan())
                    // Reading data of earlier versions moves to stable keys; favorites waiting from a restore are applied
                    runCatching { BackupManager.afterScan(getApplication()) }
                }
                startIndexing()
            } finally {
                lastScan = System.currentTimeMillis()
                scanning.value = false
            }
        }
    }

    /** Reads the text of new and changed PDFs in the background. Does nothing while a run is going. */
    fun startIndexing() {
        if (!searchInside.value || indexJob?.isActive == true) return
        indexJob = viewModelScope.launch {
            try {
                TextIndexer.run(getApplication(), dao, textDao) { done, total ->
                    indexProgress.value = IndexProgress(done, total)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The index is only a speed-up: a failed run is tried again at the next scan
            } finally {
                indexProgress.value = null
            }
        }
    }

    /** On: starts reading the PDFs. Off: stops and deletes the stored text. */
    fun setSearchInside(on: Boolean) {
        uiPrefs.edit().putBoolean("searchInside", on).apply()
        searchInside.value = on
        if (on) {
            startIndexing()
        } else {
            viewModelScope.launch {
                indexJob?.cancelAndJoin()
                textDao.clear()
                messages.emit("Text search off. Stored text deleted")
            }
        }
    }

    /** After a restore: takes the sort order and the text-search switch from the stored preferences again. */
    fun reloadPrefs() {
        sort.value = runCatching { SortMode.valueOf(uiPrefs.getString("sort", null) ?: SortMode.NEWEST.name) }.getOrDefault(SortMode.NEWEST)
        val on = uiPrefs.getBoolean("searchInside", true)
        if (on != searchInside.value) setSearchInside(on)
    }

    fun addFolder(name: String) = viewModelScope.launch {
        if (name.isNotBlank()) {
            dao.insertFolder(FolderEntity(name = name.trim()))
            messages.emit("Folder created")
        }
    }

    fun deleteFolder(id: Long) = viewModelScope.launch {
        dao.deleteFolder(id)
        if (filter.value == id) filter.value = null
        messages.emit("Folder deleted")
    }

    fun move(uris: List<String>, folderId: Long?, folderName: String?) = viewModelScope.launch {
        uris.forEach { dao.moveTo(it, folderId) }
        messages.emit("Moved ${files(uris.size)}")
    }

    fun clearRecent() = viewModelScope.launch {
        dao.clearRecent()
        messages.emit("Recent list cleared")
    }

    fun setFavorite(uris: List<String>, value: Boolean, announce: Boolean = false) = viewModelScope.launch {
        dao.setFavorite(uris, value)
        if (announce) {
            messages.emit(if (value) "Added ${files(uris.size)} to favorites" else "Removed ${files(uris.size)} from favorites")
        }
    }

    fun delete(uris: List<String>) = viewModelScope.launch {
        val resolver = getApplication<Application>().contentResolver
        val deleted = withContext(Dispatchers.IO) {
            uris.filter { runCatching { resolver.delete(Uri.parse(it), null, null) > 0 }.getOrDefault(false) }
        }
        if (deleted.isNotEmpty()) dao.deleteByUris(deleted)
        messages.emit(
            if (deleted.size == uris.size) "Deleted ${files(deleted.size)}"
            else "Deleted ${deleted.size} of ${uris.size}"
        )
    }

    fun rename(uri: String, newName: String) = viewModelScope.launch {
        val name = newName.trim().let { if (it.endsWith(".pdf", true)) it else "$it.pdf" }
        val resolver = getApplication<Application>().contentResolver
        val before = runCatching { dao.metaOf(uri) }.getOrNull()
        val ok = withContext(Dispatchers.IO) {
            runCatching {
                val values = ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, name) }
                resolver.update(Uri.parse(uri), values, null, null) > 0
            }.getOrDefault(false)
        }
        if (ok) {
            // Bookmarks and the last read page are stored under the file name, so they follow the new name
            if (before != null) ReaderState.renamed(getApplication(), before.name, name, before.sizeBytes)
            dao.rename(uri, name)
            messages.emit("Renamed")
        } else {
            messages.emit("Could not rename")
        }
    }
}
