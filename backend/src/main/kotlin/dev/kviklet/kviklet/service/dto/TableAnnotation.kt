package dev.kviklet.kviklet.service.dto

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonValue
import dev.kviklet.kviklet.security.Resource
import dev.kviklet.kviklet.security.SecuredDomainId
import dev.kviklet.kviklet.security.SecuredDomainObject
import java.io.Serializable
import java.time.LocalDateTime

data class TableAnnotationId
@JsonCreator constructor(private val id: String) :
    SecuredDomainId,
    Serializable {
    @JsonValue
    override fun toString(): String = id
}

/**
 * Where an annotation came from, which decides who may change it.
 *
 * [MANUAL] rows are owned by a Kviklet admin and are only ever touched through the API.
 * [SYNCED] rows are owned by the remote source configured in [Configuration.tableAnnotationSyncUrl]
 * and are replaced wholesale on every successful sync, so editing one by hand would be undone.
 */
enum class AnnotationSource {
    MANUAL,
    SYNCED,
}

/**
 * A note attached to one or more tables, surfaced when a statement writes to them.
 *
 * The motivating case is a table mirrored into a secondary cache, where a direct write leaves the
 * cache stale, but nothing here is cache-specific: the annotation is just a message plus an optional
 * runbook link, shown to the author and the reviewer and (when [requiresAcknowledgement]) confirmed
 * before the statement runs.
 */
data class TableAnnotation(
    val id: TableAnnotationId? = null,
    /** Ant-style pattern matched against the table name, e.g. `subscriber` or `billing_*`. */
    val tablePattern: String,
    /** Ant-style pattern matched against the connection id, following the role policy convention. */
    val connectionSelector: String = "*",
    val message: String,
    val runbookUrl: String? = null,
    val requiresAcknowledgement: Boolean = true,
    val source: AnnotationSource = AnnotationSource.MANUAL,
    /** Stable key from the remote source, used to upsert [AnnotationSource.SYNCED] rows. */
    val externalId: String? = null,
    val updatedAt: LocalDateTime = utcTimeNow(),
) : SecuredDomainObject {
    override fun getSecuredObjectId(): String = id.toString()

    override fun getDomainObjectType(): Resource = Resource.CONFIGURATION

    override fun getRelated(resource: Resource): SecuredDomainObject? = when (resource) {
        Resource.CONFIGURATION -> this
        else -> null
    }
}
