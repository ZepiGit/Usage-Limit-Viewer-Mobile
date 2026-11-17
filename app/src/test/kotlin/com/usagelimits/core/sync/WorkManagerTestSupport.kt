package com.usagelimits.core.sync

import androidx.work.impl.WorkManagerImpl
import androidx.work.testing.WorkManagerTestInitHelper
import org.robolectric.util.ReflectionHelpers

/** Robolectric reuses WorkManager statics across classes; never retain a closed test database. */
internal fun resetTestWorkManager() {
    WorkManagerTestInitHelper.closeWorkDatabase()
    WorkManagerImpl.setDelegate(null)
    // setDelegate leaves the default behind; clear it under WorkManager's own singleton lock.
    synchronized(ReflectionHelpers.getStaticField<Any>(WorkManagerImpl::class.java, "sLock")) {
        ReflectionHelpers.setStaticField(WorkManagerImpl::class.java, "sDefaultInstance", null)
    }
}
