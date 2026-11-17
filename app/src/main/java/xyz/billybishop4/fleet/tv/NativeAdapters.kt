package xyz.billybishop4.fleet.tv

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.StateListDrawable
import android.os.Parcelable
import android.text.TextUtils
import android.view.*
import android.widget.*
import androidx.recyclerview.widget.*
import coil.dispose

/** Diffing runs off the UI thread; only visible cards own views and bitmaps. */
internal class NativeAdapter<T : Any>(
    private val key: (T) -> String,
    private val create: (ViewGroup, Int) -> View,
    private val bind: (View, T, Int) -> Unit,
    private val type: (T) -> Int = { 0 },
    private val recycle: (View) -> Unit = {},
) : androidx.recyclerview.widget.ListAdapter<T, NativeAdapter.Holder>(object : DiffUtil.ItemCallback<T>() {
    override fun areItemsTheSame(a: T, b: T) = key(a) == key(b)
    // All callers use immutable data classes or Strings with structural equals.
    @android.annotation.SuppressLint("DiffUtilEquals")
    override fun areContentsTheSame(a: T, b: T) = a == b
}) {
    class Holder(view: View) : RecyclerView.ViewHolder(view)
    private val ids = HashMap<String, Long>()
    private var sequence = 0L
    init { setHasStableIds(true); stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY }
    override fun getItemId(position: Int): Long = ids.getOrPut(key(getItem(position))) { ++sequence }
    override fun getItemViewType(position: Int) = type(getItem(position))
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(create(parent, viewType))
    override fun onBindViewHolder(holder: Holder, position: Int) = bind(holder.itemView, getItem(position), position)
    override fun onCurrentListChanged(previousList: MutableList<T>, currentList: MutableList<T>) {
        // Usually only a handful of IDs have been exposed to RecyclerView.
        // Avoid allocating a hash set of an entire 6,000-channel catalog on main.
        if(ids.isNotEmpty()) {
            val stale=ids.keys.toMutableSet()
            for(item in currentList) { stale.remove(key(item));if(stale.isEmpty())break }
            stale.forEach(ids::remove)
        }
    }
    override fun onViewRecycled(holder: Holder) { recycle(holder.itemView); super.onViewRecycled(holder) }
}
internal class PosterCard(context: Context, private val loader: ArtworkLoader, private val model: FleetModel,
    private val widthDp: Int, horizontal: Boolean) : LinearLayout(context) {
    private val image = ImageView(context)
    private val label = context.text("", 13f)
    private var title: Title? = null
    private var open: ((Title) -> Unit)? = null
    init {
        orientation = VERTICAL; pad(4); isFocusable = true; isFocusableInTouchMode=true; isClickable = true
        if(android.os.Build.VERSION.SDK_INT>=26)defaultFocusHighlightEnabled=false
        layoutParams = RecyclerView.LayoutParams(if (horizontal) context.dp(widthDp + 10) else ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginEnd = context.dp(6); bottomMargin = context.dp(6)
        }
        image.scaleType = ImageView.ScaleType.CENTER_CROP
        image.clipToOutline = true; image.background = context.round(PANEL)
        image.setDuplicateParentStateEnabled(true)
        image.foreground = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused),context.round(Color.TRANSPARENT,WHITE))
            addState(intArrayOf(),context.round(Color.TRANSPARENT))
        }
        add(image, height = context.dp(widthDp * 3 / 2)); gap(6)
        label.maxLines = 1; label.ellipsize = TextUtils.TruncateAt.END; add(label); gap(4)
        setOnClickListener { title?.let { value -> open?.invoke(value) } }
    }
    fun bind(value: Title, click: (Title) -> Unit) {
        open = click; title = value; contentDescription = value.name; tag = "title:${value.key}"; label.text = value.name
        image.refreshDrawableState()
        loader.show(image, model.repository.artwork(value.poster), context.dp(widthDp), context.dp(widthDp * 3 / 2))
    }
    fun release() { image.dispose(); image.setImageDrawable(null); image.tag = null; title = null; open = null }
}
internal data class HomeRow(val name:String,val titles:List<Title>,val key:String=name)
internal class ShelfCard(context: Context, loader: ArtworkLoader, model: FleetModel, pool: RecyclerView.RecycledViewPool,
    private val large: Boolean, open: (Title) -> Unit) : LinearLayout(context) {
    private var continuing = false
    var shelfName="";private set
    var shelfKey="";private set
    private var boundTitles:List<Title> = emptyList()
    private val select: (Title) -> Unit = { title ->
        val progress = if (continuing) model.progress.firstOrNull { it.title.key == title.key && !it.finished } else null
        if(progress!=null && title.type=="series") {
            val activity=context as? MainActivity
            if(activity!=null)activity.openTitle(title,progress.episode) else model.open(Route.Detail(title,progress.episode))
        } else if(progress!=null)model.open(Route.Sources(title,progress.episode)) else open(title)
    }
    private val title = context.text("", 21f, bold = true)
    val list = RecyclerView(context)
    private val adapter = NativeAdapter<Title>({ it.key }, { parent, _ -> PosterCard(parent.context, loader, model, if (large) 162 else 130, true) },
        { view, value, _ -> (view as PosterCard).bind(value, select) }, recycle = { (it as PosterCard).release() })
    init {
        orientation = VERTICAL; layoutParams = RecyclerView.LayoutParams(-1, -2).apply { bottomMargin = context.dp(17) }
        add(title); gap(9)
        list.layoutManager = LinearLayoutManager(context, RecyclerView.HORIZONTAL, false).apply { initialPrefetchItemCount = 3 }
        list.setRecycledViewPool(pool); list.tune(); list.adapter = adapter
        add(list, height = context.dp((if (large) 162 else 130) * 3 / 2 + 47))
    }
    fun release() { list.adapter = null }
    fun saveState():Parcelable?=list.layoutManager?.onSaveInstanceState()
    fun focusTitle(tag:String):View? {
        if(adapter.currentList!=boundTitles)return null
        val position=adapter.currentList.indexOfFirst { "title:${it.key}"==tag }.takeIf { it>=0 } ?: 0
        val target=list.findTag(tag) ?: if(adapter.currentList.getOrNull(position)!=null)
            list.findTag("title:${adapter.currentList[position].key}") else null
        if(target==null && adapter.currentList.isNotEmpty())list.scrollToPosition(position)
        return target
    }
    fun bind(row: HomeRow,state:Parcelable?=null) {
        val changed=shelfKey!=row.key
        shelfName=row.name;shelfKey=row.key;boundTitles=row.titles;continuing=row.name=="Continue watching"
        if(list.adapter==null)list.adapter=adapter
        title.text=row.name;tag="shelf:${row.key}";list.contentDescription=row.name
        if(changed)list.stopScroll()
        adapter.submitList(row.titles) {
            if(shelfKey==row.key && changed) {
                if(state!=null)list.layoutManager?.onRestoreInstanceState(state) else list.scrollToPosition(0)
            }
        }
    }
}
