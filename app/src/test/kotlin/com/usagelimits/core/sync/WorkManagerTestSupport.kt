package com.usagelimits.core.sync

import androidx.work.impl.WorkManagerImpl
import androidx.work.testing.WorkManagerTestInitHelper

/** Robolectric reuses WorkManager statics across classes; never retain a closed test database. */
internal fun resetTestWorkManager() {
    WorkManagerTestInitHelper.closeWorkDatabase()
    WorkManagerImpl.setDelegate(null)
}
