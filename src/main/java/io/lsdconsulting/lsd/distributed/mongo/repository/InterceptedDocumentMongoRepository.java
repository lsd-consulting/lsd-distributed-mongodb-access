package io.lsdconsulting.lsd.distributed.mongo.repository;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.MongoException;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.model.CreateCollectionOptions;
import com.mongodb.connection.SslSettings;
import io.lsdconsulting.lsd.distributed.access.model.InterceptedInteraction;
import io.lsdconsulting.lsd.distributed.access.repository.InterceptedDocumentRepository;
import io.lsdconsulting.lsd.distributed.mongo.repository.codec.TypeCodec;
import io.lsdconsulting.lsd.distributed.mongo.repository.codec.ZonedDateTimeCodec;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.ssl.SSLContextBuilder;
import org.bson.codecs.configuration.CodecRegistry;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.List;

import static com.mongodb.MongoClientSettings.getDefaultCodecRegistry;
import static com.mongodb.client.model.Filters.in;
import static com.mongodb.client.model.Indexes.ascending;
import static java.lang.System.currentTimeMillis;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.apache.commons.lang3.StringUtils.isBlank;
import static org.bson.codecs.configuration.CodecRegistries.*;
import static org.bson.codecs.pojo.PojoCodecProvider.builder;

@Slf4j
public class InterceptedDocumentMongoRepository implements InterceptedDocumentRepository {

    public static final int DEFAULT_TIMEOUT_MILLIS = 500;
    public static final long DEFAULT_COLLECTION_SIZE_LIMIT_MBS = 1000 * 10L; // 10Gb
    private static final String DATABASE_NAME = "lsd";
    private static final String COLLECTION_NAME = "interceptedInteraction";

    public static final CodecRegistry pojoCodecRegistry = fromRegistries(
            getDefaultCodecRegistry(),
            fromCodecs(new ZonedDateTimeCodec(), new TypeCodec()),
            fromProviders(builder().automatic(true).build())
    );

    private final MongoCollection<InterceptedInteraction> interceptedInteractions;

    public InterceptedDocumentMongoRepository(final String dbConnectionString, final Integer connectionTimeout,
                                              final Long collectionSizeLimit) {
        this(dbConnectionString, null, null, connectionTimeout, collectionSizeLimit);
    }

    public InterceptedDocumentMongoRepository(final String dbConnectionString, final String trustStoreLocation,
                                              final String trustStorePassword, final Integer connectionTimeout,
                                              final Long collectionSizeLimit) {

        MongoCollection<InterceptedInteraction> temp;
        try {
            final MongoClient mongoClient = prepareMongoClient(dbConnectionString, trustStoreLocation, trustStorePassword, connectionTimeout);
            temp = prepareInterceptedInteractionCollection(mongoClient, collectionSizeLimit);
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            temp = null;
        }
        interceptedInteractions = temp;
    }

    private MongoClient prepareMongoClient(final String dbConnectionString, final String trustStoreLocation, final String trustStorePassword, int connectionTimeout) {
        final MongoClientSettings.Builder builder = MongoClientSettings.builder()
                .applyToSocketSettings(b -> {
                    b.connectTimeout(connectionTimeout, MILLISECONDS);
                    b.readTimeout(connectionTimeout, MILLISECONDS);
                })
                .applyToClusterSettings( b -> b.serverSelectionTimeout(connectionTimeout, MILLISECONDS))
                .applyConnectionString(new ConnectionString(dbConnectionString));

        if (!isBlank(trustStoreLocation) && !isBlank(trustStorePassword)) {
            builder.applyToSslSettings(sslSettingsBuilder -> loadCustomTrustStore(sslSettingsBuilder, trustStoreLocation, trustStorePassword));
        }

//    TODO We should also support other AuthenticationMechanisms
//    String user = "xxxx"; // the user name
//    String database = "admin"; // the name of the database in which the user is defined
//    char[] password = "xxxx".toCharArray(); // the password as a character array
//    MongoCredential credential = MongoCredential.createCredential(user, database, password);

        return MongoClients.create(builder
//            .credential(credential)
                .retryWrites(true)
                .build());
    }

    @SneakyThrows
    private static void loadCustomTrustStore(final SslSettings.Builder builder, final String trustStoreLocation,
                                             final String trustStorePassword) {
        try (final InputStream inputStream = new ClassPathResource(trustStoreLocation).getInputStream()) {
            final KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
            trustStore.load(inputStream, trustStorePassword.toCharArray());
            builder.context(new SSLContextBuilder()
                    .loadTrustMaterial(
                            trustStore, null
                    ).build()
            );
        }
    }

    private MongoCollection<InterceptedInteraction> prepareInterceptedInteractionCollection(final MongoClient mongoClient,
                                                                                            final Long collectionSizeLimit) {
        final MongoCollection<InterceptedInteraction> interceptedInteractions;

        if (!collectionExists(mongoClient)) {
            CreateCollectionOptions options = new CreateCollectionOptions();
            options.capped(true).sizeInBytes(1024 * 1000 * collectionSizeLimit);
            mongoClient.getDatabase(DATABASE_NAME).createCollection(COLLECTION_NAME, options);
        }

        interceptedInteractions = mongoClient.getDatabase(DATABASE_NAME).getCollection(COLLECTION_NAME, InterceptedInteraction.class).withCodecRegistry(pojoCodecRegistry);
        interceptedInteractions.createIndex(ascending("traceId"));
        interceptedInteractions.createIndex(ascending("createdAt"));
        return interceptedInteractions;
    }

    private boolean collectionExists(MongoClient mongoClient) {
        return mongoClient.getDatabase(DATABASE_NAME).listCollectionNames()
                .into(new ArrayList<>()).contains(COLLECTION_NAME);
    }

    @Override
    public void save(final InterceptedInteraction interceptedInteraction) {
        if (repositoryActive()) {
            try {
                long startTime = currentTimeMillis();
                interceptedInteractions.insertOne(interceptedInteraction);
                log.trace("save took {} ms", currentTimeMillis() - startTime);
            } catch (final MongoException e) {
                log.error("Skipping persisting the interceptedInteraction due to exception - interceptedInteraction:{}, message:{}, stackTrace:{}", interceptedInteraction, e.getMessage(), e.getStackTrace());
            }
        }
    }

    @Override
    public List<InterceptedInteraction> findByTraceIds(final String... traceId) {
        final List<InterceptedInteraction> result = new ArrayList<>();
        if (repositoryActive()) {
            long startTime = currentTimeMillis();
            try (final MongoCursor<InterceptedInteraction> cursor = interceptedInteractions
                    .find(in("traceId", traceId), InterceptedInteraction.class)
                    .sort(ascending("createdAt"))
                    .iterator()) {
                while (cursor.hasNext()) {
                    result.add(cursor.next());
                }
                log.trace("findByTraceIds took {} ms", currentTimeMillis() - startTime);
            } catch (final MongoException e) {
                log.error("Failed to retrieve interceptedInteractions - message:{}, stackTrace:{}", e.getMessage(), e.getStackTrace());
            }
        }
        return result;
    }

    private boolean repositoryActive() {
        if (interceptedInteractions == null) {
            log.warn("The LSD MongoDb repository is disabled!");
            return false;
        }
        return true;
    }
}
