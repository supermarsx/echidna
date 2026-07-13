package com.echidna.app.data

/**
 * Curated app metadata plus local package-name heuristics for the whitelist editor.
 *
 * The catalog is deliberately modest: exact package ids are used only where they are common and
 * high confidence. Unknown installed apps can still receive a category through string heuristics,
 * but those heuristics never imply device privileges or background package visibility beyond the
 * launchable packages the app already enumerates locally.
 */
object CommonApps {

    enum class Category(val label: String, val suggested: Boolean) {
        VOICE("Voice chat", true),
        VIDEO("Video calls", true),
        CALLS("Calls", true),
        MESSAGING("Messaging", true),
        SOCIAL("Social", true),
        GAMES("Games", true),
        STREAMING("Streaming", true),
        MEDIA("Media", false),
        PRODUCTIVITY("Productivity", false)
    }

    data class Entry(val displayName: String, val category: Category)

    private val KNOWN: Map<String, Entry> = mapOf(
        // Voice chat and group audio.
        "com.discord" to Entry("Discord", Category.VOICE),
        "com.teamspeak3.client.android" to Entry("TeamSpeak 3", Category.VOICE),
        "im.vector.app" to Entry("Element", Category.VOICE),
        "com.wire" to Entry("Wire", Category.VOICE),
        "com.slack" to Entry("Slack", Category.VOICE),
        "com.clubhouse.app" to Entry("Clubhouse", Category.VOICE),

        // Video calls and meetings.
        "us.zoom.videomeetings" to Entry("Zoom", Category.VIDEO),
        "com.google.android.apps.tachyon" to Entry("Google Meet", Category.VIDEO),
        "com.skype.raider" to Entry("Skype", Category.VIDEO),
        "com.microsoft.teams" to Entry("Microsoft Teams", Category.VIDEO),
        "com.cisco.webex.meetings" to Entry("Webex Meetings", Category.VIDEO),
        "org.jitsi.meet" to Entry("Jitsi Meet", Category.VIDEO),

        // Calling / VoIP.
        "com.google.android.dialer" to Entry("Phone", Category.CALLS),
        "com.samsung.android.dialer" to Entry("Samsung Phone", Category.CALLS),
        "com.google.android.apps.googlevoice" to Entry("Google Voice", Category.CALLS),
        "org.linphone" to Entry("Linphone", Category.CALLS),
        "com.viber.voip" to Entry("Viber", Category.CALLS),

        // Messaging apps that commonly include calls or voice notes.
        "com.whatsapp" to Entry("WhatsApp", Category.MESSAGING),
        "com.whatsapp.w4b" to Entry("WhatsApp Business", Category.MESSAGING),
        "org.telegram.messenger" to Entry("Telegram", Category.MESSAGING),
        "org.thoughtcrime.securesms" to Entry("Signal", Category.MESSAGING),
        "com.facebook.orca" to Entry("Messenger", Category.MESSAGING),
        "jp.naver.line.android" to Entry("LINE", Category.MESSAGING),
        "com.kakao.talk" to Entry("KakaoTalk", Category.MESSAGING),
        "com.tencent.mm" to Entry("WeChat", Category.MESSAGING),
        "com.imo.android.imoim" to Entry("imo", Category.MESSAGING),
        "com.groupme.android" to Entry("GroupMe", Category.MESSAGING),
        "com.google.android.apps.messaging" to Entry("Google Messages", Category.MESSAGING),
        "com.samsung.android.messaging" to Entry("Samsung Messages", Category.MESSAGING),
        "com.google.android.apps.dynamite" to Entry("Google Chat", Category.MESSAGING),

        // Social apps with live, voice-note, or video-message surfaces.
        "com.instagram.android" to Entry("Instagram", Category.SOCIAL),
        "com.instagram.barcelona" to Entry("Threads", Category.SOCIAL),
        "com.snapchat.android" to Entry("Snapchat", Category.SOCIAL),
        "com.facebook.katana" to Entry("Facebook", Category.SOCIAL),
        "com.twitter.android" to Entry("X (Twitter)", Category.SOCIAL),
        "com.reddit.frontpage" to Entry("Reddit", Category.SOCIAL),
        "com.zhiliaoapp.musically" to Entry("TikTok", Category.SOCIAL),
        "com.linkedin.android" to Entry("LinkedIn", Category.SOCIAL),
        "com.tinder" to Entry("Tinder", Category.SOCIAL),

        // Games where users often expect voice effects if the game opens the microphone.
        "com.roblox.client" to Entry("Roblox", Category.GAMES),
        "com.mojang.minecraftpe" to Entry("Minecraft", Category.GAMES),
        "com.epicgames.fortnite" to Entry("Fortnite", Category.GAMES),
        "com.activision.callofduty.shooter" to Entry("Call of Duty: Mobile", Category.GAMES),
        "com.tencent.ig" to Entry("PUBG Mobile", Category.GAMES),
        "com.dts.freefireth" to Entry("Free Fire", Category.GAMES),
        "com.mobile.legends" to Entry("Mobile Legends", Category.GAMES),
        "com.innersloth.spacemafia" to Entry("Among Us", Category.GAMES),
        "com.supercell.brawlstars" to Entry("Brawl Stars", Category.GAMES),
        "com.supercell.clashroyale" to Entry("Clash Royale", Category.GAMES),
        "com.supercell.clashofclans" to Entry("Clash of Clans", Category.GAMES),

        // Streaming / creator apps.
        "tv.twitch.android.app" to Entry("Twitch", Category.STREAMING),
        "com.google.android.youtube" to Entry("YouTube", Category.STREAMING),
        "com.google.android.apps.youtube.creator" to Entry("YouTube Studio", Category.STREAMING),
        "com.streamlabs" to Entry("Streamlabs", Category.STREAMING),
        "com.prism.live" to Entry("PRISM Live", Category.STREAMING),

        // Non-suggested nice names when these show up in persisted state.
        "com.spotify.music" to Entry("Spotify", Category.MEDIA),
        "com.google.android.gm" to Entry("Gmail", Category.PRODUCTIVITY)
    )

    fun canonicalName(packageName: String): String? = KNOWN[packageName]?.displayName

    fun category(packageName: String): Category? = KNOWN[packageName]?.category

    fun categoryFor(packageName: String): Category? =
        category(packageName) ?: inferCategory(packageName)

    fun isSuggested(packageName: String): Boolean =
        categoryFor(packageName)?.suggested == true

    fun isHeuristicSuggestion(packageName: String): Boolean =
        category(packageName) == null && inferCategory(packageName)?.suggested == true

    fun suggestedPackages(): Set<String> =
        KNOWN.filterValues { it.category.suggested }.keys

    /**
     * Best-effort installed-app categorisation. This is intentionally conservative and based only
     * on package names already visible to the app through launcher enumeration or user entry.
     */
    fun inferCategory(packageName: String): Category? {
        val key = packageName.lowercase()
        return when {
            key.hasAny("discord", "teamspeak", "mumble", "guilded", "voicechat", "voice") ->
                Category.VOICE
            key.hasAny("zoom", "meet", "webex", "teams", "skype", "jitsi", "videocall") ->
                Category.VIDEO
            key.hasAny("dialer", "phone", "voip", "linphone", "call") ->
                Category.CALLS
            key.hasAny("whatsapp", "telegram", "signal", "messenger", "viber", "kakao", "wechat", "chat") ->
                Category.MESSAGING
            key.hasAny("twitch", "youtube", "stream", "streamlabs", "studio", "live") ->
                Category.STREAMING
            key.hasAny("roblox", "minecraft", "pubg", "fortnite", "codm", "gaming", "game") ->
                Category.GAMES
            key.hasAny("instagram", "snapchat", "facebook", "twitter", "tiktok", "reddit", "social") ->
                Category.SOCIAL
            else -> null
        }
    }

    private fun String.hasAny(vararg tokens: String): Boolean =
        tokens.any { token -> contains(token) }
}
