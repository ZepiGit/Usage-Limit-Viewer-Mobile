package com.usagelimits.core.notifications

/** Only the first launch may ask automatically; a later request is the person's choice. */
fun shouldAskNotificationPermission(sdkLevel: Int, alreadyAsked: Boolean, alreadyGranted: Boolean): Boolean =
    sdkLevel >= 33 && !alreadyAsked && !alreadyGranted
