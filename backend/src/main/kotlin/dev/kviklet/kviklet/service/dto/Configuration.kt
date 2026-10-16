package dev.kviklet.kviklet.service.dto

import dev.kviklet.kviklet.security.Resource
import dev.kviklet.kviklet.security.SecuredDomainObject

data class Configuration(
    val teamsUrl: String?,
    val slackUrl: String?,
    val proxyEnabled: Boolean? = null,
    /** Where [dev.kviklet.kviklet.service.dto.AnnotationSource.SYNCED] table annotations are pulled from. */
    val tableAnnotationSyncUrl: String? = null,
    /** Optional `Authorization` header value sent with the sync request. */
    val tableAnnotationSyncAuthHeader: String? = null,
) : SecuredDomainObject {
    override fun getSecuredObjectId(): String? = "configuration"

    override fun getDomainObjectType(): Resource = Resource.CONFIGURATION

    override fun getRelated(resource: Resource): SecuredDomainObject? {
        if (resource == Resource.CONFIGURATION) {
            return this
        } else {
            throw IllegalArgumentException("Cannot get related object for resource $resource")
        }
    }
}
