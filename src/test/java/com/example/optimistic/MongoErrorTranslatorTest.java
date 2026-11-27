package com.example.optimistic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.example.optimistic.MongoErrorTranslator.Kind;
import com.example.optimistic.exception.DataAccessException;
import com.example.optimistic.exception.DuplicateKeyException;
import com.example.optimistic.exception.NotAuthorisedMongoException;
import com.example.optimistic.exception.NotSentException;
import com.example.optimistic.exception.NotWrittenException;
import com.example.optimistic.exception.ResponseLostException;
import com.example.optimistic.exception.RetriedWriteException;
import com.example.optimistic.exception.SchemaValidationException;
import com.mongodb.MongoCommandException;
import com.mongodb.MongoConnectionPoolClearedException;
import com.mongodb.MongoException;
import com.mongodb.MongoInterruptedException;
import com.mongodb.MongoNodeIsRecoveringException;
import com.mongodb.MongoNotPrimaryException;
import com.mongodb.MongoSocketClosedException;
import com.mongodb.MongoSocketOpenException;
import com.mongodb.MongoSocketReadException;
import com.mongodb.MongoSocketReadTimeoutException;
import com.mongodb.MongoSocketWriteException;
import com.mongodb.MongoSocketWriteTimeoutException;
import com.mongodb.MongoTimeoutException;
import com.mongodb.MongoWriteException;
import com.mongodb.ServerAddress;
import com.mongodb.WriteError;
import com.mongodb.connection.ClusterId;
import com.mongodb.connection.ServerId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.bson.BsonString;
import org.junit.jupiter.api.Test;

/**
 * The translator alone, with driver exceptions that are built by hand. No database. Each exception type has its own
 * test line, so that a wrong mapping for one type fails a test.
 */
class MongoErrorTranslatorTest {

    private static final ServerAddress HOST = new ServerAddress("localhost", 27017);
    private static final Object ID = "doc-1";

    private static Throwable translated(Kind kind, RuntimeException driverFailure) {
        return catchThrowable(() -> MongoErrorTranslator.call(ID, kind, () -> {
            throw driverFailure;
        }));
    }

    private static BsonDocument commandError(int code) {
        return new BsonDocument("code", new BsonInt32(code)).append("errmsg", new BsonString("test"));
    }

    private static MongoWriteException writeError(int code) {
        return new MongoWriteException(new WriteError(code, "test", new BsonDocument()), HOST, List.of());
    }

    /** One driver exception of each type that the transient switch lists, with the class it must become. */
    private static Map<MongoException, Class<? extends DataAccessException>> updateMapping() {
        Map<MongoException, Class<? extends DataAccessException>> mapping = new LinkedHashMap<>();
        mapping.put(new MongoTimeoutException("no server"), NotSentException.class);
        mapping.put(new MongoSocketOpenException("cannot open", HOST), NotSentException.class);
        mapping.put(new MongoConnectionPoolClearedException(new ServerId(new ClusterId(), HOST), null),
                NotSentException.class);
        mapping.put(new MongoSocketReadException("read", HOST), ResponseLostException.class);
        mapping.put(new MongoSocketReadTimeoutException("read timeout", HOST, null), ResponseLostException.class);
        mapping.put(new MongoSocketClosedException("closed", HOST), ResponseLostException.class);
        mapping.put(new MongoSocketWriteException("write", HOST, null), ResponseLostException.class);
        mapping.put(new MongoSocketWriteTimeoutException("write timeout", HOST, null), ResponseLostException.class);
        mapping.put(new MongoInterruptedException("interrupted", null), ResponseLostException.class);
        mapping.put(new MongoNotPrimaryException(commandError(10107), HOST), RetriedWriteException.class);
        mapping.put(new MongoNodeIsRecoveringException(commandError(11600), HOST), RetriedWriteException.class);
        return mapping;
    }

    @Test
    void g23_eachTransientDriverTypeOnAnUpdateBecomesItsOwnException() {
        updateMapping().forEach((driverFailure, expected) -> assertThat(translated(Kind.UPDATE, driverFailure))
                .as(driverFailure.getClass().getSimpleName())
                .isExactlyInstanceOf(expected)
                .hasCause(driverFailure));
    }

    @Test
    void g23_eachTransientDriverTypeOnAReadIsNotWritten() {
        // A read never writes. A "not sent" failure stays NotSentException (a subtype of NotWrittenException). Each
        // other type becomes NotWrittenException itself.
        updateMapping().forEach((driverFailure, onUpdate) -> assertThat(translated(Kind.READ, driverFailure))
                .as(driverFailure.getClass().getSimpleName())
                .isExactlyInstanceOf(onUpdate == NotSentException.class ? NotSentException.class
                        : NotWrittenException.class)
                .hasCause(driverFailure));
    }

    @Test
    void g24_aFailedUpdateCarriesTheIdAndAFailedInsertDoesNotForValidationErrors() {
        for (int code : new int[] {121, 10334}) {
            assertThat(translated(Kind.UPDATE, writeError(code))).as("update, code %d", code)
                    .isInstanceOfSatisfying(SchemaValidationException.class, e -> assertThat(e.id()).isEqualTo(ID));
            assertThat(translated(Kind.INSERT, writeError(code))).as("insert, code %d", code)
                    .isInstanceOfSatisfying(SchemaValidationException.class, e -> assertThat(e.id()).isNull());
        }
    }

    @Test
    void g24_aFailedUpdateCarriesTheIdAndAFailedInsertDoesNotWhenUnauthorised() {
        MongoCommandException unauthorised = new MongoCommandException(commandError(13), HOST);

        assertThat(translated(Kind.UPDATE, unauthorised))
                .isInstanceOfSatisfying(NotAuthorisedMongoException.class, e -> assertThat(e.id()).isEqualTo(ID));
        assertThat(translated(Kind.INSERT, unauthorised))
                .isInstanceOfSatisfying(NotAuthorisedMongoException.class, e -> assertThat(e.id()).isNull());
    }

    @Test
    void g24_aDuplicateKeyCarriesTheIdAlsoOnInsert() {
        assertThat(translated(Kind.INSERT, writeError(11000)))
                .isInstanceOfSatisfying(DuplicateKeyException.class, e -> assertThat(e.id()).isEqualTo(ID));
    }
}
