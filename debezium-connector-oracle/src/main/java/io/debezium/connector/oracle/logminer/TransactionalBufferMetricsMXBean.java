/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.connector.oracle.logminer;

import java.util.Set;

/**
 * This interface exposes TransactionalBuffer metrics
 */
public interface TransactionalBufferMetricsMXBean {

    /**
     * Exposes number of rolled back transactions
     *
     * @return number of rolled back transaction in the in-memory buffer
     */
    long getNumberOfRolledBackTransactions();

    /**
     * Exposes number of committed transactions
     *
     * @return number of committed transaction in the in-memory buffer
     */
    long getNumberOfCommittedTransactions();

    /**
     * Exposes average number of committed transactions per second
     *
     * @return average number of committed transactions per second in the in-memory buffer
     */
    long getCommitThroughput();

    /**
     * Exposes average number of captured and parsed DML per second
     *
     * @return average number of captured and parsed DML per second in the in-memory buffer
     */
    long getCapturedDmlThroughput();

    /**
     * Exposes number of transaction, buffered in memory
     *
     * @return number of currently buffered transactions
     */
    int getNumberOfActiveTransactions();

    /**
     * Exposes the oldest(smallest) SCN in the Transactional Buffer
     *
     * @return oldest SCN
     */
    Long getOldestScn();

    /**
     * It shows last committed SCN
     * @return committed SCN
     */
    Long getCommittedScn();

    /**
     * This is to get the lag between latest captured change timestamp in REDO LOG and time of it's placement in the buffer
     * @return lag in milliseconds
     */
    long getLagFromSource();

    /**
     * This is to get max value of the time difference between logging of source DB records into redo log and capturing it by Log Miner
     * @return value in milliseconds
     */
    long getMaxLagFromSource();

    /**
     * This is to get min value of the time difference between logging of source DB records into redo log and capturing it by Log Miner
     * @return value in milliseconds
     */
    long getMinLagFromSource();

    /**
     * This is to get average value of the time difference between logging of source DB records into redo log and capturing it by Log Miner.
     * Average is calculated as summary of all lags / number of captured DB changes
     * @return value in milliseconds
     */
    long getAverageLagFromSource();

    /**
     * This is to get list of removed transactions from the Transactional Buffer
     * @return count
     */
    Set<String> getAbandonedTransactionIds();

    /**
     * See which transactions were rolled back
     * @return set of transaction IDs
     */
    Set<String> getRolledBackTransactionIds();

    /**
     * action to reset some metrics
     */
    void reset();

    // todo delete after stowplan test
    Long getUfvDelete();
    void incrementUfvDelete();
    Long getUfvInsert();
    void incrementUfvInsert();
}
