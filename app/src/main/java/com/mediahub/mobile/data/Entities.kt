package com.mediahub.mobile.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Reserved schema for named libraries and persistent transfer jobs. */
@Entity(tableName = "libraries")
data class LibraryEntity(
    @PrimaryKey val id: String,
    val title: String,
    val libraryKind: String
)

@Entity(tableName = "transfer_jobs")
data class TransferJobEntity(
    @PrimaryKey val id: String,
    val sourceProvider: String,
    val sourceNodeId: String,
    val destinationProvider: String,
    val destinationFolderId: String,
    val operation: String,
    val status: String,
    val createdAtEpochMillis: Long,
    val bytesTransferred: Long
)
