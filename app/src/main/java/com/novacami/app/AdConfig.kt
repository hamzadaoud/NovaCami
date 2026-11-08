package com.novacami.app

/** AdMob settings for Novacami. */
object AdConfig {
    // App ID lives in AndroidManifest.xml: ca-app-pub-4283478361856726~3439702382
    private const val BANNER_ID = "ca-app-pub-4283478361856726/2754352859"

    // Google's official TEST banner. Debug builds use it so you never click your own live ads
    // (that can get an AdMob account banned). Release builds use the real banner above.
    private const val TEST_BANNER_ID = "ca-app-pub-3940256099942544/6300978111"

    /** false = the banner only shows while streaming is stopped (nothing during calls/streams). */
    const val SHOW_WHILE_STREAMING = true

    fun bannerId(): String = if (BuildConfig.DEBUG) TEST_BANNER_ID else BANNER_ID
}
