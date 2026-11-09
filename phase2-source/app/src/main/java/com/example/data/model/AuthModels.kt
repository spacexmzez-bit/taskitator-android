package com.example.data.model

import org.json.JSONObject

sealed class AuthState {
    object SignedOut : AuthState()
    object Connecting : AuthState()
    data class Authenticated(
        val clerkUserId: String,
        val workerUser: String,
        val username: String,
        val isOffline: Boolean = false
    ) : AuthState()
    data class Unlinked(
        val clerkUserId: String,
        val token: String
    ) : AuthState()
    data class SessionExpired(val message: String) : AuthState()
    data class AccountDeleted(val message: String) : AuthState()
    data class Error(val message: String) : AuthState()
}

enum class SyncState {
    IDLE,
    PULLING,
    PUSHING,
    OFFLINE,
    CONFLICT,
    ERROR,
    UP_TO_DATE
}

data class ConflictTaskItem(
    val taskId: String,
    val localTitle: String,
    val remoteTitle: String,
    val localStatus: String,
    val remoteStatus: String,
    val conflictingFields: List<String>,
    val localJson: JSONObject,
    val remoteJson: JSONObject
)

data class SyncConflictReport(
    val conflictingTasks: List<ConflictTaskItem>,
    val baseSnapshot: JSONObject,
    val localSnapshot: JSONObject,
    val remoteSnapshot: JSONObject,
    val backupFilePath: String
)
