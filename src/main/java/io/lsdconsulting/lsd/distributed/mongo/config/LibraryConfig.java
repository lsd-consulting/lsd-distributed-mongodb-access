package io.lsdconsulting.lsd.distributed.mongo.config;

import io.lsdconsulting.lsd.distributed.access.model.InterceptedInteractionFactory;
import io.lsdconsulting.lsd.distributed.access.repository.InterceptedDocumentRepository;
import io.lsdconsulting.lsd.distributed.mongo.repository.InterceptedDocumentMongoRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "lsd.dist.db.connectionString")
public class LibraryConfig {

    @Bean
    public InterceptedDocumentRepository interceptedDocumentRepository(@Value("${lsd.dist.db.connectionString}") String dbConnectionString,
                                                                       @Value("${lsd.dist.db.trustStoreLocation:#{null}}") String trustStoreLocation,
                                                                       @Value("${lsd.dist.db.trustStorePassword:#{null}}") String trustStorePassword) {
        return new InterceptedDocumentMongoRepository(dbConnectionString, trustStoreLocation, trustStorePassword);
    }

    @Bean
    public InterceptedInteractionFactory mapGenerator(@Value("${spring.profiles.active:#{''}}") final String profile) {
        return new InterceptedInteractionFactory(profile);
    }
}
