package dev.kviklet.kviklet.db

import dev.kviklet.kviklet.db.util.BaseEntity
import dev.kviklet.kviklet.service.EntityNotFound
import dev.kviklet.kviklet.service.dto.AnnotationSource
import dev.kviklet.kviklet.service.dto.TableAnnotation
import dev.kviklet.kviklet.service.dto.TableAnnotationId
import dev.kviklet.kviklet.service.dto.utcTimeNow
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

@Entity
@Table(name = "table_annotation")
class TableAnnotationEntity(
    @Column(name = "table_pattern", nullable = false)
    var tablePattern: String = "",

    @Column(name = "connection_selector", nullable = false)
    var connectionSelector: String = "*",

    @Column(name = "message", nullable = false)
    var message: String = "",

    @Column(name = "runbook_url")
    var runbookUrl: String? = null,

    @Column(name = "requires_acknowledgement", nullable = false)
    var requiresAcknowledgement: Boolean = true,

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false)
    var source: AnnotationSource = AnnotationSource.MANUAL,

    @Column(name = "external_id")
    var externalId: String? = null,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime = utcTimeNow(),
) : BaseEntity() {

    fun toDto() = TableAnnotation(
        id = id?.let { TableAnnotationId(it) },
        tablePattern = tablePattern,
        connectionSelector = connectionSelector,
        message = message,
        runbookUrl = runbookUrl,
        requiresAcknowledgement = requiresAcknowledgement,
        source = source,
        externalId = externalId,
        updatedAt = updatedAt,
    )
}

@Repository
interface TableAnnotationRepository : JpaRepository<TableAnnotationEntity, String> {
    fun findBySource(source: AnnotationSource): List<TableAnnotationEntity>
}

@Service
class TableAnnotationAdapter(private val tableAnnotationRepository: TableAnnotationRepository) {

    @Transactional(readOnly = true)
    fun findAll(): List<TableAnnotation> = tableAnnotationRepository.findAll().map { it.toDto() }

    @Transactional(readOnly = true)
    fun findById(id: TableAnnotationId): TableAnnotation =
        tableAnnotationRepository.findByIdOrNull(id.toString())?.toDto()
            ?: throw EntityNotFound("Table annotation not found", "Table annotation with id $id does not exist")

    @Transactional
    fun create(annotation: TableAnnotation): TableAnnotation = tableAnnotationRepository.save(
        TableAnnotationEntity().apply { applyFrom(annotation) },
    ).toDto()

    @Transactional
    fun update(id: TableAnnotationId, annotation: TableAnnotation): TableAnnotation {
        val entity = tableAnnotationRepository.findByIdOrNull(id.toString())
            ?: throw EntityNotFound("Table annotation not found", "Table annotation with id $id does not exist")
        return tableAnnotationRepository.save(entity.apply { applyFrom(annotation) }).toDto()
    }

    @Transactional
    fun delete(id: TableAnnotationId) {
        tableAnnotationRepository.deleteById(id.toString())
    }

    /**
     * Replaces every [AnnotationSource.SYNCED] row with [annotations], matching existing rows by
     * their external id so ids stay stable across syncs. Rows that disappeared upstream are deleted
     * and [AnnotationSource.MANUAL] rows are never touched, so a sync can never discard an admin's
     * own annotations.
     */
    @Transactional
    fun replaceSynced(annotations: List<TableAnnotation>) {
        val existing = tableAnnotationRepository.findBySource(AnnotationSource.SYNCED)
            .associateBy { it.externalId }
        val incomingIds = annotations.mapNotNull { it.externalId }.toSet()

        tableAnnotationRepository.deleteAll(existing.filterKeys { it !in incomingIds }.values)
        tableAnnotationRepository.saveAll(
            annotations.map { annotation ->
                (existing[annotation.externalId] ?: TableAnnotationEntity()).apply { applyFrom(annotation) }
            },
        )
    }
}

private fun TableAnnotationEntity.applyFrom(annotation: TableAnnotation) {
    tablePattern = annotation.tablePattern
    connectionSelector = annotation.connectionSelector
    message = annotation.message
    runbookUrl = annotation.runbookUrl
    requiresAcknowledgement = annotation.requiresAcknowledgement
    source = annotation.source
    externalId = annotation.externalId
    updatedAt = annotation.updatedAt
}
