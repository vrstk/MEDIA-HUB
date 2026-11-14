package com.mediahub.mobile.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Room schema draft, intentionally not instantiated before migrations and import logic exist. */
@Entity(tableName = "libraries")
data class LibraryEntity(
    @PrimaryKey val id: String,
    val title: String,
    val libraryKind: String
)

@Entity(tableName = "source_profiles")
data class SourceProfileEntity(
    @PrimaryKey val id: String,
    val sourceKey: String,
    val sourceUrl: String,
    val displayName: String?,
    val sourceSnapshot: String?
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
