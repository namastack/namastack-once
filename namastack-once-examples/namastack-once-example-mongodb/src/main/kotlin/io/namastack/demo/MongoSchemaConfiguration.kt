package io.namastack.demo

import org.springframework.beans.factory.InitializingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.domain.Sort
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.index.Index

@Configuration(proxyBeanMethods = false)
class MongoSchemaConfiguration {
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
