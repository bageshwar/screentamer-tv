package com.screentamer.agent.data

/**
 * Friendly names for common Fire TV streaming packages, plus helper predicates.
 */
object KnownApps {

    private val NAMES = mapOf(
        "com.google.android.youtube.tv" to "YouTube",
        "com.google.android.apps.youtube.tvunplugged" to "YouTube TV",
        "com.netflix.ninja" to "Netflix",
        "com.amazon.amazonvideo.livingroom" to "Prime Video",
        "com.disney.disneyplus" to "Disney+",
        "com.hulu.livingroomplus" to "Hulu",
        "com.hbomax" to "Max",
        "com.hbo.hbonow" to "HBO Now",
        "com.peacocktv.brownstone" to "Peacock",
        "com.paramountplus.livingroom" to "Paramount+",
        "com.apple.appletv" to "Apple TV+",
        "com.cbs.ott" to "Paramount+",
        "com.nbcuni.nbc.comcasttv.android.tvlauncher" to "NBC",
        "com.turner.cnvideoapp" to "CNN",
        "com.pluto.tv" to "Pluto TV",
        "com.tubitv" to "Tubi",
        "com.roku.web.trc.testapp" to "Roku Channel",
        "com.vudu.airplay" to "Vudu",
        "com.vudu.tv" to "Vudu",
        "com.fandangonow" to "Vudu",
        "com.amazon.tv.launcher" to "Fire TV Home",
        "com.amazon.tv.mediabrowser" to "Fire TV",
        "com.amazon.tv.purchase" to "Amazon Store",
        "com.amazon.firetv.android.leanbacklauncher" to "Fire TV Home",
        "com.spotify.tv" to "Spotify",
        "com.amazon.mp3" to "Amazon Music",
        "tv.pandora.firetv" to "Pandora",
        "com.sling" to "Sling TV",
        "com.google.android.gms" to "Google Play Services",
        "com.amazon.device.messaging" to "System",
        "com.amazon.tv.settings" to "Fire TV Settings",
        "com.amazon.tv.settings.v2" to "Fire TV Settings",
    )

    fun displayName(pkg: String): String = NAMES[pkg] ?: pkg

    /** Packages we should never count or block (system / launcher / ourselves). */
    fun isSystemish(pkg: String): Boolean {
        if (pkg == "com.screentamer.agent") return true
        return pkg == "com.amazon.tv.launcher" ||
            pkg == "com.amazon.firetv.android.leanbacklauncher" ||
            pkg == "com.android.systemui" ||
            pkg == "com.amazon.tv.mediabrowser" ||
            pkg == "com.amazon.tv.purchase" ||
            pkg == "com.amazon.device.messaging" ||
            pkg == "com.google.android.gms"
    }
}
