package dev.kviklet.kviklet.controller

import dev.kviklet.kviklet.service.TableWarning
import dev.kviklet.kviklet.service.annotations.AnnotationSyncService
import dev.kviklet.kviklet.service.annotations.AnnotationSyncStatus
import dev.kviklet.kviklet.service.annotations.TableAnnotationService
import dev.kviklet.kviklet.service.dto.AnnotationSource
import dev.kviklet.kviklet.service.dto.TableAnnotation
import dev.kviklet.kviklet.service.dto.TableAnnotationId
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.constraints.NotBlank
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDateTime

/** One annotation matched against one table a statement is about to write to. */
data class TableWarningResponse(
    val table: String,
    val message: String,
    val runbookUrl: String?,
    val requiresAcknowledgement: Boolean,
) {
    companion object {
        fun from(warning: TableWarning) = TableWarningResponse(
            table = warning.table,
            message = warning.message,
            runbookUrl = warning.runbookUrl,
            requiresAcknowledgement = warning.requiresAcknowledgement,
        )
    }
}

/** Body of the 409 returned when a statement writes to a table that still needs acknowledging. */
data class UnacknowledgedTableResponse(val message: String, val warnings: List<TableWarningResponse>)

data class TableAnnotationRequest(
    @field:NotBlank
    val tablePattern: String,
    val connectionSelector: String = "*",
    @field:NotBlank
    val message: String,
    val runbookUrl: String? = null,
    val requiresAcknowledgement: Boolean = true,
) {
    fun toDto() = TableAnnotation(
        tablePattern = tablePattern,
        connectionSelector = connectionSelector.ifBlank { "*" },
        message = message,
        runbookUrl = runbookUrl?.takeIf { it.isNotBlank() },
        requiresAcknowledgement = requiresAcknowledgement,
    )
}

data class TableAnnotationResponse(
    val id: String,
    val tablePattern: String,
    val connectionSelector: String,
    val message: String,
    val runbookUrl: String?,
    val requiresAcknowledgement: Boolean,
    val source: AnnotationSource,
    val updatedAt: LocalDateTime,
) {
    companion object {
        fun from(annotation: TableAnnotation) = TableAnnotationResponse(
            id = annotation.id.toString(),
            tablePattern = annotation.tablePattern,
            connectionSelector = annotation.connectionSelector,
            message = annotation.message,
            runbookUrl = annotation.runbookUrl,
            requiresAcknowledgement = annotation.requiresAcknowledgement,
            source = annotation.source,
            updatedAt = annotation.updatedAt,
        )
    }
}

data class AnnotationSyncStatusResponse(
    val configured: Boolean,
    val lastSyncAt: LocalDateTime?,
    val lastSyncError: String?,
    val syncedCount: Int,
    val neverSynced: Boolean,
) {
    companion object {
        fun from(status: AnnotationSyncStatus) = AnnotationSyncStatusResponse(
            configured = status.configured,
            lastSyncAt = status.lastSyncAt,
            lastSyncError = status.lastSyncError,
            syncedCount = status.syncedCount,
            neverSynced = status.neverSynced,
        )
    }
}

data class TableAnnotationListResponse(
    val annotations: List<TableAnnotationResponse>,
    val syncStatus: AnnotationSyncStatusResponse,
)

@RestController
@Validated
@RequestMapping("/table-annotations")
@Tag(
    name = "Table Annotations",
    description = "Mark tables so writing to them raises a warning, e.g. because they are mirrored " +
        "into a cache that a direct write would leave stale.",
)
class TableAnnotationController(
    private val tableAnnotationService: TableAnnotationService,
    private val annotationSyncService: AnnotationSyncService,
) {

    @Operation(summary = "List table annotations")
    @GetMapping("/")
    fun list(): TableAnnotationListResponse = TableAnnotationListResponse(
        annotations = tableAnnotationService.listAnnotations().map { TableAnnotationResponse.from(it) },
        syncStatus = AnnotationSyncStatusResponse.from(annotationSyncService.status()),
    )

    @Operation(summary = "Create a table annotation")
    @PostMapping("/")
    fun create(@RequestBody request: TableAnnotationRequest): TableAnnotationResponse =
        TableAnnotationResponse.from(tableAnnotationService.createAnnotation(request.toDto()))

    @Operation(
        summary = "Update a table annotation",
        description = "Only annotations managed in Kviklet can be edited; synced ones are owned by the sync source.",
    )
    @PutMapping("/{id}")
    fun update(@PathVariable id: String, @RequestBody request: TableAnnotationRequest): TableAnnotationResponse =
        TableAnnotationResponse.from(
            tableAnnotationService.updateAnnotation(TableAnnotationId(id), request.toDto()),
        )

    @Operation(summary = "Delete a table annotation")
    @DeleteMapping("/{id}")
    fun delete(@PathVariable id: String) {
        tableAnnotationService.deleteAnnotation(TableAnnotationId(id))
    }

    @Operation(
        summary = "Sync table annotations now",
        description = "Pulls the annotation list from the configured sync URL immediately instead of " +
            "waiting for the next scheduled sync.",
    )
    @PostMapping("/sync")
    fun sync(): AnnotationSyncStatusResponse = AnnotationSyncStatusResponse.from(annotationSyncService.syncNow())
}
