/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.connector.oracle.logminer;

import io.debezium.annotation.NotThreadSafe;
import io.debezium.connector.oracle.OracleConnector;
import io.debezium.connector.oracle.OracleOffsetContext;
import io.debezium.pipeline.ErrorHandler;
import io.debezium.pipeline.source.spi.ChangeEventSource;
import io.debezium.util.Threads;
import org.apache.kafka.connect.errors.DataException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @author Andrey Pustovetov
 * <p>
 * Transactional buffer is designed to register callbacks, to execute them when transaction commits and to clear them
 * when transaction rollbacks.
 */
@NotThreadSafe
public final class TransactionalBuffer {

    private static final Logger LOGGER = LoggerFactory.getLogger(TransactionalBuffer.class);

    private final Map<String, Transaction> transactions;
    private final ExecutorService executor;
    private final AtomicInteger taskCounter;
    private final ErrorHandler errorHandler;
    private TransactionalBufferMetrics metrics;
    private final Set<String> abandonedTransactionIds;

    // storing rolledBackTransactionIds is for debugging purposes to check what was rolled back to research, todo delete in future releases
    private final Set<String> rolledBackTransactionIds;

    // It holds the latest captured SCN.
    // This number tracks starting point for the next mining cycle.
    private BigDecimal largestScn;
    private BigDecimal lastCommittedScn;

    /**
     * Constructor to create a new instance.
     *
     * @param logicalName  logical name
     * @param errorHandler logError handler
     * @param metrics      metrics MBean
     */
    TransactionalBuffer(String logicalName, ErrorHandler errorHandler, TransactionalBufferMetrics metrics) {
        this.transactions = new HashMap<>();
        this.executor = Threads.newSingleThreadExecutor(OracleConnector.class, logicalName, "transactional-buffer");
        this.taskCounter = new AtomicInteger();
        this.errorHandler = errorHandler;
        this.metrics = metrics;
        largestScn = BigDecimal.ZERO;
        lastCommittedScn = BigDecimal.ZERO;
        this.abandonedTransactionIds = new HashSet<>();
        this.rolledBackTransactionIds = new HashSet<>();
    }

    /**
     * @return largest last SCN in the buffer among all transactions
     */
    BigDecimal getLargestScn() {
        return largestScn;
    }

    /**
     * @return rolled back transactions
     */
    Set<String> getRolledBackTransactionIds() {
        return new HashSet<>(rolledBackTransactionIds);
    }

    /**
     * Reset Largest SCN
     */
    void resetLargestScn(Long value) {
        if (value != null) {
            largestScn = new BigDecimal(value);
        } else {
            largestScn = BigDecimal.ZERO;
        }
    }

    /**
     * Registers callback to execute when transaction commits.
     *
     * @param transactionId transaction identifier
     * @param scn           SCN
     * @param changeTime    time of DML parsing completion
     * @param redoSql       statement from redo
     * @param callback      callback to execute when transaction commits
     */
    void registerCommitCallback(String transactionId, BigDecimal scn, Instant changeTime, String redoSql, CommitCallback callback) {
        if (abandonedTransactionIds.contains(transactionId)) {
            LogMinerHelper.logWarn(metrics, "Another DML for an abandoned transaction {} : {}, ignored", transactionId, redoSql);
            return;
        }

        transactions.computeIfAbsent(transactionId, s -> new Transaction(scn));

        metrics.setActiveTransactions(transactions.size());
        metrics.incrementCapturedDmlCounter();
        metrics.calculateLagMetrics(changeTime);

        // The transaction object is not a lightweight object anymore having all REDO_SQL stored.
        Transaction transaction = transactions.get(transactionId);
        if (transaction != null) {

            // todo this should never happen, delete when tested and confirmed
            if (rolledBackTransactionIds.contains(transactionId)) {
                LogMinerHelper.logWarn(metrics, "Ignore DML for rolled back transaction: SCN={}, REDO_SQL={}", scn, redoSql);
                return;
            }

            transaction.commitCallbacks.add(callback);
            transaction.addRedoSql(scn, redoSql);
        }

        if (scn.compareTo(largestScn) > 0) {
            largestScn = scn;
        }
    }

    /**
     * @param transactionId transaction identifier
     * @param scn SCN of the commit.
     * @param offsetContext Oracle offset
     * @param timestamp     commit timestamp
     * @param context       context to check that source is running
     * @param debugMessage  message
     * @return true if committed transaction is in the buffer, was not processed yet and processed now
     */
    boolean commit(String transactionId, BigDecimal scn, OracleOffsetContext offsetContext, Timestamp timestamp,
                   ChangeEventSource.ChangeEventSourceContext context, String debugMessage) {

        Transaction transaction = transactions.get(transactionId);
        if (transaction == null) {
            return false;
        }

        calculateLargestScn();
        transaction = transactions.remove(transactionId);
        BigDecimal smallestScn = calculateSmallestScn();

        taskCounter.incrementAndGet();
        abandonedTransactionIds.remove(transactionId);

        // On the restarting connector, we start from SCN in the offset. There is possibility to commit a transaction(s) which were already committed.
        // Currently we cannot use ">=", because we may lose normal commit which may happen at the same time. TODO use audit table to prevent duplications
        if ((offsetContext.getCommitScn() != null && offsetContext.getCommitScn() > scn.longValue()) || lastCommittedScn.longValue() > scn.longValue()) {
            LogMinerHelper.logWarn(metrics, "Transaction {} was already processed, ignore. Committed SCN in offset is {}, commit SCN of the transaction is {}, last committed SCN is {}",
                    transactionId, offsetContext.getCommitScn(), scn, lastCommittedScn);
            metrics.setActiveTransactions(transactions.size());
            return false;
        }

        List<CommitCallback> commitCallbacks = transaction.commitCallbacks;
        LOGGER.trace("COMMIT, {}, smallest SCN: {}, largest SCN {}", debugMessage, smallestScn, largestScn);
        executor.execute(() -> {
            try {
                int counter = commitCallbacks.size();
                for (CommitCallback callback : commitCallbacks) {
                    if (!context.isRunning()) {
                        return;
                    }
                    callback.execute(timestamp, smallestScn, scn, --counter);
                }

                lastCommittedScn = new BigDecimal(scn.longValue());
                metrics.incrementCommittedTransactions();
                metrics.setActiveTransactions(transactions.size());
                metrics.incrementCommittedDmlCounter(commitCallbacks.size());
                metrics.setCommittedScn(scn.longValue());
            } catch (InterruptedException e) {
                LogMinerHelper.logError(metrics, "Thread interrupted during running", e);
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                errorHandler.setProducerThrowable(e);
            } finally {
                taskCounter.decrementAndGet();
            }
        });

        return true;
    }

    /**
     * Clears registered callbacks for given transaction identifier.
     *
     * @param transactionId transaction id
     * @param debugMessage  message
     * @return true if the rollback is for a transaction in the buffer
     */
    boolean rollback(String transactionId, String debugMessage) {

        Transaction transaction = transactions.get(transactionId);
        if (transaction != null) {
            LOGGER.debug("Transaction rolled back, {} , Statements: {}", debugMessage, transaction.redoSqlMap.values().toArray());

            calculateLargestScn(); // in case if largest SCN was in this transaction
            transactions.remove(transactionId);

            abandonedTransactionIds.remove(transactionId);
            rolledBackTransactionIds.add(transactionId);

            metrics.setActiveTransactions(transactions.size());
            metrics.incrementRolledBackTransactions();
            metrics.addRolledBackTransactionId(transactionId); // todo decide if we need both metrics

            return true;
        }

        return false;
    }

    /**
     * If for some reason the connector got restarted, the offset will point to the beginning of the oldest captured transaction.
     * Taking in consideration offset flush interval, the offset could be even older.
     * If that transaction was lasted for a long time, let say > 30 minutes, the offset will be not accessible after restart,
     * because we don't mine archived logs, neither rely on continuous_mine configuration option.
     * Hence we have to address these cases manually.
     * <p>
     * It is limited by  following condition:
     * allOnlineRedoLogFiles.size() - currentlyMinedLogFiles.size() <= 1
     * <p>
     * If each redo lasts for 10 minutes and 7 redo group have been configured, any transaction cannot lasts longer than 1 hour.
     * <p>
     * In case of an abandonment, all DMLs/Commits/Rollbacs for this transaction will be ignored
     * <p>
     * In other words connector will not send any part of this transaction to Kafka
     *
     * @param thresholdScn the smallest SVN of any transaction to keep in the buffer. All others will be removed.
     */
    void abandonLongTransactions(Long thresholdScn) {
        BigDecimal threshold = new BigDecimal(thresholdScn);
        Iterator<Map.Entry<String, Transaction>> iter = transactions.entrySet().iterator();
        while (iter.hasNext()) {
            Map.Entry<String, Transaction> transaction = iter.next();
            if (transaction.getValue().firstScn.compareTo(threshold) <= 0) {
                LogMinerHelper.logWarn(metrics, "Following long running transaction {} will be abandoned and ignored: {} ", transaction.getKey(), transaction.getValue().toString());
                abandonedTransactionIds.add(transaction.getKey());
                iter.remove();
                calculateLargestScn();

                metrics.addAbandonedTransactionId(transaction.getKey());
                metrics.setActiveTransactions(transactions.size());
            }
        }
    }

    private BigDecimal calculateSmallestScn() {
        BigDecimal scn = transactions.isEmpty() ? null : transactions.values()
                .stream()
                .map(transaction -> transaction.firstScn)
                .min(BigDecimal::compareTo)
                .orElseThrow(() -> new DataException("Cannot calculate smallest SCN"));
        metrics.setOldestScn(scn == null ? -1 : scn.longValue());
        return scn;
    }

    private void calculateLargestScn() {
        largestScn = transactions.isEmpty() ? BigDecimal.ZERO : transactions.values()
                .stream()
                .map(transaction -> transaction.lastScn)
                .max(BigDecimal::compareTo)
                .orElseThrow(() -> new DataException("Cannot calculate largest SCN"));
    }

    /**
     * Returns {@code true} if buffer is empty, otherwise {@code false}.
     *
     * @return {@code true} if buffer is empty, otherwise {@code false}
     */
    boolean isEmpty() {
        return transactions.isEmpty() && taskCounter.get() == 0;
    }

    @Override
    public String toString() {
        StringBuilder result = new StringBuilder();
        this.transactions.values().forEach(t -> result.append(t.toString()));
        return result.toString();
    }

    /**
     * Closes buffer.
     */
    void close() {
        transactions.clear();
        executor.shutdown();
        try {
            if (!executor.awaitTermination(1000L, TimeUnit.MILLISECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            LogMinerHelper.logError(metrics, "Thread interrupted during shutdown", e);
        }
    }

    /**
     * Callback is designed to execute when transaction commits.
     */
    public interface CommitCallback {

        /**
         * Executes callback.
         *
         * @param timestamp   commit timestamp
         * @param smallestScn smallest SCN among other transactions
         * @param commitScn commit SCN
         * @param callbackNumber number of the callback in the transaction
         */
        void execute(Timestamp timestamp, BigDecimal smallestScn, BigDecimal commitScn, int callbackNumber) throws InterruptedException;
    }

    @NotThreadSafe
    private static final class Transaction {

        private final BigDecimal firstScn;
        private BigDecimal lastScn;
        private final List<CommitCallback> commitCallbacks;
        private final Map<BigDecimal, List<String>> redoSqlMap;

        private Transaction(BigDecimal firstScn) {
            this.firstScn = firstScn;
            this.commitCallbacks = new ArrayList<>();
            this.redoSqlMap = new HashMap<>();
            this.lastScn = firstScn;
        }

        private void addRedoSql(BigDecimal scn, String redoSql) {
            this.lastScn = scn;

            List<String> sqlList = redoSqlMap.get(scn);
            if (sqlList == null) {
                redoSqlMap.put(scn, new ArrayList<>(Collections.singletonList(redoSql)));
            } else {
                sqlList.add(redoSql);
            }
        }

        @Override
        public String toString() {
            return "Transaction{" +
                    "firstScn=" + firstScn +
                    ", lastScn=" + lastScn +
                    ", redoSqls=" + Arrays.toString(redoSqlMap.values().toArray()) +
                    '}';
        }
    }
}
