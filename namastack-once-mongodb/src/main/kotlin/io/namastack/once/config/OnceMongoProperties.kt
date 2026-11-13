package io.namastack.once.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Marker collection settings for the auto-configured MongoDB Once instance.
 *
 * The application owns collection provisioning and indexes. Names are used as configured.
 *
 * @property collectionName Marker collection name; defaults to once_marker.
 *
 * @author Roland Beisel
 * @since 0.1.0
 */
@ConfigurationProperties("namastack.once.mongodb", ignoreUnknownFields = false)
data class OnceMongoProperties(
    val collectionName: String = "once_marker",
)
