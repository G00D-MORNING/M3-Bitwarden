package com.framepersecond.m3bitwarden.autofill.match

/**
 * Popular apps and the websites their accounts belong to, for apps that publish no Digital Asset
 * Links or before those were checked. A package name alone proves nothing (anyone can sideload an
 * app with the same id), so matches from this table are always offered with a confirmation; once
 * confirmed, the link is bound to the app's signing certificate.
 */
object KnownApps {
    private val android: Map<String, List<String>> = mapOf(
        "com.amazon.mShop.android.shopping" to listOf("amazon.de", "amazon.com", "amazon.at", "amazon.co.uk"),
        "com.amazon.avod.thirdpartyclient" to listOf("amazon.de", "amazon.com", "primevideo.com"),
        "com.audible.application" to listOf("audible.de", "audible.com", "amazon.de"),
        "com.paypal.android.p2pmobile" to listOf("paypal.com"),
        "com.ebay.mobile" to listOf("ebay.de", "ebay.com"),
        "com.ebay.kleinanzeigen" to listOf("kleinanzeigen.de"),
        "com.netflix.mediaclient" to listOf("netflix.com"),
        "com.spotify.music" to listOf("spotify.com"),
        "com.disney.disneyplus" to listOf("disneyplus.com"),
        "de.hafas.android.db" to listOf("bahn.de"),
        "com.starfinanz.smob.android.sfinanzstatus" to listOf("sparkasse.de"),
        "de.dkb.portalapp" to listOf("dkb.de"),
        "de.ingdiba.bankingapp" to listOf("ing.de"),
        "de.number26.android" to listOf("n26.com"),
        "de.comdirect.android" to listOf("comdirect.de"),
        "de.zalando.mobile" to listOf("zalando.de", "zalando.com"),
        "com.yopeso.lieferando" to listOf("lieferando.de"),
        "de.dhl.paket" to listOf("dhl.de"),
        "com.lufthansa.android.lufthansa" to listOf("lufthansa.com", "miles-and-more.com"),
        "com.booking" to listOf("booking.com"),
        "com.airbnb.android" to listOf("airbnb.de", "airbnb.com"),
        "com.ubercab" to listOf("uber.com"),
        "com.instagram.android" to listOf("instagram.com"),
        "com.facebook.katana" to listOf("facebook.com"),
        "com.twitter.android" to listOf("x.com", "twitter.com"),
        "com.linkedin.android" to listOf("linkedin.com"),
        "com.pinterest" to listOf("pinterest.com"),
        "com.snapchat.android" to listOf("snapchat.com"),
        "com.zhiliaoapp.musically" to listOf("tiktok.com"),
        "com.discord" to listOf("discord.com"),
        "com.reddit.frontpage" to listOf("reddit.com"),
        "tv.twitch.android.app" to listOf("twitch.tv"),
        "com.valvesoftware.android.steam.community" to listOf("steampowered.com", "steamcommunity.com"),
        "com.microsoft.office.outlook" to listOf("microsoft.com", "live.com", "outlook.com"),
        "com.google.android.gm" to listOf("google.com"),
        "com.google.android.youtube" to listOf("youtube.com", "google.com"),
        "com.dropbox.android" to listOf("dropbox.com"),
        "com.github.android" to listOf("github.com"),
        "com.duolingo" to listOf("duolingo.com"),
        "com.strava" to listOf("strava.com"),
    )

    /** Website domains the app with [appId] is known to use, empty when unknown. */
    fun domainsFor(appId: String?): Set<String> = appId?.let { android[it] }?.toSet().orEmpty()

    /**
     * Guess from a reverse-domain package name: "de.dkb.portalapp" → "dkb.de",
     * "com.paypal.android.p2pmobile" → "paypal.com". Null for ids that do not start like a domain.
     */
    fun reverseDomainGuess(appId: String?): String? {
        val parts = appId?.split('.')?.filter { it.isNotBlank() } ?: return null
        if (parts.size < 2) return null
        val tld = parts[0].lowercase()
        if (tld !in TLDS) return null
        val name = parts[1].lowercase()
        if (name.length < 2 || name in GENERIC) return null
        return "$name.$tld"
    }

    private val TLDS = setOf("com", "de", "net", "org", "io", "at", "ch", "fr", "nl", "it", "es", "eu", "app", "dev", "tv", "co", "me", "uk", "se", "dk", "no", "fi", "pl", "be")
    private val GENERIC = setOf("google", "android", "example", "github", "facebook", "microsoft", "samsung", "huawei", "xiaomi", "app", "apps", "mobile")
}
