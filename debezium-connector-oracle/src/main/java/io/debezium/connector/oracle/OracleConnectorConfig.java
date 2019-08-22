/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.connector.oracle;

import io.debezium.config.CommonConnectorConfig;
import io.debezium.config.Configuration;
import io.debezium.config.EnumeratedValue;
import io.debezium.config.Field;
import io.debezium.connector.AbstractSourceInfo;
import io.debezium.connector.SourceInfoStructMaker;
import io.debezium.connector.oracle.xstream.LcrPosition;
import io.debezium.connector.oracle.xstream.OracleVersion;
import io.debezium.document.Document;
import io.debezium.heartbeat.Heartbeat;
import io.debezium.jdbc.JdbcConfiguration;
import io.debezium.relational.HistorizedRelationalDatabaseConnectorConfig;
import io.debezium.relational.RelationalDatabaseConnectorConfig;
import io.debezium.relational.TableId;
import io.debezium.relational.Tables.TableFilter;
import io.debezium.relational.history.HistoryRecordComparator;
import io.debezium.relational.history.KafkaDatabaseHistory;
import org.apache.kafka.common.config.ConfigDef;
import org.apache.kafka.common.config.ConfigDef.Importance;
import org.apache.kafka.common.config.ConfigDef.Type;
import org.apache.kafka.common.config.ConfigDef.Width;

/**
 * Connector configuration for Oracle.
 * Includes both XStream and LogMiner configs
 *
 * @author Gunnar Morling
 */
public class OracleConnectorConfig extends HistorizedRelationalDatabaseConnectorConfig {

    // TODO pull up to RelationalConnectorConfig
    public static final String DATABASE_CONFIG_PREFIX = "database.";

    public static final Field DATABASE_NAME = Field.create(DATABASE_CONFIG_PREFIX + JdbcConfiguration.DATABASE)
            .withDisplayName("Database name")
            .withType(Type.STRING)
            .withWidth(Width.MEDIUM)
            .withImportance(Importance.HIGH)
            .withValidation(Field::isRequired)
            .withDescription("The name of the database the connector should be monitoring. When working with a "
                    + "multi-tenant set-up, must be set to the CDB name.");

    public static final Field PDB_NAME = Field.create(DATABASE_CONFIG_PREFIX + "pdb.name")
            .withDisplayName("PDB name")
            .withType(Type.STRING)
            .withWidth(Width.MEDIUM)
            .withImportance(Importance.HIGH)
            .withDescription("Name of the pluggable database when working with a multi-tenant set-up. "
                    + "The CDB name must be given via " + DATABASE_NAME.name() + " in this case.");


    public static final Field SCHEMA_NAME = Field.create(DATABASE_CONFIG_PREFIX + "schema")
            .withDisplayName("Schema name")
            .withType(Type.STRING)
            .withWidth(Width.MEDIUM)
            .withImportance(Importance.HIGH)
            .withDescription("Name of the connection user to the database ");

    public static final Field XSTREAM_SERVER_NAME = Field.create(DATABASE_CONFIG_PREFIX + "out.server.name")
            .withDisplayName("XStream out server name")
            .withType(Type.STRING)
            .withWidth(Width.MEDIUM)
            .withImportance(Importance.HIGH)
            .withValidation(Field::isRequired)
            .withDescription("Name of the XStream Out server to connect to.");

    public static final Field SNAPSHOT_MODE = Field.create("snapshot.mode")
            .withDisplayName("Snapshot mode")
            .withEnum(SnapshotMode.class, SnapshotMode.INITIAL)
            .withWidth(Width.SHORT)
            .withImportance(Importance.LOW)
            .withDescription("The criteria for running a snapshot upon startup of the connector. "
                    + "Options include: "
                    + "'initial' (the default) to specify the connector should run a snapshot only when no offsets are available for the logical server name; "
                    + "'initial_schema_only' to specify the connector should run a snapshot of the schema when no offsets are available for the logical server name. ");

    public static final Field TABLENAME_CASE_INSENSITIVE = Field.create("database.tablename.case.insensitive")
        .withDisplayName("Case insensitive table names")
        .withType(Type.BOOLEAN)
        .withDefault(false)
        .withImportance(Importance.LOW)
        .withDescription("Case insensitive table names; set to 'true' for Oracle 11g, 'false' (default) otherwise.");

    public static final Field ORACLE_VERSION = Field.create("database.oracle.version")
        .withDisplayName("Oracle version, 11 or 12+")
        .withEnum(OracleVersion.class, OracleVersion.V12Plus)
        .withImportance(Importance.LOW)
        .withDescription("For default Oracle 12+, use default pos_version value v2, for Oracle 11, use pos_version value v1.");

    public static final Field SERVER_NAME = RelationalDatabaseConnectorConfig.SERVER_NAME
            .withValidation(CommonConnectorConfig::validateServerNameIsDifferentFromHistoryTopicName);

    public static final Field CONNECTOR_ADAPTER = Field.create(DATABASE_CONFIG_PREFIX + "connection.adapter")
            .withDisplayName("Connector adapter")
            .withEnum(ConnectorAdapter.class, ConnectorAdapter.XSTREAM)
            .withWidth(Width.MEDIUM)
            .withImportance(Importance.HIGH)
            .withDescription("There are two adapters: XStream and LogMiner.");

    public static final Field SNAPSHOT_SKIP_LOCKS = Field.create("snapshot.skip.locks")
            .withDisplayName("Should schema be locked as we build the schema snapshot?")
            .withType(Type.BOOLEAN)
            .withWidth(Width.SHORT)
            .withImportance(Importance.LOW)
            .withDefault(false)
            .withValidation(Field::isBoolean)
            .withDescription("If true, locks all tables to be captured as we build the schema snapshot. This will prevent from any concurrent schema changes being applied to them.");

    public static final Field LOG_MINING_STRATEGY = Field.create("log.mining.strategy")
            .withDisplayName("Log Mining Strategy")
            .withEnum(LogMiningStrategy.class, LogMiningStrategy.CATALOG_IN_REDO)
            .withWidth(Width.MEDIUM)
            .withImportance(Importance.HIGH)
            .withDescription("There are strategies: Online catalog with faster mining but no captured DDL. Another - with data dictionary loaded into REDO LOG files");

    // this option could be true up to Oracle 18c version. Starting from Oracle 19c this option cannot be true todo should we do it?
    public static final Field CONTINUOUS_MINE = Field.create("log.mining.continuous.mine")
            .withDisplayName("Should log mining session configured with CONTINUOUS_MINE setting?")
            .withType(Type.BOOLEAN)
            .withWidth(Width.SHORT)
            .withImportance(Importance.LOW)
            .withDefault(false)
            .withValidation(Field::isBoolean)
            .withDescription("If true, CONTINUOUS_MINE option will be added to the log mining session. This will manage log files switches seamlessly.");

    public static final Field SNAPSHOT_ENHANCEMENT_TOKEN = Field.create("snapshot.enhance.predicate.scn")
            .withDisplayName("A string to replace on snapshot predicate enhancement")
            .withType(Type.STRING)
            .withWidth(Width.MEDIUM)
            .withImportance(Importance.HIGH)
            .withDescription("A token to replace on snapshot predicate template");
// todo continues_mine option
    /**
     * The set of {@link Field}s defined as part of this configuration.
     */
    public static Field.Set ALL_FIELDS = Field.setOf(
            SERVER_NAME,
            DATABASE_NAME,
            PDB_NAME,
            XSTREAM_SERVER_NAME,
            SNAPSHOT_MODE,
            HistorizedRelationalDatabaseConnectorConfig.DATABASE_HISTORY,
            RelationalDatabaseConnectorConfig.TABLE_WHITELIST,
            RelationalDatabaseConnectorConfig.TABLE_BLACKLIST,
            RelationalDatabaseConnectorConfig.TABLE_IGNORE_BUILTIN,
            CommonConnectorConfig.POLL_INTERVAL_MS,
            CommonConnectorConfig.MAX_BATCH_SIZE,
            CommonConnectorConfig.MAX_QUEUE_SIZE,
            CommonConnectorConfig.SNAPSHOT_DELAY_MS,
            CommonConnectorConfig.SNAPSHOT_FETCH_SIZE,
            SNAPSHOT_SKIP_LOCKS,
            Heartbeat.HEARTBEAT_INTERVAL,
            Heartbeat.HEARTBEAT_TOPICS_PREFIX,
            TABLENAME_CASE_INSENSITIVE,
            ORACLE_VERSION,
            SCHEMA_NAME,
            CONNECTOR_ADAPTER,
            LOG_MINING_STRATEGY,
            SNAPSHOT_ENHANCEMENT_TOKEN
    );

    private final String databaseName;
    private final String pdbName;
    private final String xoutServerName;
    private final SnapshotMode snapshotMode;

    private final boolean tablenameCaseInsensitive;
    private final OracleVersion oracleVersion;
    private final String schemaName;

    public OracleConnectorConfig(Configuration config) {
        super(config, config.getString(SERVER_NAME), new SystemTablesPredicate());

        this.databaseName = config.getString(DATABASE_NAME);
        this.pdbName = config.getString(PDB_NAME);
        this.xoutServerName = config.getString(XSTREAM_SERVER_NAME);
        this.snapshotMode = SnapshotMode.parse(config.getString(SNAPSHOT_MODE));
        this.tablenameCaseInsensitive = config.getBoolean(TABLENAME_CASE_INSENSITIVE);
        this.oracleVersion = OracleVersion.parse(config.getString(ORACLE_VERSION));
        this.schemaName = config.getString(SCHEMA_NAME);
    }

    public static ConfigDef configDef() {
        ConfigDef config = new ConfigDef();

        Field.group(config, "Oracle", SERVER_NAME, DATABASE_NAME, PDB_NAME,
                XSTREAM_SERVER_NAME, SNAPSHOT_MODE, CONNECTOR_ADAPTER, LOG_MINING_STRATEGY);
        Field.group(config, "History Storage", KafkaDatabaseHistory.BOOTSTRAP_SERVERS,
                KafkaDatabaseHistory.TOPIC, KafkaDatabaseHistory.RECOVERY_POLL_ATTEMPTS,
                KafkaDatabaseHistory.RECOVERY_POLL_INTERVAL_MS, HistorizedRelationalDatabaseConnectorConfig.DATABASE_HISTORY);
        Field.group(config, "Events", RelationalDatabaseConnectorConfig.TABLE_WHITELIST,
                RelationalDatabaseConnectorConfig.TABLE_BLACKLIST,
                RelationalDatabaseConnectorConfig.TABLE_IGNORE_BUILTIN,
                Heartbeat.HEARTBEAT_INTERVAL, Heartbeat.HEARTBEAT_TOPICS_PREFIX
        );
        Field.group(config, "Connector", CommonConnectorConfig.POLL_INTERVAL_MS, CommonConnectorConfig.MAX_BATCH_SIZE,
                CommonConnectorConfig.MAX_QUEUE_SIZE, CommonConnectorConfig.SNAPSHOT_DELAY_MS, CommonConnectorConfig.SNAPSHOT_FETCH_SIZE,
                SNAPSHOT_SKIP_LOCKS, SNAPSHOT_ENHANCEMENT_TOKEN
                );

        return config;
    }

    public String getDatabaseName() {
        return databaseName;
    }

    public String getPdbName() {
        return pdbName;
    }

    public String getXoutServerName() {
        return xoutServerName;
    }

    public SnapshotMode getSnapshotMode() {
        return snapshotMode;
    }

    public boolean  getTablenameCaseInsensitive() {
        return tablenameCaseInsensitive;
    }

    public OracleVersion getOracleVersion() {
        return oracleVersion;
    }

    public String getSchemaName(){
        return schemaName;
    }

    @Override
    protected HistoryRecordComparator getHistoryRecordComparator() {
        return new HistoryRecordComparator() {
            @Override
            protected boolean isPositionAtOrBefore(Document recorded, Document desired) {
                final LcrPosition recordedPosition = LcrPosition.valueOf(recorded.getString(SourceInfo.LCR_POSITION_KEY));
                final LcrPosition desiredPosition = LcrPosition.valueOf(desired.getString(SourceInfo.LCR_POSITION_KEY));
                final Long recordedScn = recordedPosition != null ? recordedPosition.getScn() : recorded.getLong(SourceInfo.SCN_KEY);
                final Long desiredScn = desiredPosition != null ? desiredPosition.getScn() : desired.getLong(SourceInfo.SCN_KEY);

                return (recordedPosition != null && desiredPosition != null)
                        ? recordedPosition.compareTo(desiredPosition) < 1
                        : recordedScn.compareTo(desiredScn) < 1;
            }
        };
    }

    /**
     * The set of predefined SnapshotMode options or aliases.
     */
    public static enum SnapshotMode implements EnumeratedValue {

        /**
         * Perform a snapshot of data and schema upon initial startup of a connector.
         */
        INITIAL("initial", true),

        /**
         * Perform a snapshot of the schema but no data upon initial startup of a connector.
         */
        INITIAL_SCHEMA_ONLY("initial_schema_only", false);

        private final String value;
        private final boolean includeData;

        private SnapshotMode(String value, boolean includeData) {
            this.value = value;
            this.includeData = includeData;
        }

        @Override
        public String getValue() {
            return value;
        }

        /**
         * Whether this snapshotting mode should include the actual data or just the
         * schema of captured tables.
         */
        public boolean includeData() {
            return includeData;
        }

        /**
         * Determine if the supplied value is one of the predefined options.
         *
         * @param value the configuration property value; may not be null
         * @return the matching option, or null if no match is found
         */
        public static SnapshotMode parse(String value) {
            if (value == null) {
                return null;
            }
            value = value.trim();

            for (SnapshotMode option : SnapshotMode.values()) {
                if (option.getValue().equalsIgnoreCase(value)) {
                    return option;
                }
            }

            return null;
        }

        /**
         * Determine if the supplied value is one of the predefined options.
         *
         * @param value the configuration property value; may not be null
         * @param defaultValue the default value; may be null
         * @return the matching option, or null if no match is found and the non-null default is invalid
         */
        public static SnapshotMode parse(String value, String defaultValue) {
            SnapshotMode mode = parse(value);

            if (mode == null && defaultValue != null) {
                mode = parse(defaultValue);
            }

            return mode;
        }
    }

    public enum ConnectorAdapter implements EnumeratedValue {

        /**
         * This is based on XStream API.
         */
        XSTREAM("XStream"),

        /**
         * This is based on LogMiner utility.
         */
        LOG_MINER("LogMiner");

        private final String value;

        ConnectorAdapter(String value) {
            this.value = value;
        }

        @Override
        public String getValue() {
            return value;
        }

        /**
         * Determine if the supplied value is one of the predefined options.
         *
         * @param value the configuration property value; may not be null
         * @return the matching option, or null if no match is found
         */
        public static ConnectorAdapter parse(String value) {
            if (value == null) {
                return null;
            }
            value = value.trim();
            for (ConnectorAdapter adapter : ConnectorAdapter.values()) {
                if (adapter.getValue().equalsIgnoreCase(value)) {
                    return adapter;
                }
            }
            return null;
        }

        public static ConnectorAdapter parse(String value, String defaultValue) {
            ConnectorAdapter mode = parse(value);

            if (mode == null && defaultValue != null) {
                mode = parse(defaultValue);
            }

            return mode;
        }
    }

    public enum LogMiningStrategy implements EnumeratedValue {

        /**
         * This strategy uses Log Miner with data dictionary in online catalog.
         * This option will not capture DDL , but acts fast on REDO LOG switch events
         * This option does not use CONTINUOUS_MINE option
         */
        ONLINE_CATALOG("online_catalog"),

        /**
         * This strategy uses Log Miner with data dictionary in REDO LOG files.
         * This option will capture DDL, but will develop some lag on REDO LOG switch event and will eventually catch up
         * This option does not use CONTINUOUS_MINE option
         * This is default value
         */
        CATALOG_IN_REDO("redo_log_catalog");

        private final String value;

        LogMiningStrategy(String value) {
            this.value = value;
        }

        @Override
        public String getValue() {
            return value;
        }

        /**
         * Determine if the supplied value is one of the predefined options.
         *
         * @param value the configuration property value; may not be null
         * @return the matching option, or null if no match is found
         */
        public static LogMiningStrategy parse(String value) {
            if (value == null) {
                return null;
            }
            value = value.trim();
            for (LogMiningStrategy adapter : LogMiningStrategy.values()) {
                if (adapter.getValue().equalsIgnoreCase(value)) {
                    return adapter;
                }
            }
            return null;
        }

        public static LogMiningStrategy parse(String value, String defaultValue) {
            LogMiningStrategy mode = parse(value);

            if (mode == null && defaultValue != null) {
                mode = parse(defaultValue);
            }

            return mode;
        }
    }

    /**
     * A {@link TableFilter} that excludes all Oracle system tables.
     *
     * @author Gunnar Morling
     */
    private static class SystemTablesPredicate implements TableFilter {

        @Override
        public boolean isIncluded(TableId t) {
            return !t.schema().toLowerCase().equals("appqossys") &&
                    !t.schema().toLowerCase().equals("audsys") &&
                    !t.schema().toLowerCase().equals("ctxsys") &&
                    !t.schema().toLowerCase().equals("dvsys") &&
                    !t.schema().toLowerCase().equals("dbsfwuser") &&
                    !t.schema().toLowerCase().equals("dbsnmp") &&
                    !t.schema().toLowerCase().equals("gsmadmin_internal") &&
                    !t.schema().toLowerCase().equals("lbacsys") &&
                    !t.schema().toLowerCase().equals("mdsys") &&
                    !t.schema().toLowerCase().equals("ojvmsys") &&
                    !t.schema().toLowerCase().equals("olapsys") &&
                    !t.schema().toLowerCase().equals("orddata") &&
                    !t.schema().toLowerCase().equals("ordsys") &&
                    !t.schema().toLowerCase().equals("outln") &&
                    !t.schema().toLowerCase().equals("sys") &&
                    !t.schema().toLowerCase().equals("system") &&
                    !t.schema().toLowerCase().equals("wmsys") &&
                    !t.schema().toLowerCase().equals("xdb");
        }
    }

    @Override
    protected SourceInfoStructMaker<? extends AbstractSourceInfo> getSourceInfoStructMaker(Version version) {
        return new OracleSourceInfoStructMaker(Module.name(), Module.version(), this);
    }

    @Override
    public String getContextName() {
        return Module.contextName();
    }

    /**
     * @return true if lock to be obtained before building the schema
     */
    public boolean skipSnapshotLock() {
        return getConfig().getBoolean(SNAPSHOT_SKIP_LOCKS);
    }

    /**
     * @return connection adapter
     */
    public ConnectorAdapter getAdapter(){
        return ConnectorAdapter.parse(getConfig().getString(CONNECTOR_ADAPTER));
    }

    /**
     * @return Log Mining strategy
     */
    public LogMiningStrategy getLogMiningStrategy(){
        return LogMiningStrategy.parse(getConfig().getString(LOG_MINING_STRATEGY));
    }

    /**
     * @return String token to replace
     */
    public String getTokenToReplaceInSnapshotPredicate(){
        return getConfig().getString(SNAPSHOT_ENHANCEMENT_TOKEN);
    }

    public boolean isContinuousMining(){
        return getConfig().getBoolean(CONTINUOUS_MINE);
    }
}


