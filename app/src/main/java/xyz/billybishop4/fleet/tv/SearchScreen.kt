package xyz.billybishop4.fleet.tv

import android.content.Context
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.os.Parcelable
import android.text.TextUtils
import android.text.Editable
import android.text.TextWatcher
import android.text.InputType
import android.view.*
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.recyclerview.widget.*
import java.security.MessageDigest

private data class HomeRowsKey(val progress:List<Progress>,val saved:List<Title>,val shelves:List<Shelf>,val filter:CatalogFilter,
    val continuing:Boolean,val recent:Boolean,val myList:Boolean)

internal class SearchScreen(private val a:MainActivity):NativeScreen {
    private val m=a.model
    override val view=object:LinearLayout(a) {
        init { orientation=VERTICAL }
        override fun dispatchKeyEvent(event:KeyEvent):Boolean {
            if(event.action==KeyEvent.ACTION_DOWN && event.keyCode==KeyEvent.KEYCODE_DPAD_UP && returnToInput())return true
            return super.dispatchKeyEvent(event)
        }
    }
    private val home=RecyclerView(a).apply { tag="search-home";layoutManager=LinearLayoutManager(a);tune(cachedViews=1) }
    private val results=RecyclerView(a).apply { tag="search-results";layoutManager=LinearLayoutManager(a);tune(cachedViews=1) }
    private val filterRow=a.row()
    private val filters=HorizontalScrollView(a).apply { tag="catalog-filters";isHorizontalScrollBarEnabled=false;addView(filterRow) }
    private var filterDefinitions:List<CatalogDefinition>?=null
    private var renderedFilter:CatalogFilter?=null
    private var lastFilterFocus:String?=null
    private var filterDialog:android.app.AlertDialog?=null
    private val summary=a.text("",12f,MUTED)
    private var capabilities=BrowseAddonCapabilities(m.addons)
    private val addonMessage=a.text("",14f,MUTED).apply { maxLines=3;ellipsize=TextUtils.TruncateAt.END }
    private val manageAddons=a.action("Manage add-ons") { m.open(Route.Addons) }
    private val addonNotice=a.column().apply {
        add(a.row().apply {
            addView(addonMessage,LinearLayout.LayoutParams(0,-2,1f).apply { marginEnd=a.dp(14) })
            add(manageAddons,width=-2)
        });gap(10)
    }
    private val emptyTitle=a.text("",21f,bold=true)
    private val emptyMessage=a.text("",14f,MUTED)
    private val emptyRefresh=a.action("Refresh catalog") { m.retry() }.quiet()
    private val empty=a.column().apply {
        gravity=Gravity.CENTER;add(emptyTitle,width=-2);gap(8);add(emptyMessage,width=-2);gap(14);add(emptyRefresh,width=-2)
    }
    private var syncingQuery=false
    private val input=EditText(a).apply {
        id=View.generateViewId();tag="field:Search movies and shows";contentDescription="Search movies and shows"
        hint="Search movies and shows";textSize=16f;setTextColor(WHITE);setHintTextColor(MUTED)
        setSingleLine(true);minWidth=0;minimumWidth=0;minimumHeight=a.dp(48)
        inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        imeOptions=EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        showSoftInputOnFocus=false
        background=StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused),a.round(PANEL,FOCUS))
            addState(intArrayOf(),a.round(PANEL))
        }
        setPadding(a.dp(14),a.dp(10),a.dp(14),a.dp(10))
        val icon=a.getDrawable(R.drawable.ic_search)!!.mutate().apply { setBounds(0,0,a.dp(20),a.dp(20)) }
        setCompoundDrawablesRelative(icon,null,null,null);compoundDrawablePadding=a.dp(10)
        setText(m.searchText);setSelection(text.length)
    }
    private val submit=a.action("Search","Submit search",prominent=true) { submitSearch() }.apply { id=View.generateViewId() }
    private val clear=a.action("Clear","Clear search") { input.setText("");input.requestFocus() }.quiet().apply { id=View.generateViewId() }
    private var homeState:Parcelable?=null
    private var resultsState:Parcelable?=null
    private var lastQuery=m.searchText
    private var homeRowsKey:HomeRowsKey?=null
    private var homeRows:List<HomeRow> = emptyList()
    private var resultsShelves:List<Shelf>?=null
    private var resultsQuery:String?=null
    private var resultRows:List<HomeRow> = emptyList()
    private val shelfStates=LinkedHashMap<String,Parcelable>()
    private val focusRestore=BrowseFocusRestore(a,view)
    private fun open(t:Title)=a.openTitle(t)
    private val rows=NativeAdapter<HomeRow>(
        { it.key },
        { parent,_ -> ShelfCard(parent.context,a.artwork,m,a.posterPool,m.preferences.largePosters,::open) },
        { v,row,_ -> (v as ShelfCard).bind(row,shelfStates[row.key]) },
        recycle={ v -> (v as ShelfCard).let { shelf -> shelf.saveState()?.let { saveShelf(shelf.shelfKey,it) };shelf.release() } })
    private val searchRows=NativeAdapter<HomeRow>({it.key},
        { parent,_->ShelfCard(parent.context,a.artwork,m,a.posterPool,m.preferences.largePosters,::open) },
        { v,row,_->(v as ShelfCard).bind(row,shelfStates[row.key]) },
        recycle={ v -> (v as ShelfCard).let { shelf -> shelf.saveState()?.let { saveShelf(shelf.shelfKey,it) };shelf.release() } })
    init {
        input.nextFocusRightId=submit.id;submit.nextFocusLeftId=input.id;clear.nextFocusLeftId=submit.id
        input.setOnClickListener { keyboard() }
        input.setOnFocusChangeListener { _,focused -> if(!focused)hideKeyboard() }
        input.setOnKeyListener { _,key,event ->
            when(key) {
                KeyEvent.KEYCODE_DPAD_UP -> {
                    if(event.action==KeyEvent.ACTION_DOWN) {
                        focusRestore.cancel();hideKeyboard()
                        a.window.decorView.findTag("navigation:On-Demand")?.requestFocus()
                    }
                    true
                }
                KeyEvent.KEYCODE_DPAD_CENTER -> { if(event.action==KeyEvent.ACTION_UP && event.repeatCount==0)keyboard();true }
                KeyEvent.KEYCODE_ENTER -> { if(event.action==KeyEvent.ACTION_UP && event.repeatCount==0)submitSearch();true }
                else -> false
            }
        }
        input.setOnEditorActionListener { _,action,event ->
            when {
                action in listOf(EditorInfo.IME_ACTION_SEARCH,EditorInfo.IME_ACTION_DONE,EditorInfo.IME_ACTION_GO) -> { submitSearch();true }
                event?.keyCode==KeyEvent.KEYCODE_ENTER -> { if(event.action==KeyEvent.ACTION_UP)submitSearch();true }
                else -> false
            }
        }
        input.addTextChangedListener(object:TextWatcher {
            override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int) {}
            override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int) {
                if(!syncingQuery) { focusRestore.cancel();m.search(s?.toString().orEmpty()) }
            }
            override fun afterTextChanged(s:Editable?) {}
        })
        val headerHeight=maxOf(a.dp(50),input.lineHeight+input.compoundPaddingTop+input.compoundPaddingBottom)
        view.add(a.row().apply {
            tag="search-controls";isBaselineAligned=false
            addView(input,LinearLayout.LayoutParams(0,-1,1f))
            addView(submit,LinearLayout.LayoutParams(-2,a.dp(44)).apply { marginStart=a.dp(10) })
            addView(clear,LinearLayout.LayoutParams(-2,a.dp(44)).apply { marginStart=a.dp(8) })
        },height=headerHeight);view.gap(8);view.add(filters,height=a.dp(48));view.gap(4);view.add(addonNotice);view.add(summary);view.gap(6)
        home.adapter=rows;results.adapter=searchRows
        view.addView(FrameLayout(a).apply {
            addView(home,FrameLayout.LayoutParams(-1,-1));addView(results,FrameLayout.LayoutParams(-1,-1))
            addView(empty,FrameLayout.LayoutParams(-1,-1))
        },LinearLayout.LayoutParams(-1,0,1f))
    }
    private fun keyboard() {
        input.requestFocus()
        input.post { if(input.isAttachedToWindow && input.hasFocus())
            (a.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(input,InputMethodManager.SHOW_IMPLICIT) }
    }
    private fun hideKeyboard() { (a.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(input.windowToken,0) }
    private fun submitSearch() {
        val text=input.text.toString()
        if(text.trim().length<2) { keyboard();return }
        m.search(text);hideKeyboard()
        focusRestore.start(waitForWindowFocus=true) {
            if(m.searchText!=text || m.busy)null
            else if(addonNotice.isShown)manageAddons
            else if(!results.isShown)null
            else if(m.results.isNotEmpty())(results.getChildAt(0) as? ShelfCard)?.let { card -> searchRows.currentList.firstOrNull()?.titles?.firstOrNull()?.let { card.focusTitle("title:${it.key}") } }
            else if(m.error!=null)input else clear
        }
    }
    private fun returnToInput():Boolean {
        val focused=view.findFocus()?:return false
        if(home.isShown) {
            val row=home.findContainingItemView(focused)
            if(row!=null && home.getChildAdapterPosition(row)==0)return filterTarget()?.requestFocus() ?: input.requestFocus()
        }
        if(results.isShown) {
            val item=results.findContainingItemView(focused)
            val position=item?.let(results::getChildAdapterPosition) ?: RecyclerView.NO_POSITION
            if(position==0)return input.requestFocus()
        }
        return false
    }
    private fun hash(value:String)=MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        .take(12).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun filterHash()=hash(org.json.JSONObject().put("type",m.catalogFilter.type).put("catalog",m.catalogFilter.catalogKey)
        .put("extra",org.json.JSONObject(m.catalogFilter.extras.toSortedMap())).toString())
    private fun homeRows():List<HomeRow> {
        val prefs=m.preferences
        val next=HomeRowsKey(m.progress,m.saved,m.shelves,m.catalogFilter,prefs.showContinue,prefs.showRecent,prefs.showMyList)
        if(next==homeRowsKey)return homeRows
        homeRowsKey=next
        val items=ArrayList<HomeRow>()
        val continuing=if(prefs.showContinue)m.progress.filter { !it.finished && it.positionMs>0 }.map { it.title }.distinctBy { it.key } else emptyList()
        val continuingKeys=continuing.mapTo(HashSet()) { it.key }
        if(continuing.isNotEmpty())items+=HomeRow("Continue watching",continuing)
        if(prefs.showRecent)m.progress.map { it.title }.distinctBy { it.key }.filterNot { it.key in continuingKeys }
            .takeIf { it.isNotEmpty() }?.let { items+=HomeRow("Recently watched",it) }
        if(prefs.showMyList && m.saved.isNotEmpty())items+=HomeRow("My list",m.saved)
        // One scope per filter change, rather than a digest for every shelf/update.
        val scope="browse:${filterHash()}"
        m.shelves.forEach { shelf -> if(shelf.titles.isNotEmpty())items+=HomeRow(shelf.name,shelf.titles,"${shelf.id}:$scope") }
        homeRows=items
        return items
    }
    private fun resultRows():List<HomeRow> {
        val query=m.searchText.trim()
        if(resultsShelves===m.searchShelves && resultsQuery==query)return resultRows
        resultsShelves=m.searchShelves;resultsQuery=query
        val scope="search:${hash(query)}"
        resultRows=m.searchShelves.filter { it.titles.isNotEmpty() }.map { HomeRow(it.name,it.titles,"${it.id}:$scope") }
        return resultRows
    }
    private fun saveShelf(key:String,state:Parcelable) {
        shelfStates[key]=state
        while(shelfStates.size>64)shelfStates.remove(shelfStates.keys.first())
    }
    private fun filterTarget():View? = if(filters.isShown)
        lastFilterFocus?.let { filterRow.findTag(it) } ?: (0 until filterRow.childCount).map { filterRow.getChildAt(it) }.firstOrNull { it.isFocusable } else null
    private fun applyFilter(filter:CatalogFilter) { focusRestore.cancel();hideKeyboard();m.selectCatalog(filter) }
    private fun updateFilters() {
        filters.visibility=if(m.searchText.isEmpty() && m.catalogDefinitions.any { it.canBrowse })View.VISIBLE else View.GONE
        if(filterDefinitions===m.catalogDefinitions && renderedFilter==m.catalogFilter) {
            input.nextFocusDownId=filterTarget()?.id ?: View.NO_ID
            return
        }
        val focused=view.findFocus()?.tag?.toString()?.takeIf { it.startsWith("catalog-filter:") }
        filterDefinitions=m.catalogDefinitions;renderedFilter=m.catalogFilter;filterRow.removeAllViews()
        val available=m.catalogDefinitions.filter { it.canBrowse }
        val current=m.catalogFilter
        fun label(type:String)=if(type=="movie")"Movies" else "Series"
        fun button(text:String,description:String,key:String,action:()->Unit) {
            val tag="catalog-filter:"+key.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,40}")) }.orEmpty().ifEmpty { hash(key) }
            val control=a.action("$text ▾",description,click=action).apply {
                this.tag=tag;id=View.generateViewId();maxLines=1;ellipsize=TextUtils.TruncateAt.END
                nextFocusUpId=input.id;setOnFocusChangeListener { _,hasFocus -> if(hasFocus)lastFilterFocus=tag }
            }
            filterRow.addView(control,LinearLayout.LayoutParams(-2,a.dp(44)).apply { marginEnd=a.dp(8) })
        }
        val types=available.map { it.type }.distinct()
        if(types.size>1)button(if(current.type.isEmpty())"All types" else label(current.type),"Catalog type","type") {
            val values=listOf("")+types
            filterDialog=a.chooseOption("Type",values.map { if(it.isEmpty())"All types" else label(it) },values.indexOf(current.type)) { index -> applyFilter(CatalogFilter(type=values[index])) }
        }
        val catalogs=available.filter { current.type.isEmpty() || it.type==current.type }
        val selected=catalogs.firstOrNull { it.key==current.catalogKey }
        if(catalogs.size>1)button(selected?.name ?: "All catalogs","Catalog","catalog") {
            val names=listOf("All catalogs")+catalogs.map { catalogLabel(it,catalogs) }
            filterDialog=a.chooseOption("Catalog",names,selected?.let { catalogs.indexOf(it)+1 } ?: 0) { index ->
                val chosen=catalogs.getOrNull(index-1)
                val extras=chosen?.extras.orEmpty().filter { it.required && it.options.isNotEmpty() }.associate { it.name to it.options.first() }
                applyFilter(current.copy(catalogKey=chosen?.key.orEmpty(),extras=extras))
            }
        }
        val extraCatalog=selected ?: catalogs.singleOrNull()
        extraCatalog?.let { catalog -> catalog.extras.filter { it.options.isNotEmpty() && it.name !in setOf("search","skip") }.forEach { extra ->
            val name=if(extra.name=="genre" && extra.options.all { it.matches(Regex("[0-9]{4}")) })"Year"
                else extra.name.replaceFirstChar { it.uppercaseChar() }
            val value=current.extras[extra.name].orEmpty()
            val any=if(name=="Genre")"All genres" else "Any ${name.lowercase()}"
            button(value.ifBlank { if(extra.required)"Choose ${name.lowercase()}" else any },name,extra.name) {
                val values=if(extra.required)extra.options else listOf("")+extra.options
                filterDialog=a.chooseOption(name,values.map { it.ifEmpty { any } },values.indexOf(value)) { index ->
                    val extras=current.extras.toMutableMap()
                    if(values[index].isEmpty())extras.remove(extra.name) else extras[extra.name]=values[index]
                    applyFilter(current.copy(catalogKey=catalog.key,extras=extras))
                }
            }
        } }
        val controls=(0 until filterRow.childCount).map { filterRow.getChildAt(it) }
        controls.forEachIndexed { index,control ->
            control.nextFocusLeftId=controls.getOrNull(index-1)?.id ?: control.id
            control.nextFocusRightId=controls.getOrNull(index+1)?.id ?: control.id
        }
        input.nextFocusDownId=filterTarget()?.id ?: View.NO_ID
        if(focused!=null && filters.isShown)focusRestore.start(waitForWindowFocus=true) { filterRow.findTag(focused) ?: filterTarget() ?: input }
    }
    override fun update() {
        val text=m.searchText
        if(input.text.toString()!=text) {
            focusRestore.cancel()
            val start=input.selectionStart.coerceIn(0,text.length)
            val end=input.selectionEnd.coerceIn(0,text.length)
            syncingQuery=true
            try { input.setText(text);input.setSelection(start,end) }
            finally { syncingQuery=false }
        }
        val searching=m.searchText.trim().length>=2
        updateFilters()
        if(capabilities.addons!==m.addons)capabilities=BrowseAddonCapabilities(m.addons)
        val recovery=addonRecovery(m,capabilities,if(searching)capabilities.search || m.catalogDefinitions.any { it.canSearch } else capabilities.home || m.catalogDefinitions.any { it.canBrowse },
            if(searching)"search movies and shows" else "browse movies and shows")
        addonMessage.text=recovery.orEmpty();addonNotice.visibility=if(recovery==null)View.GONE else View.VISIBLE
        clear.visibility=if(m.searchText.isNotEmpty())View.VISIBLE else View.GONE
        submit.nextFocusRightId=if(clear.visibility==View.VISIBLE)clear.id else View.NO_ID
        home.visibility=if(searching)View.GONE else View.VISIBLE;results.visibility=if(searching && recovery==null)View.VISIBLE else View.GONE
        val shortQuery=m.searchText.isNotBlank() && !searching
        summary.visibility=if(shortQuery || searching && !m.busy && m.results.isNotEmpty())View.VISIBLE else View.GONE
        summary.text=if(shortQuery)"Enter at least 2 characters" else m.results.size.toString()+" "+if(m.results.size==1)"title" else "titles"
        if(searching) {
            if(lastQuery!=m.searchText) { resultsState=null;results.scrollToPosition(0) }
            lastQuery=m.searchText
            searchRows.submitList(resultRows()) {
                if(m.results.isNotEmpty())resultsState?.let { results.layoutManager?.onRestoreInstanceState(it);resultsState=null }
            }
            emptyTitle.text="No results"
            emptyMessage.text="Try another title or check the spelling."
            emptyRefresh.visibility=View.GONE
            empty.visibility=if(recovery==null && !m.busy && m.results.isEmpty() && m.error==null)View.VISIBLE else View.GONE
        } else {
            lastQuery=m.searchText
            val items=homeRows()
            rows.submitList(items) {
                if(items.isNotEmpty())homeState?.let { home.layoutManager?.onRestoreInstanceState(it);homeState=null }
            }
            emptyTitle.text="Nothing to browse yet"
            emptyMessage.text="Search for a title, or refresh your catalog."
            emptyRefresh.visibility=View.VISIBLE
            empty.visibility=if(recovery==null && !m.busy && items.isEmpty() && m.error==null)View.VISIBLE else View.GONE
        }
    }
    override fun focusTarget():View=input
    override fun restoreFocus(tag:String,parentTag:String?):Boolean {
        if(!tag.startsWith("title:"))return false
        return focusRestore.start {
            val list=if(results.isShown)results else home
            val adapter=if(results.isShown)searchRows else rows
            if(parentTag=="search-results" || parentTag?.startsWith("shelf:")==true) {
                val index=adapter.currentList.indexOfFirst { if(parentTag=="search-results")it.titles.any { title -> "title:${title.key}"==tag } else "shelf:${it.key}"==parentTag }
                if(index<0) { if(m.busy)null else input }
                else {
                    val card=list.findViewHolderForAdapterPosition(index)?.itemView as? ShelfCard
                    if(card==null)list.scrollToPosition(index)
                    card?.focusTitle(tag)
                }
            } else list.findTag(tag) ?: input
        }
    }
    override fun saveState():Parcelable {
        listOf(home,results).forEach { list -> for(i in 0 until list.childCount)(list.getChildAt(i) as? ShelfCard)?.let { card -> card.saveState()?.let { saveShelf(card.shelfKey,it) } } }
        return Bundle().apply {
            putParcelable("home",home.layoutManager?.onSaveInstanceState());putParcelable("results",results.layoutManager?.onSaveInstanceState())
            putBundle("shelves",Bundle().apply { shelfStates.forEach { (name,state)->putParcelable(name,state) } })
        }
    }
    @Suppress("DEPRECATION") override fun restoreState(state:Parcelable?) {
        if(state is Bundle) {
            homeState=state.getParcelable("home");resultsState=state.getParcelable("results")
            state.getBundle("shelves")?.let { bundle -> bundle.keySet().forEach { name -> bundle.getParcelable<Parcelable>(name)?.let { shelfStates[name]=it } } }
        } else homeState=state
    }
    override fun background() { focusRestore.cancel();hideKeyboard() }
    override fun close() { background();filterDialog?.dismiss();filterDialog=null;home.adapter=null;results.adapter=null }
}

