package com.nuvio.tv.relay.standalone

/** Serialises Watch Next row replacement between the sync service and the manual Sync Now action. */
internal object PublishLock {
    val lock = Any()
}
