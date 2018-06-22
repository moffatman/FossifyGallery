package com.simplemobiletools.gallery.adapters

import android.os.Handler
import android.os.Looper
import android.text.format.DateFormat
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import com.bumptech.glide.Glide
import com.simplemobiletools.commons.activities.BaseSimpleActivity
import com.simplemobiletools.commons.adapters.MyRecyclerViewAdapter
import com.simplemobiletools.commons.dialogs.PropertiesDialog
import com.simplemobiletools.commons.dialogs.RenameItemDialog
import com.simplemobiletools.commons.extensions.*
import com.simplemobiletools.commons.helpers.OTG_PATH
import com.simplemobiletools.commons.models.FileDirItem
import com.simplemobiletools.commons.views.FastScroller
import com.simplemobiletools.commons.views.MyRecyclerView
import com.simplemobiletools.gallery.R
import com.simplemobiletools.gallery.dialogs.DeleteWithRememberDialog
import com.simplemobiletools.gallery.extensions.*
import com.simplemobiletools.gallery.helpers.*
import com.simplemobiletools.gallery.models.Medium
import com.simplemobiletools.gallery.models.ThumbnailItem
import com.simplemobiletools.gallery.models.ThumbnailMedium
import com.simplemobiletools.gallery.models.ThumbnailSection
import kotlinx.android.synthetic.main.photo_video_item_grid.view.*
import kotlinx.android.synthetic.main.thumbnail_section.view.*
import java.util.*
import kotlin.collections.ArrayList

class MediaAdapter(activity: BaseSimpleActivity, var media: MutableList<Medium>, val listener: MediaOperationsListener?, val isAGetIntent: Boolean,
                   val allowMultiplePicks: Boolean, recyclerView: MyRecyclerView, fastScroller: FastScroller? = null, val path: String,
                   itemClick: (Any) -> Unit) : MyRecyclerViewAdapter(activity, recyclerView, fastScroller, itemClick) {

    private val INSTANT_LOAD_DURATION = 2000L
    private val IMAGE_LOAD_DELAY = 100L
    private val ITEM_SECTION = 0
    private val ITEM_MEDIUM = 1

    private val config = activity.config
    private val isListViewType = config.viewTypeFiles == VIEW_TYPE_LIST
    private var visibleItemPaths = ArrayList<String>()
    private var thumbnailItems = ArrayList<ThumbnailItem>()
    private var loadImageInstantly = false
    private var delayHandler = Handler(Looper.getMainLooper())
    private var currentMediaHash = media.hashCode()
    private var currentGrouping = GROUP_BY_NONE
    private val hasOTGConnected = activity.hasOTGConnected()
    private var mediumGroups = LinkedHashMap<String, ArrayList<Medium>>()

    private var scrollHorizontally = config.scrollHorizontally
    private var animateGifs = config.animateGifs
    private var cropThumbnails = config.cropThumbnails
    private var displayFilenames = config.displayFileNames

    init {
        setupDragListener(true)
        groupMedia()
        enableInstantLoad()
    }

    override fun getActionMenuId() = R.menu.cab_media

    override fun prepareItemSelection(viewHolder: ViewHolder) {
        viewHolder.itemView?.medium_check?.background?.applyColorFilter(primaryColor)
    }

    override fun markViewHolderSelection(select: Boolean, viewHolder: ViewHolder?) {
        viewHolder?.itemView?.medium_check?.beVisibleIf(select)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val layoutType = if (viewType == ITEM_SECTION) {
            R.layout.thumbnail_section
        } else {
            if (isListViewType) {
                R.layout.photo_video_item_list
            } else {
                R.layout.photo_video_item_grid
            }
        }
        return createViewHolder(layoutType, parent)
    }

    override fun onBindViewHolder(holder: MyRecyclerViewAdapter.ViewHolder, position: Int) {
        val tmbItem = thumbnailItems.getOrNull(position) ?: return
        if (tmbItem is ThumbnailMedium) {
            visibleItemPaths.add(tmbItem.path)
        }

        val view = holder.bindView(tmbItem, !allowMultiplePicks) { itemView, adapterPosition ->
            if (tmbItem is ThumbnailMedium) {
                setupThumbnailMedium(itemView, tmbItem)
            } else {
                setupThumbnailSection(itemView, tmbItem as ThumbnailSection)
            }
        }
        bindViewHolder(holder, position, view)
    }

    override fun getItemCount() = thumbnailItems.size

    override fun getItemViewType(position: Int): Int {
        val tmbItem = thumbnailItems[position]
        return if (tmbItem is ThumbnailSection) {
            ITEM_SECTION
        } else {
            ITEM_MEDIUM
        }
    }

    override fun prepareActionMode(menu: Menu) {
        menu.apply {
            findItem(R.id.cab_rename).isVisible = isOneItemSelected()
            findItem(R.id.cab_open_with).isVisible = isOneItemSelected()
            findItem(R.id.cab_confirm_selection).isVisible = isAGetIntent && allowMultiplePicks && selectedPositions.size > 0

            checkHideBtnVisibility(this)
            checkFavoriteBtnVisibility(this)
        }
    }

    override fun actionItemPressed(id: Int) {
        if (selectedPositions.isEmpty()) {
            return
        }

        when (id) {
            R.id.cab_confirm_selection -> confirmSelection()
            R.id.cab_properties -> showProperties()
            R.id.cab_rename -> renameFile()
            R.id.cab_edit -> editFile()
            R.id.cab_hide -> toggleFileVisibility(true)
            R.id.cab_unhide -> toggleFileVisibility(false)
            R.id.cab_add_to_favorites -> toggleFavorites(true)
            R.id.cab_remove_from_favorites -> toggleFavorites(false)
            R.id.cab_share -> shareMedia()
            R.id.cab_copy_to -> copyMoveTo(true)
            R.id.cab_move_to -> copyMoveTo(false)
            R.id.cab_select_all -> selectAll()
            R.id.cab_open_with -> activity.openPath(getCurrentPath(), true)
            R.id.cab_set_as -> activity.setAs(getCurrentPath())
            R.id.cab_delete -> checkDeleteConfirmation()
        }
    }

    override fun getSelectableItemCount() = thumbnailItems.filter { it is ThumbnailMedium }.size

    override fun getIsItemSelectable(position: Int) = !isASectionTitle(position)

    override fun onViewRecycled(holder: ViewHolder) {
        super.onViewRecycled(holder)
        if (!activity.isActivityDestroyed()) {
            val itemView = holder.itemView
            visibleItemPaths.remove(itemView?.photo_name?.tag)
            val tmb = itemView?.medium_thumbnail
            if (tmb != null) {
                Glide.with(activity).clear(tmb)
            }
        }
    }

    fun isASectionTitle(position: Int) = thumbnailItems.getOrNull(position) is ThumbnailSection

    private fun checkHideBtnVisibility(menu: Menu) {
        var hiddenCnt = 0
        var unhiddenCnt = 0
        getSelectedMedia().forEach {
            if (it.name.startsWith('.')) {
                hiddenCnt++
            } else {
                unhiddenCnt++
            }
        }

        menu.findItem(R.id.cab_hide).isVisible = unhiddenCnt > 0
        menu.findItem(R.id.cab_unhide).isVisible = hiddenCnt > 0
    }

    private fun checkFavoriteBtnVisibility(menu: Menu) {
        var favoriteCnt = 0
        var nonFavoriteCnt = 0
        getSelectedMedia().forEach {
            if (it.isFavorite) {
                favoriteCnt++
            } else {
                nonFavoriteCnt++
            }
        }

        menu.findItem(R.id.cab_add_to_favorites).isVisible = nonFavoriteCnt > 0
        menu.findItem(R.id.cab_remove_from_favorites).isVisible = favoriteCnt > 0
    }

    private fun confirmSelection() {
        listener?.selectedPaths(getSelectedPaths())
    }

    private fun showProperties() {
        if (selectedPositions.size <= 1) {
            PropertiesDialog(activity, (thumbnailItems[selectedPositions.first()] as ThumbnailMedium).path, config.shouldShowHidden)
        } else {
            val paths = getSelectedPaths()
            PropertiesDialog(activity, paths, config.shouldShowHidden)
        }
    }

    private fun renameFile() {
        val oldPath = getCurrentPath()
        RenameItemDialog(activity, oldPath) {
            Thread {
                activity.updateDBMediaPath(oldPath, it)

                activity.runOnUiThread {
                    enableInstantLoad()
                    listener?.refreshItems()
                    finishActMode()
                }
            }.start()
        }
    }

    private fun editFile() {
        activity.openEditor(getCurrentPath())
    }

    private fun toggleFileVisibility(hide: Boolean) {
        Thread {
            getSelectedMedia().forEach {
                activity.toggleFileVisibility(it.path, hide)
            }
            activity.runOnUiThread {
                listener?.refreshItems()
                finishActMode()
            }
        }.start()
    }

    private fun toggleFavorites(add: Boolean) {
        Thread {
            val mediumDao = activity.galleryDB.MediumDao()
            getSelectedMedia().forEach {
                it.isFavorite = add
                mediumDao.updateFavorite(it.path, add)
            }
            activity.runOnUiThread {
                listener?.refreshItems()
                finishActMode()
            }
        }.start()
    }

    private fun shareMedia() {
        if (selectedPositions.size == 1 && selectedPositions.first() != -1) {
            activity.shareMediumPath(getSelectedMedia().first().path)
        } else if (selectedPositions.size > 1) {
            activity.shareMediaPaths(getSelectedPaths())
        }
    }

    private fun copyMoveTo(isCopyOperation: Boolean) {
        val paths = getSelectedPaths()

        val fileDirItems = paths.map {
            FileDirItem(it, it.getFilenameFromPath())
        } as ArrayList

        activity.tryCopyMoveFilesTo(fileDirItems, isCopyOperation) {
            config.tempFolderPath = ""
            activity.applicationContext.rescanFolderMedia(it)
            activity.applicationContext.rescanFolderMedia(fileDirItems.first().getParentPath())
            if (!isCopyOperation) {
                listener?.refreshItems()
            }
        }
    }

    private fun checkDeleteConfirmation() {
        if (config.tempSkipDeleteConfirmation || config.skipDeleteConfirmation) {
            deleteFiles()
        } else {
            askConfirmDelete()
        }
    }

    private fun askConfirmDelete() {
        val items = resources.getQuantityString(R.plurals.delete_items, selectedPositions.size, selectedPositions.size)
        val question = String.format(resources.getString(R.string.deletion_confirmation), items)
        DeleteWithRememberDialog(activity, question) {
            config.tempSkipDeleteConfirmation = it
            deleteFiles()
        }
    }

    private fun getCurrentPath() = (thumbnailItems[selectedPositions.first()] as ThumbnailMedium).path

    private fun deleteFiles() {
        if (selectedPositions.isEmpty()) {
            return
        }

        val fileDirItems = ArrayList<FileDirItem>(selectedPositions.size)
        val removeMedia = ArrayList<ThumbnailMedium>(selectedPositions.size)

        if (thumbnailItems.size <= selectedPositions.first()) {
            finishActMode()
            return
        }

        val SAFPath = (thumbnailItems[selectedPositions.first()] as ThumbnailMedium).path
        activity.handleSAFDialog(SAFPath) {
            selectedPositions.sortedDescending().forEach {
                val thumbnailItem = thumbnailItems[it]
                if (thumbnailItem is ThumbnailMedium) {
                    fileDirItems.add(FileDirItem(thumbnailItem.path, thumbnailItem.name))
                    removeMedia.add(thumbnailItem)
                }
            }

            thumbnailItems.removeAll(removeMedia)
            listener?.tryDeleteFiles(fileDirItems)
            removeSelectedItems()
        }
    }

    private fun getSelectedMedia(): List<ThumbnailMedium> {
        val selectedMedia = ArrayList<ThumbnailMedium>(selectedPositions.size)
        selectedPositions.forEach {
            selectedMedia.add(thumbnailItems[it] as ThumbnailMedium)
        }
        return selectedMedia
    }

    private fun getSelectedPaths() = getSelectedMedia().map { it.path } as ArrayList<String>

    fun updateMedia(newMedia: ArrayList<Medium>) {
        if (newMedia.hashCode() != currentMediaHash || currentGrouping != config.getFolderGrouping(path)) {
            currentMediaHash = newMedia.hashCode()
            Handler().postDelayed({
                media = newMedia
                groupMedia()
                enableInstantLoad()
                notifyDataSetChanged()
                finishActMode()
                fastScroller?.measureRecyclerView()
            }, 100L)
        }
    }

    fun updateDisplayFilenames(displayFilenames: Boolean) {
        this.displayFilenames = displayFilenames
        enableInstantLoad()
        notifyDataSetChanged()
    }

    fun updateAnimateGifs(animateGifs: Boolean) {
        this.animateGifs = animateGifs
        notifyDataSetChanged()
    }

    fun updateCropThumbnails(cropThumbnails: Boolean) {
        this.cropThumbnails = cropThumbnails
        notifyDataSetChanged()
    }

    fun updateScrollHorizontally(scrollHorizontally: Boolean) {
        this.scrollHorizontally = scrollHorizontally
        notifyDataSetChanged()
    }

    private fun enableInstantLoad() {
        loadImageInstantly = true
        delayHandler.postDelayed({
            loadImageInstantly = false
        }, INSTANT_LOAD_DURATION)
    }

    private fun groupMedia() {
        currentGrouping = config.getFolderGrouping(path)
        if (currentGrouping and GROUP_BY_NONE != 0) {
            return
        }

        mediumGroups.clear()
        media.forEach {
            val key = it.getGroupingKey(currentGrouping)
            if (!mediumGroups.containsKey(key)) {
                mediumGroups[key] = ArrayList()
            }
            mediumGroups[key]!!.add(it)
        }

        val sortDescending = currentGrouping and GROUP_DESCENDING != 0
        val sorted = mediumGroups.toSortedMap(if (sortDescending) compareByDescending { it } else compareBy { it })
        mediumGroups.clear()
        sorted.forEach { key, value ->
            mediumGroups[key] = value
        }

        thumbnailItems.clear()
        for ((key, value) in mediumGroups) {
            thumbnailItems.add(ThumbnailSection(getFormattedKey(key, currentGrouping)))
            value.forEach {
                val thumbnailMedium = ThumbnailMedium(it.name, it.path, it.parentPath, it.modified, it.taken, it.size, it.type, it.isFavorite)
                thumbnailItems.add(thumbnailMedium)
            }
        }
    }

    private fun getFormattedKey(key: String, grouping: Int): String {
        return when {
            grouping and GROUP_BY_LAST_MODIFIED != 0 || grouping and GROUP_BY_DATE_TAKEN != 0 -> formatDate(key)
            grouping and GROUP_BY_FILE_TYPE != 0 -> getFileTypeString(key)
            grouping and GROUP_BY_EXTENSION != 0 -> key.toUpperCase()
            grouping and GROUP_BY_FOLDER != 0 -> activity.humanizePath(key)
            else -> key
        }
    }

    private fun formatDate(timestamp: String): String {
        return if (timestamp.areDigitsOnly()) {
            val cal = Calendar.getInstance(Locale.ENGLISH)
            cal.timeInMillis = timestamp.toLong()
            DateFormat.format("dd MMM yyyy", cal).toString()
        } else {
            ""
        }
    }

    private fun getFileTypeString(key: String): String {
        val stringId = when (key.toInt()) {
            TYPE_IMAGES -> R.string.images
            TYPE_VIDEOS -> R.string.videos
            TYPE_GIFS -> R.string.gifs
            else -> R.string.raw_images
        }
        return activity.getString(stringId)
    }

    fun getItemBubbleText(position: Int, sorting: Int) = (thumbnailItems[position] as? ThumbnailMedium)?.getBubbleText(sorting)

    private fun setupThumbnailMedium(view: View, medium: ThumbnailMedium) {
        view.apply {
            play_outline.beVisibleIf(medium.isVideo())
            photo_name.beVisibleIf(displayFilenames || isListViewType)
            photo_name.text = medium.name
            photo_name.tag = medium.path

            var thumbnailPath = medium.path
            if (hasOTGConnected && thumbnailPath.startsWith(OTG_PATH)) {
                thumbnailPath = thumbnailPath.getOTGPublicPath(context)
            }

            if (loadImageInstantly) {
                activity.loadImage(medium.type, thumbnailPath, medium_thumbnail, scrollHorizontally, animateGifs, cropThumbnails)
            } else {
                medium_thumbnail.setImageDrawable(null)
                medium_thumbnail.isHorizontalScrolling = scrollHorizontally
                delayHandler.postDelayed({
                    val isVisible = visibleItemPaths.contains(medium.path)
                    if (isVisible) {
                        activity.loadImage(medium.type, thumbnailPath, medium_thumbnail, scrollHorizontally, animateGifs, cropThumbnails)
                    }
                }, IMAGE_LOAD_DELAY)
            }

            if (isListViewType) {
                photo_name.setTextColor(textColor)
                play_outline.applyColorFilter(textColor)
            }
        }
    }

    private fun setupThumbnailSection(view: View, section: ThumbnailSection) {
        view.apply {
            thumbnail_section.text = section.title
            thumbnail_section.setTextColor(textColor)
        }
    }

    interface MediaOperationsListener {
        fun refreshItems()

        fun tryDeleteFiles(fileDirItems: ArrayList<FileDirItem>)

        fun selectedPaths(paths: ArrayList<String>)
    }
}
