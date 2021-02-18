/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.connector.oracle.logminer;

/**
 * This interface is exposed for JMX
 */
public interface LogMinerMetricsMXBean {

    /**
     * Exposes current SCN in the database. This is very efficient query and will not affect overall performance
     *
     * @return current SCN
     */
    Long getCurrentScn();

    /**
     * Exposes current redo log file. This is very efficient query and will not affect overall performance
     *
     * @return full path or NULL if an exception occurs.
     */
    String[] getCurrentRedoLogFileName();

    /**
     * Exposes states of redo logs: current, active, inactive, unused ...
     * @return array of: (redo log name | status) elements
     */
    String[] getRedoLogStatus();

    /**
     * fetches counter of redo switches for the last day.
     * If this number is high , like once in 3 minutes,  the troubleshooting on the database level is required.
     * @return counter
     */
    int getSwitchCounter();

    /**
     * @return number of captured DML since the connector is up
     */
    long getTotalCapturedDmlCount();

    /**
     * @return average duration of LogMiner view query
     */
    Long getMaxDurationOfFetchQueryInMilliseconds();

    /**
     * LogMiner view query returns number of captured DML , Commit and Rollback. This is what we call a batch.
     * @return duration of the last batch fetching
     */
    Long getLastDurationOfFetchQueryInMilliseconds();

    /**
     * LogMiner view query returns number of captured DML , Commit and Rollback. This is what we call a batch.
     * @return number of all processed batches
     */
    long getFetchingQueryCount();

    /**
     * @return max number of DML captured during connector start time
     */
    Long getMaxCapturedDmlInBatch();

    /**
     * @return time of processing the last captured batch
     */
    long getLastBatchProcessingTimeInMilliseconds();

    /**
     * @return number of captured DL during last mining session
     */
    int getLastCapturedDmlCount();

    /**
     * Maximum number of entries in LogMiner view to fetch. This is used to set the diapason of the SCN in mining query.
     * If difference between "start SCN" and "end SCN" to mine exceeds this limit, end SCN will be set to "start SCN" + batchSize
     * @return the limit
     */
    int getBatchSize();

    /**
     * this gives ability to manipulate number of entries in LogMiner view to fetch.
     * It has limits to prevent abnormal values
     * @param size limit
     */
    void setBatchSize(int size);

    /**
     * @return number of milliseconds for connector to sleep before fetching another batch from the LogMiner view
     */
    long getMillisecondToSleepBetweenMiningQuery();

    /**
     * sets number of milliseconds for connector to sleep before fetching another batch from the LogMiner view
     * @param milliseconds to sleep
     */
    void setMillisecondToSleepBetweenMiningQuery(long milliseconds);

    /**
     * change sleeping time
     * @param increment true to add, false to deduct
     */
    void changeSleepingTime(boolean increment);

    void changeBatchSize(boolean increment);

    /**
     * this flag turns on recording of captured incremental changes. It creates an overhead on CPU and takes disk space
     * @param doRecording true - record
     */
    void setRecordMiningHistory(boolean doRecording);

    /**
     * this flag indicates whether log mining is being recorded by {@link HistoryRecorder}
     */
    boolean getRecordMiningHistory();

    /**
     * This represents the maximum number of entries processed per second from LogMiner sessions.
     * Entries include things such as DMLs, commits, rollbacks, etc.
     *
     * @return the maximum number of entries processed per second from LogMiner sessions
     */
    long getMaxBatchProcessingThroughput();

    /**
     * This represents the average number of entries processed per second from LogMiner sessions.
     * Entries include things such as DMLs, commits, rollbacks, etc.
     *
     * @return the average number of entries per second from LogMiner sessions
     */
    long getAverageBatchProcessingThroughput();

    /**
     * This represents the number of entries processed per second in the last LogMiner session.
     * Entries include things such as DMLs, commits, rollbacks, etc.
     *
     * @return the number of entries processed per second from last LogMiner session
     */
    long getLastBatchProcessingThroughput();

    /**
     * @return the number of connection problems detected
     */
    long getNetworkConnectionProblemsCounter();

    /**
     * @return the total number of milliseconds used to parse DDL/DML statements
     */
    long getTotalParseTimeInMilliseconds();

    /**
     * @return the total number of milliseconds spent starting a log mining session
     */
    long getTotalMiningSessionStartTimeInMilliseconds();

    /**
     * @return the total number of milliseconds spent mining and processing results
     */
    long getTotalProcessingTimeInMilliseconds();

    /**
     * @return the total number of log miner rows processed.
     */
    long getTotalProcessedRows();

    /**
     * @return the total number of milliseconds spent iterating log miner results calling next.
     */
    long getTotalResultSetNextTimeInMilliseconds();

    /**
     * Resets metrics.
     */
    void reset();

    // *** following metrics work if RecordMiningHistory is true.

    /**
     * @return the number of hours to keep transaction in buffer before abandoning
     */
    int getHoursToKeepTransactionInBuffer();
}
