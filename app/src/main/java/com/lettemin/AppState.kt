package com.lettemin

object AppState {
    @Volatile var serviceRunning = false
    @Volatile var teensyAttached = false
}
