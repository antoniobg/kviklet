package dev.kviklet.kviklet.service.annotations

import dev.kviklet.kviklet.db.TableAnnotationAdapter
import dev.kviklet.kviklet.security.Permission
import dev.kviklet.kviklet.security.Policy
import dev.kviklet.kviklet.service.dto.AnnotationSource
import dev.kviklet.kviklet.service.dto.TableAnnotation
import dev.kviklet.kviklet.service.dto.TableAnnotationId
import dev.kviklet.kviklet.service.dto.utcTimeNow
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Raised when an admin tries to edit an annotation owned by the remote sync source, which the next
 * sync would silently overwrite.
 */
class SyncedAnnotationNotEditableException(message: String) : RuntimeException(message)

@Service
class TableAnnotationService(private val tableAnnotationAdapter: TableAnnotationAdapter) {

    @Policy(Permission.CONFIGURATION_GET)
    @Transactional(readOnly = true)
    fun listAnnotations(): List<TableAnnotation> = tableAnnotationAdapter.findAll()
        .sortedWith(compareBy({ it.source }, { it.tablePattern }))

    @Policy(Permission.CONFIGURATION_EDIT)
    @Transactional
    fun createAnnotation(annotation: TableAnnotation): TableAnnotation = tableAnnotationAdapter.create(
        annotation.copy(source = AnnotationSource.MANUAL, externalId = null, updatedAt = utcTimeNow()),
    )

    @Policy(Permission.CONFIGURATION_EDIT)
    @Transactional
    fun updateAnnotation(id: TableAnnotationId, annotation: TableAnnotation): TableAnnotation {
        requireManual(id)
        return tableAnnotationAdapter.update(
            id,
            annotation.copy(source = AnnotationSource.MANUAL, externalId = null, updatedAt = utcTimeNow()),
        )
    }

    @Policy(Permission.CONFIGURATION_EDIT)
    @Transactional
    fun deleteAnnotation(id: TableAnnotationId) {
        requireManual(id)
        tableAnnotationAdapter.delete(id)
    }

    private fun requireManual(id: TableAnnotationId) {
        val existing = tableAnnotationAdapter.findById(id)
        if (existing.source == AnnotationSource.SYNCED) {
            throw SyncedAnnotationNotEditableException(
                "This annotation is managed by the configured sync source and cannot be edited here. " +
                    "Change it at the source, or remove the sync URL to manage annotations in Kviklet.",
            )
        }
    }
}
