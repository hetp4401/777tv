package xyz.billybishop4.fleet.tv

/** Only reduced, account-scoped metadata enters the native player's action row. */
internal data class YouTubePlayerActionState(
    val channel:YouTubeChannel?=null,
    val rating:YouTubeRating?=null,
    val signedIn:Boolean=false,
    val busy:Boolean=false,
    val pending:String?=null,
    val error:String?=null,
)

/** Shares the existing action session with sheets. Construction and metadata updates
 * allocate no dialog and issue no request; account work starts with an explicit action. */
internal class YouTubePlayerActions(
    a:MainActivity,video:YouTubeVideo,changed:(YouTubePlayerActionState)->Unit,
) {
    private val actions=YouTubeVideoActions(a,video,changed)
    fun metadata(details:YouTubeWatchDetails?)=actions.playerMetadata(details)
    fun rate(rating:YouTubeRating)=actions.playerRate(rating)
    fun subscribe()=actions.playerSubscribe()
    fun save()=actions.playerSave()
    fun channel()=actions.playerChannel()
    fun more()=actions.show()
    fun accountChanged()=actions.playerAccountChanged()
    fun close()=actions.close()
}
