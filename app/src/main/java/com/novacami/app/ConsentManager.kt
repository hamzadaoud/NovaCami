package com.novacami.app

import android.app.Activity
import android.util.Log
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform

/**
 * Google's User Messaging Platform (UMP): shows the legally required consent / privacy message
 * (GDPR in EEA/UK/Switzerland, privacy-choice messages for US states) when needed, and does
 * nothing for users who don't need it. The messages themselves are created in the AdMob console
 * under Privacy & messaging.
 */
class ConsentManager(private val activity: Activity) {
    private val info: ConsentInformation = UserMessagingPlatform.getConsentInformation(activity)

    val canRequestAds: Boolean get() = info.canRequestAds()
    val privacyOptionsRequired: Boolean
        get() = info.privacyOptionsRequirementStatus == ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED

    /** Call on every app start. [onDone] runs after the form (if any) is finished. */
    fun gather(onDone: () -> Unit) {
        // To test the EU form on a debug build, add:
        //   ConsentDebugSettings.Builder(activity).setDebugGeography(DebugGeography.DEBUG_GEOGRAPHY_EEA)
        //       .addTestDeviceHashedId("YOUR_HASHED_ID").build()   (hash is printed in Logcat)
        val params = ConsentRequestParameters.Builder().build()
        info.requestConsentInfoUpdate(activity, params, {
            UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { err ->
                if (err != null) Log.w(TAG, "consent form: ${err.message}")
                onDone()
            }
        }, { err ->
            Log.w(TAG, "consent info: ${err.message}")
            onDone()
        })
    }

    /** "Privacy settings" button: lets users change their choice later (required in some regions). */
    fun showPrivacyOptions(onDone: () -> Unit) {
        UserMessagingPlatform.showPrivacyOptionsForm(activity) { err ->
            if (err != null) Log.w(TAG, "privacy options: ${err.message}")
            onDone()
        }
    }

    companion object { private const val TAG = "NovacamiConsent" }
}
