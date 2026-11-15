package com.mediahub.mobile.catalog

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "catalog_sources")
internal data class CatalogSource(
    @PrimaryKey val id: String,
    val displayName: String,
    val notes: String
)

@Entity(
    tableName = "source_profiles",
    foreignKeys = [ForeignKey(entity = CatalogSource::class, parentColumns = ["id"], childColumns = ["sourceKey"])],
    indices = [Index("sourceKey")]
)
internal data class SourceProfile(
    @PrimaryKey val id: String,
    val sourceKey: String,
    val sourceUrl: String,
    val displayName: String?,
    val sourceSnapshot: String?,
    val aliases: String?,
    val sourceStatus: String?,
    val hairColor: String?,
    val tagsRaw: String?,
    val categoriesRaw: String?,
    val galleryCount: Int?,
    val archivedUrl: String?,
    val nameSourceUrl: String?,
    val siteId: String?,
    val reviewReason: String?,
    val reviewStatus: String?,
    val metadataJson: String,
    val searchText: String,
    val sortName: String
)

@Entity(
    tableName = "profile_names",
    foreignKeys = [ForeignKey(entity = SourceProfile::class, parentColumns = ["id"], childColumns = ["profileId"])]
)
internal data class ProfileName(
    @PrimaryKey val profileId: String,
    val title: String,
    val searchTitle: String
)

@Entity(
    tableName = "taxonomy_terms",
    foreignKeys = [ForeignKey(entity = CatalogSource::class, parentColumns = ["id"], childColumns = ["sourceKey"])],
    indices = [Index("sourceKey"), Index("kind", "sortLabel")]
)
internal data class TaxonomyTerm(
    @PrimaryKey val id: String,
    val sourceKey: String,
    val sourceUrl: String,
    val kind: String,
    val label: String,
    val labelRu: String,
    val sourceCount: Int?,
    val sourceSnapshot: String?,
    val searchText: String,
    val sortLabel: String
)

@Entity(
    tableName = "profile_terms",
    primaryKeys = ["profileId", "termId", "sourceField"],
    foreignKeys = [
        ForeignKey(entity = SourceProfile::class, parentColumns = ["id"], childColumns = ["profileId"]),
        ForeignKey(entity = TaxonomyTerm::class, parentColumns = ["id"], childColumns = ["termId"])
    ],
    indices = [Index("termId")]
)
internal data class ProfileTerm(val profileId: String, val termId: String, val sourceField: String)

@Entity(tableName = "catalog_imports")
internal data class CatalogImport(
    @PrimaryKey val digest: String,
    val importedAt: Long,
    val profileSnapshot: String?,
    val taxonomySnapshot: String?,
    val manifestJson: String?,
    val profileCount: Int,
    val categoryCount: Int,
    val tagCount: Int,
    val linkCount: Int
)

internal data class CatalogSummary(
    val profiles: Int,
    val categories: Int,
    val tags: Int,
    val unnamed: Int
)

internal data class ProfileRow(
    val id: String,
    val archiveName: String?,
    val localName: String?,
    val sourceName: String,
    val sourceSnapshot: String?,
    val aliases: String?,
    val sourceUrl: String
) {
    val title: String get() = localName ?: archiveName ?: "Без имени"
}

@Dao
internal interface CatalogDao {
    @Upsert suspend fun writeSources(items: List<CatalogSource>)
    @Upsert suspend fun writeProfiles(items: List<SourceProfile>)
    @Upsert suspend fun writeTaxonomy(items: List<TaxonomyTerm>)
    @Query("DELETE FROM profile_terms WHERE profileId IN (:profileIds)")
    suspend fun clearLinksFor(profileIds: List<String>)
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun writeLinks(items: List<ProfileTerm>)
    @Insert suspend fun recordImport(item: CatalogImport)
    @Query("SELECT EXISTS(SELECT 1 FROM catalog_imports WHERE digest = :digest)")
    suspend fun hasImport(digest: String): Boolean

    @Query("""
        SELECT (SELECT COUNT(*) FROM source_profiles) AS profiles,
               (SELECT COUNT(*) FROM taxonomy_terms WHERE kind = 'category') AS categories,
               (SELECT COUNT(*) FROM taxonomy_terms WHERE kind = 'tag') AS tags,
               (SELECT COUNT(*) FROM source_profiles p LEFT JOIN profile_names n ON p.id = n.profileId
                WHERE p.displayName IS NULL AND n.profileId IS NULL) AS unnamed
    """)
    fun summary(): Flow<CatalogSummary>

    @Query("""
        SELECT p.id, p.displayName AS archiveName, n.title AS localName, s.displayName AS sourceName,
               p.sourceSnapshot, p.aliases, p.sourceUrl
        FROM source_profiles p JOIN catalog_sources s ON p.sourceKey = s.id
        LEFT JOIN profile_names n ON p.id = n.profileId
        WHERE (:unnamedOnly = 0 OR (p.displayName IS NULL AND n.profileId IS NULL))
          AND (:termId IS NULL OR EXISTS(
              SELECT 1 FROM profile_terms pt WHERE pt.termId = :termId AND pt.profileId = p.id))
          AND (:query = '' OR INSTR(p.searchText, :query) > 0 OR INSTR(n.searchTitle, :query) > 0)
        ORDER BY COALESCE(n.searchTitle, p.sortName), p.id LIMIT :limit OFFSET :offset
    """)
    suspend fun profiles(query: String, unnamedOnly: Boolean, termId: String?, offset: Int, limit: Int): List<ProfileRow>

    @Query("""
        SELECT * FROM taxonomy_terms
        WHERE kind = :kind AND (:query = '' OR INSTR(searchText, :query) > 0)
        ORDER BY sortLabel, id LIMIT :limit OFFSET :offset
    """)
    suspend fun terms(kind: String, query: String, offset: Int, limit: Int): List<TaxonomyTerm>

    @Query("""
        SELECT t.* FROM taxonomy_terms t
        WHERE EXISTS (SELECT 1 FROM profile_terms pt WHERE pt.profileId = :profileId AND pt.termId = t.id)
        ORDER BY t.kind, t.sortLabel, t.id
    """)
    suspend fun profileTerms(profileId: String): List<TaxonomyTerm>

    @Upsert suspend fun saveProfileName(name: ProfileName)
    @Query("DELETE FROM profile_names WHERE profileId = :id")
    suspend fun resetProfileName(id: String)
}

@Database(
    entities = [
        CatalogSource::class, SourceProfile::class, ProfileName::class,
        TaxonomyTerm::class, ProfileTerm::class, CatalogImport::class
    ],
    version = 1,
    exportSchema = true
)
internal abstract class CatalogDatabase : RoomDatabase() {
    abstract fun catalog(): CatalogDao

    companion object {
        fun open(context: Context): CatalogDatabase =
            Room.databaseBuilder(context, CatalogDatabase::class.java, "mediahub-catalog.db").build()
    }
}
