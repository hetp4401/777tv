package xyz.billybishop4.fleet.tv

/** Curated dark palettes: immutable values shared by native views, never bitmap wallpapers. */
internal data class ThemePalette(
    val id:String,val name:String,val pairing:String,
    val background:Int,val panel:Int,val raised:Int,val text:Int,val muted:Int,
    val accent:Int,val focusRing:Int,val gradientStart:Int,val gradientEnd:Int,
    val prefersGradient:Boolean=true,
) {
    val surface get()=panel
    val onAccent get()=background
    val focusFill get()=if(id=="classic")0xFF3A3A3D.toInt() else raised
    val pressedFill get()=if(id=="classic")0xFF48484A.toInt() else raised
    val prominentFill get()=if(id=="classic")0xFFF3F3F6.toInt() else accent
    val overlaySurface get()=panel
    val overlayScrim get()=(background and 0x00FFFFFF) or 0xE8000000.toInt()
}

internal object ThemeCatalog {
    private fun color(hex:String)=(0xFF000000L or hex.toLong(16)).toInt()
    private fun palette(id:String,name:String,pairing:String,vararg values:String)=values.map(::color).let { c ->
        ThemePalette(id,name,pairing,c[0],c[1],c[2],c[3],c[4],c[5],c[6],c[7],c[8],id !in listOf("classic","tube","sand","moss"))
    }
    val all:List<ThemePalette> = listOf(
        palette("classic","Classic","Ink & silver","080808","171717","29292C","FFFFFF","AAAAAF","FFFFFF","FFFFFF","080808","080808"),
        palette("midnight","Midnight","Blue & indigo","080D18","121D30","22344D","F5F7FD","B2C0D3","8DBEFF","C0D9FF","172D4B","100D24"),
        palette("ocean","Ocean","Cyan & sea glass","061419","0E242C","183C49","F0F9FC","ABC7D0","72CDE7","A6E5F5","103342","07251F"),
        palette("aurora","Aurora","Mint & deep blue","081611","112A23","1C4234","F2FBF6","AECABD","83D4B3","B8EBD7","0C211E","0E1927"),
        palette("cinema","Cinema","Violet & midnight blue","090815","18162A","2B2643","F8F6FF","BDB8D4","B6A0FF","D6CAFF","15112B","0B1628"),
        palette("rose","Rose","Blush & plum","190D15","2C1827","462A3D","FFF5FA","D6BACB","EAA5C8","F5D0E3","402132","201B3A"),
        palette("ember","Ember","Apricot & burgundy","1A100C","2D1E17","483124","FFF8F1","D1BEB0","EDB28B","F6D4BA","402A1D","271522"),
        palette("sand","Sand","Champagne & sage","17140D","292419","41382A","FBF8F0","CAC4AE","D8C28B","EDE1B9","343020","192824"),
        palette("tube","Tube","Black, white & red","080808","141414","1C1418","FFFFFF","B5B5BD","FF0033","FFFFFF","080808","080808"),
        palette("moss","Moss","Olive & forest","12160B","222918","34402A","F7FAF0","BEC8A8","BDCE8D","DDE8BD","2F3921","112823"),
    )
    private val byId=all.associateBy { it.id }
    fun resolve(id:String):ThemePalette=byId[id]?:all.first()
    fun contains(id:String):Boolean=id in byId
}

/** One foreground MainActivity owns the UI. Applying a preset swaps one immutable palette. */
internal object AppTheme {
    @Volatile var palette:ThemePalette=ThemeCatalog.resolve("classic");private set
    var gradient:Boolean=true;private set
    fun apply(id:String,useGradient:Boolean):Boolean {
        val next=ThemeCatalog.resolve(id)
        if(palette===next && gradient==useGradient)return false
        palette=next;gradient=useGradient;return true
    }
}

/** Settings use the app palette; content surfaces keep their own area's override. */
internal fun Preferences.appearanceFor(route:Route):ThemePalette {
    val area=when(route) {
        is Route.Browse -> route.section
        is Route.Channels -> Section.LIVE
        is Route.Detail,is Route.Sources -> Section.SEARCH
        is Route.Player -> when {
            route.watch.title.type=="youtube" -> Section.YOUTUBE
            route.watch.live -> Section.LIVE
            else -> Section.SEARCH
        }
        else -> null
    }
    return ThemeCatalog.resolve(if(area==null)themeId else themeFor(area))
}
