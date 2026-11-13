package io.namastack.demo

import org.springframework.beans.factory.InitializingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.domain.Sort
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.index.Index

/**
 * Provisions the application's demo collections before order processing starts.
 *
 * Production applications apply their own migrations. This explicit example initializer is separate
 * from Once auto-configuration and uses the schema documented by the MongoDB module.
 *
 * @author Roland Beisel
 * @since 0.1.0
 */
@Configuration(proxyBeanMethods = false)
class MongoSchemaConfiguration {
    /**
     * Creates missing demo collections and the marker expiry index.
     *
     * @param mongoTemplate Access to the application's MongoDB database.
     * @return The application-owned initializer executed before dependent services are created.
     */
    @Bean("exampleMongoSchema")
    fun exampleMongoSchema(mongoTemplate: MongoTemplate): InitializingBean =
        InitializingBean {
            listOf("once_marker", "demo_order").forEach { collection ->
                if (!mongoTemplate.collectionExists(collection)) mongoTemplate.createCollection(collection)
            }
            mongoTemplate.indexOps("once_marker")
                .createIndex(Index().on("expiresAtMicros", Sort.Direction.ASC).on("_id", Sort.Direction.ASC))
        }
}
