/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.connector.oracle.logminer;

import io.debezium.config.Configuration;
import io.debezium.connector.base.ChangeEventQueue;
import io.debezium.connector.oracle.OracleConnector;
import io.debezium.connector.oracle.OracleConnectorConfig;
import io.debezium.connector.oracle.OracleOffsetContext;
import io.debezium.pipeline.DataChangeEvent;
import io.debezium.pipeline.ErrorHandler;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.mockito.Mockito.mock;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static io.debezium.config.CommonConnectorConfig.DEFAULT_MAX_BATCH_SIZE;
import static io.debezium.config.CommonConnectorConfig.DEFAULT_MAX_QUEUE_SIZE;
import static org.fest.assertions.Assertions.assertThat;

/**
 * @author Andrey Pustovetov
 */

public class TransactionalBufferTest {

    private static final String SERVER_NAME = "serverX";
    private static final String TRANSACTION_ID = "transaction";
    private static final String OTHER_TRANSACTION_ID = "other_transaction";
    private static final String SQL_ONE = "update table";
    private static final String SQL_TWO = "insert into table";
    private static final String MESSAGE = "OK";
    private static final BigDecimal SCN = BigDecimal.ONE;
    private static final BigDecimal OTHER_SCN = BigDecimal.TEN;
    private static final BigDecimal LARGEST_SCN = BigDecimal.valueOf(100L);
    private static final Timestamp TIMESTAMP = new Timestamp(System.currentTimeMillis());
    private static final Configuration config = new Configuration() {
        @Override
        public Set<String> keys() {
            return null;
        }

        @Override
        public String getString(String key) {
            return null;
        }
    };
    private static final OracleConnectorConfig connectorConfig = new OracleConnectorConfig(config);
    private static OracleOffsetContext offsetContext;

    private ErrorHandler errorHandler;
    private TransactionalBuffer transactionalBuffer;

    @Before
    public void before() {
        ChangeEventQueue<DataChangeEvent> queue = new ChangeEventQueue.Builder<DataChangeEvent>()
                .pollInterval(Duration.of(DEFAULT_MAX_QUEUE_SIZE, ChronoUnit.MILLIS))
                .maxBatchSize(DEFAULT_MAX_BATCH_SIZE)
                .maxQueueSize(DEFAULT_MAX_QUEUE_SIZE)
                .build();
        errorHandler = new ErrorHandler(OracleConnector.class, SERVER_NAME, queue, () -> { });
        TransactionalBufferMetrics metrics = mock(TransactionalBufferMetrics.class);
        transactionalBuffer = new TransactionalBuffer(SERVER_NAME, errorHandler, metrics);
    }

    @After
    public void after() throws InterruptedException {
        errorHandler.stop();
        transactionalBuffer.close();
    }

    @Test
    public void testIsEmpty() {
        assertThat(transactionalBuffer.isEmpty()).isEqualTo(true);
    }

    @Test
    public void testIsNotEmptyWhenTransactionIsRegistered() {
        transactionalBuffer.registerCommitCallback(TRANSACTION_ID, SCN, Instant.now(), "", (timestamp, smallestScn, commitScn, counter) -> { });
        assertThat(transactionalBuffer.isEmpty()).isEqualTo(false);
    }

    @Test
    public void testIsNotEmptyWhenTransactionIsCommitting() {
        transactionalBuffer.registerCommitCallback(TRANSACTION_ID, SCN, Instant.now(), "", (timestamp, smallestScn, commitScn, counter) -> Thread.sleep(1000));
        offsetContext = new OracleOffsetContext(connectorConfig, SCN.longValue(), SCN.longValue(), null, false, true);
        transactionalBuffer.commit(TRANSACTION_ID, SCN.add(BigDecimal.ONE), offsetContext, TIMESTAMP, () -> true, MESSAGE);
        assertThat(transactionalBuffer.isEmpty()).isEqualTo(false);
    }

    @Test
    public void testIsEmptyWhenTransactionIsCommitted() throws InterruptedException {
        CountDownLatch commitLatch = new CountDownLatch(1);
        transactionalBuffer.registerCommitCallback(TRANSACTION_ID, SCN, Instant.now(), "", (timestamp, smallestScn, commitScn, counter) -> commitLatch.countDown());
        offsetContext = new OracleOffsetContext(connectorConfig, SCN.longValue(), SCN.longValue(), null, false, true);
        transactionalBuffer.commit(TRANSACTION_ID, SCN.add(BigDecimal.ONE), offsetContext, TIMESTAMP, () -> true, MESSAGE);
        commitLatch.await();
        Thread.sleep(1000);
        assertThat(transactionalBuffer.isEmpty()).isEqualTo(true);
    }

    @Test
    public void testIsEmptyWhenTransactionIsRolledBack() {
        transactionalBuffer.registerCommitCallback(TRANSACTION_ID, SCN, Instant.now(), "", (timestamp, smallestScn, commitScn, counter) -> { });
        transactionalBuffer.rollback(TRANSACTION_ID, "");
        assertThat(transactionalBuffer.isEmpty()).isEqualTo(true);
        assertThat(transactionalBuffer.getLargestScn()).isEqualTo(SCN);
    }

    @Test
    public void testNonEmptyFirstTransactionIsRolledBack() {
        transactionalBuffer.registerCommitCallback(TRANSACTION_ID, SCN, Instant.now(), "insert", (timestamp, smallestScn, commitScn, counter) -> { });
        transactionalBuffer.registerCommitCallback(OTHER_TRANSACTION_ID, OTHER_SCN, Instant.now(), "", (timestamp, smallestScn, commitScn, counter) -> { });
        transactionalBuffer.rollback(TRANSACTION_ID, "");
        assertThat(transactionalBuffer.isEmpty()).isEqualTo(false);
        assertThat(transactionalBuffer.getLargestScn()).isEqualTo(OTHER_SCN);
        assertThat(transactionalBuffer.getRolledBackTransactionIds().contains(TRANSACTION_ID)).isTrue();
        assertThat(transactionalBuffer.getRolledBackTransactionIds().contains(OTHER_TRANSACTION_ID)).isFalse();
    }

    @Test
    public void testNonEmptySecondTransactionIsRolledBack() {
        transactionalBuffer.registerCommitCallback(TRANSACTION_ID, SCN, Instant.now(), "", (timestamp, smallestScn, commitScn, counter) -> { });
        transactionalBuffer.registerCommitCallback(OTHER_TRANSACTION_ID, OTHER_SCN, Instant.now(), "", (timestamp, smallestScn, commitScn, counter) -> { });
        transactionalBuffer.rollback(OTHER_TRANSACTION_ID, "");
        assertThat(transactionalBuffer.isEmpty()).isEqualTo(false);
        assertThat(transactionalBuffer.getLargestScn()).isEqualTo(OTHER_SCN);
        assertThat(transactionalBuffer.getRolledBackTransactionIds().contains(TRANSACTION_ID)).isFalse();
        assertThat(transactionalBuffer.getRolledBackTransactionIds().contains(OTHER_TRANSACTION_ID)).isTrue();
    }

    @Test
    public void testCalculateScnWhenTransactionIsCommitted() throws InterruptedException {
        CountDownLatch commitLatch = new CountDownLatch(1);
        AtomicReference<BigDecimal> smallestScnContainer = new AtomicReference<>();
        transactionalBuffer.registerCommitCallback(TRANSACTION_ID, SCN, Instant.now(), "", (timestamp, smallestScn, commitScn, counter) -> {
            smallestScnContainer.set(smallestScn);
            commitLatch.countDown();
        });
        assertThat(transactionalBuffer.getLargestScn()).isEqualTo(SCN); // before commit
        offsetContext = new OracleOffsetContext(connectorConfig, SCN.longValue(), SCN.longValue(), null, false, true);
        transactionalBuffer.commit(TRANSACTION_ID, SCN.add(BigDecimal.ONE), offsetContext, TIMESTAMP, () -> true, MESSAGE);
        commitLatch.await();
        assertThat(transactionalBuffer.getLargestScn()).isEqualTo(SCN); // after commit

        assertThat(smallestScnContainer.get()).isNull();

        assertThat(transactionalBuffer.getRolledBackTransactionIds().isEmpty()).isTrue();
    }

    @Test
    public void testCalculateScnWhenFirstTransactionIsCommitted() throws InterruptedException {
        CountDownLatch commitLatch = new CountDownLatch(1);
        AtomicReference<BigDecimal> smallestScnContainer = new AtomicReference<>();
        transactionalBuffer.registerCommitCallback(TRANSACTION_ID, SCN, Instant.now(), "", (timestamp, smallestScn, commitScn, counter) -> {
            smallestScnContainer.set(smallestScn);
            commitLatch.countDown();
        });
        transactionalBuffer.registerCommitCallback(OTHER_TRANSACTION_ID, OTHER_SCN, Instant.now(), "", (timestamp, smallestScn, commitScn, counter) -> { });
        assertThat(transactionalBuffer.getLargestScn()).isEqualTo(OTHER_SCN); // before commit
        offsetContext = new OracleOffsetContext(connectorConfig, SCN.longValue(), SCN.longValue(), null, false, true);
        transactionalBuffer.commit(TRANSACTION_ID, SCN.add(BigDecimal.ONE), offsetContext, TIMESTAMP, () -> true, MESSAGE);
        commitLatch.await();
        // after commit, it stays the same because OTHER_TRANSACTION_ID is not committed yet
        assertThat(transactionalBuffer.getLargestScn()).isEqualTo(OTHER_SCN);

        assertThat(smallestScnContainer.get()).isEqualTo(OTHER_SCN);
        assertThat(transactionalBuffer.getRolledBackTransactionIds().isEmpty()).isTrue();
    }

    @Test
    public void testCalculateScnWhenSecondTransactionIsCommitted() throws InterruptedException {
        transactionalBuffer.registerCommitCallback(TRANSACTION_ID, SCN, Instant.now(), "", (timestamp, smallestScn, commitScn, counter) -> { });
        CountDownLatch commitLatch = new CountDownLatch(1);
        AtomicReference<BigDecimal> smallestScnContainer = new AtomicReference<>();
        transactionalBuffer.registerCommitCallback(OTHER_TRANSACTION_ID, OTHER_SCN, Instant.now(), "", (timestamp, smallestScn, commitScn, counter) -> {
            smallestScnContainer.set(smallestScn);
            commitLatch.countDown();
        });
        assertThat(transactionalBuffer.getLargestScn()).isEqualTo(OTHER_SCN); // before commit
        offsetContext = new OracleOffsetContext(connectorConfig, OTHER_SCN.longValue(), OTHER_SCN.longValue(), null, false, true);
        transactionalBuffer.commit(OTHER_TRANSACTION_ID, OTHER_SCN.add(BigDecimal.ONE), offsetContext, TIMESTAMP, () -> true, MESSAGE);
        commitLatch.await();
        assertThat(smallestScnContainer.get()).isEqualTo(SCN);
        // after committing OTHER_TRANSACTION_ID
        assertThat(transactionalBuffer.getLargestScn()).isEqualTo(OTHER_SCN);
        assertThat(transactionalBuffer.getRolledBackTransactionIds().isEmpty()).isTrue();
    }

    @Test
    public void testResetLargestScn() {
        transactionalBuffer.registerCommitCallback(TRANSACTION_ID, SCN, Instant.now(), "", (timestamp, smallestScn, commitScn, counter) -> { });
        transactionalBuffer.registerCommitCallback(OTHER_TRANSACTION_ID, OTHER_SCN, Instant.now(), "", (timestamp, smallestScn, commitScn, counter) -> {});
        assertThat(transactionalBuffer.getLargestScn()).isEqualTo(OTHER_SCN); // before commit
        offsetContext = new OracleOffsetContext(connectorConfig, OTHER_SCN.longValue(), OTHER_SCN.longValue(), null, false, true);
        transactionalBuffer.commit(OTHER_TRANSACTION_ID, OTHER_SCN, offsetContext, TIMESTAMP, () -> true, MESSAGE);
        assertThat(transactionalBuffer.getLargestScn()).isEqualTo(OTHER_SCN); // after commit

        transactionalBuffer.resetLargestScn(null);
        assertThat(transactionalBuffer.getLargestScn()).isEqualTo(BigDecimal.ZERO);
        transactionalBuffer.resetLargestScn(OTHER_SCN.longValue());
        assertThat(transactionalBuffer.getLargestScn()).isEqualTo(OTHER_SCN);
    }

    @Test
    public void testAbandoningOneTransaction() {
        transactionalBuffer.registerCommitCallback(TRANSACTION_ID, SCN, Instant.now(), "", (timestamp, smallestScn, commitScn, counter) -> {});
        transactionalBuffer.abandonLongTransactions(SCN.longValue());
        assertThat(transactionalBuffer.isEmpty()).isEqualTo(true);
        assertThat(transactionalBuffer.getLargestScn()).isEqualTo(BigDecimal.ZERO);
    }

    @Test
    public void testAbandoningTransactionHavingAnotherOne() {
        transactionalBuffer.registerCommitCallback(TRANSACTION_ID, SCN, Instant.now(), "", (timestamp, smallestScn, commitScn, counter) -> {});
        transactionalBuffer.registerCommitCallback(OTHER_TRANSACTION_ID, OTHER_SCN, Instant.now(), "", (timestamp, smallestScn, commitScn, counter) -> {});
        transactionalBuffer.abandonLongTransactions(SCN.longValue());
        assertThat(transactionalBuffer.isEmpty()).isEqualTo(false);
        assertThat(transactionalBuffer.getLargestScn()).isEqualTo(OTHER_SCN);
    }

    @Test
    public void testTransactionDump() {
        transactionalBuffer.registerCommitCallback(TRANSACTION_ID, SCN, Instant.now(), SQL_ONE, (timestamp, smallestScn, commitScn, counter) -> {});
        transactionalBuffer.registerCommitCallback(OTHER_TRANSACTION_ID, OTHER_SCN, Instant.now(), SQL_ONE, (timestamp, smallestScn, commitScn, counter) -> {});
        transactionalBuffer.registerCommitCallback(OTHER_TRANSACTION_ID, OTHER_SCN, Instant.now(), SQL_TWO, (timestamp, smallestScn, commitScn, counter) -> {});
        assertThat(transactionalBuffer.toString()).contains(SQL_ONE);
        assertThat(transactionalBuffer.toString()).contains(SQL_TWO);
    }

    @Test
    public void testDuplicatedRedoSql() {

        assertThat(transactionalBuffer.getLargestScn().equals(BigDecimal.ZERO));

        final String insertIntoATable = "insert into a table";
        final String anotherInsertIntoATable = "another insert into a table";
        final String duplicatedInsertIntoATable = "duplicated insert into a table";

        transactionalBuffer.registerCommitCallback(TRANSACTION_ID, SCN, Instant.now(), insertIntoATable, (timestamp, smallestScn, commitScn, counter) -> { });
        transactionalBuffer.registerCommitCallback(TRANSACTION_ID, OTHER_SCN, Instant.now(), anotherInsertIntoATable, (timestamp, smallestScn, commitScn, counter) -> { });
        assertThat(transactionalBuffer.getLargestScn().equals(OTHER_SCN));
        assertThat(transactionalBuffer.toString().contains(insertIntoATable));
        assertThat(transactionalBuffer.toString().contains(anotherInsertIntoATable));
        transactionalBuffer.rollback(TRANSACTION_ID, "");

        // duplications are OK in different transactions
        transactionalBuffer.registerCommitCallback(TRANSACTION_ID, SCN, Instant.now(), duplicatedInsertIntoATable, (timestamp, smallestScn, commitScn, counter) -> { });
        transactionalBuffer.registerCommitCallback(OTHER_TRANSACTION_ID, SCN, Instant.now(), duplicatedInsertIntoATable, (timestamp, smallestScn, commitScn, counter) -> { });
        assertThat(transactionalBuffer.toString().indexOf(duplicatedInsertIntoATable) != transactionalBuffer.toString().lastIndexOf(duplicatedInsertIntoATable));
        transactionalBuffer.rollback(TRANSACTION_ID, "");
        transactionalBuffer.rollback(OTHER_TRANSACTION_ID, "");

        // duplications are NOT OK in a transactions for different SCNs if they are sequential
        transactionalBuffer.registerCommitCallback(TRANSACTION_ID, SCN, Instant.now(), duplicatedInsertIntoATable, (timestamp, smallestScn, commitScn, counter) -> {});
        transactionalBuffer.registerCommitCallback(TRANSACTION_ID, OTHER_SCN, Instant.now(), duplicatedInsertIntoATable, (timestamp, smallestScn, commitScn, counter) -> {});
        assertThat(transactionalBuffer.toString().indexOf(duplicatedInsertIntoATable) == transactionalBuffer.toString().lastIndexOf(duplicatedInsertIntoATable));
        transactionalBuffer.rollback(TRANSACTION_ID, "");

        // duplications are OK in a transactions for different SCNs if they are NOT sequential
        transactionalBuffer.registerCommitCallback(TRANSACTION_ID, SCN, Instant.now(), duplicatedInsertIntoATable, (timestamp, smallestScn, commitScn, counter) -> {});
        transactionalBuffer.registerCommitCallback(TRANSACTION_ID, OTHER_SCN, Instant.now(), insertIntoATable, (timestamp, smallestScn, commitScn, counter) -> {});
        transactionalBuffer.registerCommitCallback(TRANSACTION_ID, LARGEST_SCN, Instant.now(), duplicatedInsertIntoATable, (timestamp, smallestScn, commitScn, counter) -> {});
        assertThat(transactionalBuffer.toString().indexOf(duplicatedInsertIntoATable) != transactionalBuffer.toString().lastIndexOf(duplicatedInsertIntoATable));
        transactionalBuffer.rollback(TRANSACTION_ID, "");
    }

}
