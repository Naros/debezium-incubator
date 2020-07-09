/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.connector.oracle.logminer;

import io.debezium.connector.oracle.OracleConnection;
import io.debezium.connector.oracle.OracleConnectorConfig;
import io.debezium.connector.oracle.OracleDatabaseSchema;
import io.debezium.connector.oracle.OracleOffsetContext;
import io.debezium.connector.oracle.OracleTaskContext;
import io.debezium.connector.oracle.jsqlparser.SimpleDmlParser;
import io.debezium.pipeline.ErrorHandler;
import io.debezium.pipeline.EventDispatcher;
import io.debezium.pipeline.source.spi.StreamingChangeEventSource;
import io.debezium.relational.TableId;
import io.debezium.util.Clock;
import io.debezium.util.Metronome;
import oracle.net.ns.NetException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLRecoverableException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static io.debezium.connector.oracle.logminer.LogMinerHelper.buildDataDictionary;
import static io.debezium.connector.oracle.logminer.LogMinerHelper.checkSupplementalLogging;
import static io.debezium.connector.oracle.logminer.LogMinerHelper.createAuditTable;
import static io.debezium.connector.oracle.logminer.LogMinerHelper.endMining;
import static io.debezium.connector.oracle.logminer.LogMinerHelper.getCurrentRedoLogFiles;
import static io.debezium.connector.oracle.logminer.LogMinerHelper.getTimeDifference;
import static io.debezium.connector.oracle.logminer.LogMinerHelper.getFirstOnlineLogScn;
import static io.debezium.connector.oracle.logminer.LogMinerHelper.setNlsSessionParameters;
import static io.debezium.connector.oracle.logminer.LogMinerHelper.setRedoLogFilesForMining;
import static io.debezium.connector.oracle.logminer.LogMinerHelper.getEndScn;
import static io.debezium.connector.oracle.logminer.LogMinerHelper.logWarn;
import static io.debezium.connector.oracle.logminer.LogMinerHelper.logError;
import static io.debezium.connector.oracle.logminer.LogMinerHelper.startOnlineMining;
import static io.debezium.connector.oracle.logminer.LogMinerHelper.getLastScnFromTheOldestOnlineRedo;

/**
 * A {@link StreamingChangeEventSource} based on Oracle's LogMiner utility.
 * The event handler loop is executed in a separate executor.
 */
public class LogMinerStreamingChangeEventSource implements StreamingChangeEventSource {

    private static final Logger LOGGER = LoggerFactory.getLogger(LogMinerStreamingChangeEventSource.class);

    private final OracleConnection jdbcConnection;
    private final EventDispatcher<TableId> dispatcher;
    private final Clock clock;
    private final OracleDatabaseSchema schema;
    private final OracleOffsetContext offsetContext;
    private final TransactionalBuffer transactionalBuffer;
    private final SimpleDmlParser dmlParser;
    private final String catalogName;
    private OracleConnectorConfig connectorConfig;
    private final TransactionalBufferMetrics transactionalBufferMetrics;
    private final LogMinerMetrics logMinerMetrics;
    private final OracleConnectorConfig.LogMiningStrategy strategy;
    private final boolean isContinuousMining;
    private long startScn;
    private long endScn;

    public LogMinerStreamingChangeEventSource(OracleConnectorConfig connectorConfig, OracleOffsetContext offsetContext,
                                              OracleConnection jdbcConnection, EventDispatcher<TableId> dispatcher,
                                              ErrorHandler errorHandler, Clock clock, OracleDatabaseSchema schema,
                                              OracleTaskContext taskContext) {
        this.jdbcConnection = jdbcConnection;
        this.dispatcher = dispatcher;
        this.clock = clock;
        this.schema = schema;
        this.offsetContext = offsetContext;
        OracleChangeRecordValueConverter converters = new OracleChangeRecordValueConverter(jdbcConnection);
        this.connectorConfig = connectorConfig;
        this.catalogName = (connectorConfig.getPdbName() != null) ? connectorConfig.getPdbName() : connectorConfig.getDatabaseName();
        this.dmlParser = new SimpleDmlParser(catalogName, connectorConfig.getSchemaName(), converters);
        this.transactionalBufferMetrics = new TransactionalBufferMetrics(taskContext);
        this.transactionalBufferMetrics.register(LOGGER);
        transactionalBuffer = new TransactionalBuffer(connectorConfig.getLogicalName(), errorHandler,
                transactionalBufferMetrics, connectorConfig.getMaxQueueSize());
        this.logMinerMetrics = new LogMinerMetrics(taskContext);
        this.logMinerMetrics.register(LOGGER);
        this.strategy = connectorConfig.getLogMiningStrategy();
        this.isContinuousMining = connectorConfig.isContinuousMining();
    }

    /**
     * This is the loop to get changes from LogMiner
     *
     * @param context change event source context
     */
    @Override
    public void execute(ChangeEventSourceContext context) {
        Metronome metronome;

        // The top outer loop gives the resiliency on the network disconnections. This is critical for cloud deployment.
        while (context.isRunning()) {
            try (Connection connection = jdbcConnection.connection(false);
                 PreparedStatement fetchFromMiningView =
                         connection.prepareStatement(SqlUtils.queryLogMinerContents(connectorConfig.getSchemaName(), jdbcConnection.username(), schema))) {

                startScn = offsetContext.getScn();
                createAuditTable(connection);
                LOGGER.trace("current millis {}, db time {}", System.currentTimeMillis(), getTimeDifference(connection));
                transactionalBufferMetrics.setTimeDifference(new AtomicLong(getTimeDifference(connection)));

                if (!isContinuousMining && startScn < getFirstOnlineLogScn(connection)) {
                    throw new RuntimeException("Online REDO LOG files don't contain the offset SCN. Clean offset and start over");
                }

                // 1. Configure Log Miner to mine online redo logs
                setNlsSessionParameters(jdbcConnection);
                checkSupplementalLogging(jdbcConnection, connectorConfig.getPdbName());

                if (strategy == OracleConnectorConfig.LogMiningStrategy.CATALOG_IN_REDO) {
                    buildDataDictionary(connection);
                }

                if (!isContinuousMining) {
                    setRedoLogFilesForMining(connection, startScn);
                }

                LogMinerQueryResultProcessor processor = new LogMinerQueryResultProcessor(context, logMinerMetrics, transactionalBuffer,
                        dmlParser, offsetContext, schema, dispatcher, transactionalBufferMetrics, catalogName, clock);

                // 2. Querying LogMiner view while running
                Set<String> currentRedoLogFiles = getCurrentRedoLogFiles(connection, logMinerMetrics);
                while (context.isRunning()) {

                    endScn = getEndScn(connection, startScn, logMinerMetrics);
                    LOGGER.trace("startScn: {}, endScn: {}", startScn, endScn);

                    metronome = Metronome.sleeper(Duration.ofMillis(logMinerMetrics.getMillisecondToSleepBetweenMiningQuery()), clock);
                    metronome.pause();

                    Set<String> possibleNewCurrentLogFile = getCurrentRedoLogFiles(connection, logMinerMetrics);
                    if (!currentRedoLogFiles.equals(possibleNewCurrentLogFile)) {
                        LOGGER.debug("\n\n***** SWITCH occurred *****\n" + " from:{} , to:{} \n\n", currentRedoLogFiles, possibleNewCurrentLogFile);

                        // This is the way to mitigate PGA leak.
                        // With one mining session it grows and maybe there is another way to flush PGA, but at this point we use new mining session
                        endMining(connection);

                        if (!isContinuousMining) {
                            if (strategy == OracleConnectorConfig.LogMiningStrategy.CATALOG_IN_REDO) {
                                buildDataDictionary(connection);
                            }

                            abandonOldTransactionsIfExist(connection);
                            setRedoLogFilesForMining(connection, startScn);
                        }

                        currentRedoLogFiles = getCurrentRedoLogFiles(connection, logMinerMetrics);
                    }

                    startOnlineMining(connection, startScn, endScn, strategy, isContinuousMining);

                    Instant startTime = Instant.now();
                    fetchFromMiningView.setFetchSize(10_000);
                    fetchFromMiningView.setLong(1, startScn);
                    fetchFromMiningView.setLong(2, endScn);

                    ResultSet res = fetchFromMiningView.executeQuery();
                    logMinerMetrics.setLastLogMinerQueryDuration(Duration.between(startTime, Instant.now()));
                    processor.processResult(res);

                    updateStartScn();
                    LOGGER.trace("largest scn = {}", transactionalBuffer.getLargestScn());

                    // update SCN in offset context only if buffer is empty, otherwise we update offset in TransactionalBuffer
                    if (transactionalBuffer.isEmpty()) {
                        offsetContext.setScn(startScn);
                        transactionalBuffer.resetLargestScn(null);
                    }

                    res.close();
                    // we don't do it for other modes to save time on building data dictionary
//                    if (strategy == OracleConnectorConfig.LogMiningStrategy.ONLINE_CATALOG) {
//                        endMining(connection);
//                        updateRedoLogMetrics(connection, logMinerMetrics);
//                        currentRedoLogFiles = getCurrentRedoLogFiles(connection, logMinerMetrics);
//                    }
                }
            } catch (Throwable e) {
                if (connectionProblem(e)) {
                    logWarn(transactionalBufferMetrics, "Disconnection occurred. {} ", e.toString());
                    continue;
                }
                logError(transactionalBufferMetrics, "Mining session was stopped due to the {} ", e.toString());
                throw new RuntimeException(e);
            } finally {
                LOGGER.info("startScn={}, endScn={}, offsetContext.getScn()={}", startScn, endScn, offsetContext.getScn());
                LOGGER.info("Transactional buffer metrics dump: {}", transactionalBufferMetrics.toString());
                LOGGER.info("Transactional buffer dump: {}", transactionalBuffer.toString());
                LOGGER.info("LogMiner metrics dump: {}", logMinerMetrics.toString());
            }
        }
    }

    private void abandonOldTransactionsIfExist(Connection connection) throws SQLException {
        Optional<Long> lastScnToAbandonTransactions = getLastScnFromTheOldestOnlineRedo(connection, offsetContext.getScn());
        lastScnToAbandonTransactions.ifPresent(thresholdScn -> {
            logWarn(transactionalBufferMetrics, "All transactions with first SCN <= {} will be abandoned, offset: {}", thresholdScn, offsetContext.getScn());
            transactionalBuffer.abandonLongTransactions(thresholdScn);
            offsetContext.setScn(thresholdScn);
            updateStartScn();
        });
    }

    private void updateStartScn() {
        long nextStartScn = transactionalBuffer.getLargestScn().equals(BigDecimal.ZERO) ? endScn : transactionalBuffer.getLargestScn().longValue();
        if (nextStartScn <= startScn) {
            // When system is idle, largest SCN may stay unchanged, move it forward then
            transactionalBuffer.resetLargestScn(endScn);
        }
        startScn = endScn;
    }

    @Override
    public void commitOffset(Map<String, ?> offset) {
        // nothing to do
    }

    private boolean connectionProblem(Throwable e) {
        if (e.getMessage() == null || e.getCause() == null) {
            return false;
        }
        return e.getMessage().startsWith("ORA-03135") || // connection lost contact
                e.getMessage().startsWith("ORA-12543") || // TNS:destination host unreachable
                e.getMessage().startsWith("ORA-00604") || // error occurred at recursive SQL level 1
                e.getMessage().startsWith("ORA-01089") || // Oracle immediate shutdown in progress
                e.getCause() instanceof IOException ||
                e instanceof SQLRecoverableException ||
                e.getMessage().toUpperCase().startsWith("NO MORE DATA TO READ FROM SOCKET") ||
                e.getCause().getCause() instanceof NetException;
    }
}
