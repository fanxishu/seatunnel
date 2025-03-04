/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.seatunnel.connectors.seatunnel.jdbc.internal.dialect;

import org.apache.seatunnel.api.table.catalog.TablePath;
import org.apache.seatunnel.api.table.converter.BasicTypeDefine;
import org.apache.seatunnel.api.table.converter.TypeConverter;
import org.apache.seatunnel.api.table.schema.event.AlterTableAddColumnEvent;
import org.apache.seatunnel.api.table.schema.event.AlterTableChangeColumnEvent;
import org.apache.seatunnel.api.table.schema.event.AlterTableColumnEvent;
import org.apache.seatunnel.api.table.schema.event.AlterTableColumnsEvent;
import org.apache.seatunnel.api.table.schema.event.AlterTableDropColumnEvent;
import org.apache.seatunnel.api.table.schema.event.AlterTableModifyColumnEvent;
import org.apache.seatunnel.api.table.schema.event.SchemaChangeEvent;
import org.apache.seatunnel.api.table.type.SqlType;
import org.apache.seatunnel.connectors.seatunnel.jdbc.config.JdbcConnectionConfig;
import org.apache.seatunnel.connectors.seatunnel.jdbc.internal.connection.JdbcConnectionProvider;
import org.apache.seatunnel.connectors.seatunnel.jdbc.internal.connection.SimpleJdbcConnectionProvider;
import org.apache.seatunnel.connectors.seatunnel.jdbc.internal.converter.JdbcRowConverter;
import org.apache.seatunnel.connectors.seatunnel.jdbc.internal.dialect.dialectenum.FieldIdeEnum;
import org.apache.seatunnel.connectors.seatunnel.jdbc.source.JdbcSourceTable;
import org.apache.seatunnel.connectors.seatunnel.jdbc.utils.DefaultValueUtils;

import org.apache.commons.lang3.StringUtils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import static java.lang.String.format;

/**
 * Represents a dialect of SQL implemented by a particular JDBC system. Dialects should be immutable
 * and stateless.
 */
public interface JdbcDialect extends Serializable {

    Logger log = LoggerFactory.getLogger(JdbcDialect.class.getName());

    /**
     * Get the name of jdbc dialect.
     *
     * @return the dialect name.
     */
    String dialectName();

    /**
     * Get converter that convert jdbc object to seatunnel internal object.
     *
     * @return a row converter for the database
     */
    JdbcRowConverter getRowConverter();

    /**
     * Get converter that convert type object to seatunnel internal type.
     *
     * @return a type converter for the database
     */
    default TypeConverter<BasicTypeDefine> getTypeConverter() {
        throw new UnsupportedOperationException("TypeConverter is not supported");
    }

    /**
     * get jdbc meta-information type to seatunnel data type mapper.
     *
     * @return a type mapper for the database
     */
    JdbcDialectTypeMapper getJdbcDialectTypeMapper();

    default String hashModForField(String nativeType, String fieldName, int mod) {
        return hashModForField(fieldName, mod);
    }

    default String hashModForField(String fieldName, int mod) {
        return "ABS(MD5(" + quoteIdentifier(fieldName) + ") % " + mod + ")";
    }

    /** Quotes the identifier for table name or field name */
    default String quoteIdentifier(String identifier) {
        return identifier;
    }
    /** Quotes the identifier for database name or field name */
    default String quoteDatabaseIdentifier(String identifier) {
        return identifier;
    }

    default String tableIdentifier(String database, String tableName) {
        return quoteDatabaseIdentifier(database) + "." + quoteIdentifier(tableName);
    }

    /**
     * Constructs the dialects insert statement for a single row. The returned string will be used
     * as a {@link java.sql.PreparedStatement}. Fields in the statement must be in the same order as
     * the {@code fieldNames} parameter.
     *
     * <pre>{@code
     * INSERT INTO table_name (column_name [, ...]) VALUES (value [, ...])
     * }</pre>
     *
     * @return the dialects {@code INSERT INTO} statement.
     */
    default String getInsertIntoStatement(String database, String tableName, String[] fieldNames) {
        String columns =
                Arrays.stream(fieldNames)
                        .map(this::quoteIdentifier)
                        .collect(Collectors.joining(", "));
        String placeholders =
                Arrays.stream(fieldNames)
                        .map(fieldName -> ":" + fieldName)
                        .collect(Collectors.joining(", "));
        return String.format(
                "INSERT INTO %s (%s) VALUES (%s)",
                tableIdentifier(database, tableName), columns, placeholders);
    }

    /**
     * Constructs the dialects update statement for a single row with the given condition. The
     * returned string will be used as a {@link java.sql.PreparedStatement}. Fields in the statement
     * must be in the same order as the {@code fieldNames} parameter.
     *
     * <pre>{@code
     * UPDATE table_name SET col = val [, ...] WHERE cond [AND ...]
     * }</pre>
     *
     * @return the dialects {@code UPDATE} statement.
     */
    default String getUpdateStatement(
            String database,
            String tableName,
            String[] fieldNames,
            String[] conditionFields,
            boolean isPrimaryKeyUpdated) {

        fieldNames =
                Arrays.stream(fieldNames)
                        .filter(
                                fieldName ->
                                        isPrimaryKeyUpdated
                                                || !Arrays.asList(conditionFields)
                                                        .contains(fieldName))
                        .toArray(String[]::new);

        String setClause =
                Arrays.stream(fieldNames)
                        .map(fieldName -> format("%s = :%s", quoteIdentifier(fieldName), fieldName))
                        .collect(Collectors.joining(", "));
        String conditionClause =
                Arrays.stream(conditionFields)
                        .map(fieldName -> format("%s = :%s", quoteIdentifier(fieldName), fieldName))
                        .collect(Collectors.joining(" AND "));
        return String.format(
                "UPDATE %s SET %s WHERE %s",
                tableIdentifier(database, tableName), setClause, conditionClause);
    }

    /**
     * Constructs the dialects delete statement for a single row with the given condition. The
     * returned string will be used as a {@link java.sql.PreparedStatement}. Fields in the statement
     * must be in the same order as the {@code fieldNames} parameter.
     *
     * <pre>{@code
     * DELETE FROM table_name WHERE cond [AND ...]
     * }</pre>
     *
     * @return the dialects {@code DELETE} statement.
     */
    default String getDeleteStatement(String database, String tableName, String[] conditionFields) {
        String conditionClause =
                Arrays.stream(conditionFields)
                        .map(fieldName -> format("%s = :%s", quoteIdentifier(fieldName), fieldName))
                        .collect(Collectors.joining(" AND "));
        return String.format(
                "DELETE FROM %s WHERE %s", tableIdentifier(database, tableName), conditionClause);
    }

    /**
     * Generates a query to determine if a row exists in the table. The returned string will be used
     * as a {@link java.sql.PreparedStatement}.
     *
     * <pre>{@code
     * SELECT 1 FROM table_name WHERE cond [AND ...]
     * }</pre>
     *
     * @return the dialects {@code QUERY} statement.
     */
    default String getRowExistsStatement(
            String database, String tableName, String[] conditionFields) {
        String fieldExpressions =
                Arrays.stream(conditionFields)
                        .map(field -> format("%s = :%s", quoteIdentifier(field), field))
                        .collect(Collectors.joining(" AND "));
        return String.format(
                "SELECT 1 FROM %s WHERE %s",
                tableIdentifier(database, tableName), fieldExpressions);
    }

    /**
     * Constructs the dialects upsert statement if supported; such as MySQL's {@code DUPLICATE KEY
     * UPDATE}, or PostgreSQL's {@code ON CONFLICT... DO UPDATE SET..}.
     *
     * <p>If supported, the returned string will be used as a {@link java.sql.PreparedStatement}.
     * Fields in the statement must be in the same order as the {@code fieldNames} parameter.
     *
     * <p>If the dialect does not support native upsert statements, the writer will fallback to
     * {@code SELECT ROW Exists} + {@code UPDATE}/{@code INSERT} which may have poor performance.
     *
     * @return the dialects {@code UPSERT} statement or {@link Optional#empty()}.
     */
    Optional<String> getUpsertStatement(
            String database, String tableName, String[] fieldNames, String[] uniqueKeyFields);

    /**
     * Different dialects optimize their PreparedStatement
     *
     * @return The logic about optimize PreparedStatement
     */
    default PreparedStatement creatPreparedStatement(
            Connection connection, String queryTemplate, int fetchSize) throws SQLException {
        PreparedStatement statement =
                connection.prepareStatement(
                        queryTemplate, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
        if (fetchSize == Integer.MIN_VALUE || fetchSize > 0) {
            statement.setFetchSize(fetchSize);
        }
        return statement;
    }

    default ResultSetMetaData getResultSetMetaData(Connection conn, String query)
            throws SQLException {
        PreparedStatement ps = conn.prepareStatement(query);
        return ps.getMetaData();
    }

    default String extractTableName(TablePath tablePath) {
        return tablePath.getSchemaAndTableName();
    }

    default String getFieldIde(String identifier, String fieldIde) {
        if (StringUtils.isEmpty(fieldIde)) {
            return identifier;
        }
        switch (FieldIdeEnum.valueOf(fieldIde.toUpperCase())) {
            case LOWERCASE:
                return identifier.toLowerCase();
            case UPPERCASE:
                return identifier.toUpperCase();
            default:
                return identifier;
        }
    }

    default Map<String, String> defaultParameter() {
        return new HashMap<>();
    }

    default void connectionUrlParse(
            String url, Map<String, String> info, Map<String, String> defaultParameter) {
        defaultParameter.forEach(
                (key, value) -> {
                    if (!url.contains(key) && !info.containsKey(key)) {
                        info.put(key, value);
                    }
                });
    }

    default TablePath parse(String tablePath) {
        return TablePath.of(tablePath);
    }

    default String tableIdentifier(TablePath tablePath) {
        return tablePath.getFullName();
    }

    /**
     * Approximate total number of entries in the lookup table.
     *
     * @param connection The JDBC connection object used to connect to the database.
     * @param table table info.
     * @return approximate row count statement.
     */
    default Long approximateRowCntStatement(Connection connection, JdbcSourceTable table)
            throws SQLException {
        if (StringUtils.isNotBlank(table.getQuery())) {
            return SQLUtils.countForSubquery(connection, table.getQuery());
        }
        return SQLUtils.countForTable(connection, tableIdentifier(table.getTablePath()));
    }

    /**
     * Performs a sampling operation on the specified column of a table in a JDBC-connected
     * database.
     *
     * @param connection The JDBC connection object used to connect to the database.
     * @param table The table in which the column resides.
     * @param columnName The name of the column to be sampled.
     * @param samplingRate samplingRate The inverse of the fraction of the data to be sampled from
     *     the column. For example, a value of 1000 would mean 1/1000 of the data will be sampled.
     * @return Returns a List of sampled data from the specified column.
     * @throws SQLException If an SQL error occurs during the sampling operation.
     */
    default Object[] sampleDataFromColumn(
            Connection connection,
            JdbcSourceTable table,
            String columnName,
            int samplingRate,
            int fetchSize)
            throws Exception {
        String sampleQuery;
        if (StringUtils.isNotBlank(table.getQuery())) {
            sampleQuery =
                    String.format(
                            "SELECT %s FROM (%s) AS T",
                            quoteIdentifier(columnName), table.getQuery());
        } else {
            sampleQuery =
                    String.format(
                            "SELECT %s FROM %s",
                            quoteIdentifier(columnName), tableIdentifier(table.getTablePath()));
        }

        try (PreparedStatement stmt = creatPreparedStatement(connection, sampleQuery, fetchSize)) {
            log.info(String.format("Split Chunk, approximateRowCntStatement: %s", sampleQuery));
            try (ResultSet rs = stmt.executeQuery()) {
                int count = 0;
                List<Object> results = new ArrayList<>();

                while (rs.next()) {
                    count++;
                    if (count % samplingRate == 0) {
                        results.add(rs.getObject(1));
                    }
                    if (Thread.currentThread().isInterrupted()) {
                        throw new InterruptedException("Thread interrupted");
                    }
                }
                Object[] resultsArray = results.toArray();
                Arrays.sort(resultsArray);
                return resultsArray;
            }
        }
    }

    /**
     * Query the maximum value of the next chunk, and the next chunk must be greater than or equal
     * to <code>includedLowerBound</code> value [min_1, max_1), [min_2, max_2),... [min_n, null).
     * Each time this method is called it will return max1, max2...
     *
     * @param connection JDBC connection.
     * @param table table info.
     * @param columnName column name.
     * @param chunkSize chunk size.
     * @param includedLowerBound the previous chunk end value.
     * @return next chunk end value.
     */
    default Object queryNextChunkMax(
            Connection connection,
            JdbcSourceTable table,
            String columnName,
            int chunkSize,
            Object includedLowerBound)
            throws SQLException {
        String quotedColumn = quoteIdentifier(columnName);
        String sqlQuery;
        if (StringUtils.isNotBlank(table.getQuery())) {
            sqlQuery =
                    String.format(
                            "SELECT MAX(%s) FROM ("
                                    + "SELECT %s FROM (%s) AS T1 WHERE %s >= ? ORDER BY %s ASC LIMIT %s"
                                    + ") AS T2",
                            quotedColumn,
                            quotedColumn,
                            table.getQuery(),
                            quotedColumn,
                            quotedColumn,
                            chunkSize);
        } else {
            sqlQuery =
                    String.format(
                            "SELECT MAX(%s) FROM ("
                                    + "SELECT %s FROM %s WHERE %s >= ? ORDER BY %s ASC LIMIT %s"
                                    + ") AS T",
                            quotedColumn,
                            quotedColumn,
                            tableIdentifier(table.getTablePath()),
                            quotedColumn,
                            quotedColumn,
                            chunkSize);
        }
        try (PreparedStatement ps = connection.prepareStatement(sqlQuery)) {
            ps.setObject(1, includedLowerBound);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getObject(1);
                } else {
                    // this should never happen
                    throw new SQLException(
                            String.format("No result returned after running query [%s]", sqlQuery));
                }
            }
        }
    }

    default JdbcConnectionProvider getJdbcConnectionProvider(
            JdbcConnectionConfig jdbcConnectionConfig) {
        return new SimpleJdbcConnectionProvider(jdbcConnectionConfig);
    }

    /**
     * Cast column type e.g. CAST(column AS type)
     *
     * @param columnName
     * @param columnType
     * @return the text of converted column type.
     */
    default String convertType(String columnName, String columnType) {
        return columnName;
    }

    /**
     * Refresh physical table schema by schema change event
     *
     * @param connection jdbc connection
     * @param tablePath sink table path
     * @param event schema change event
     */
    default void applySchemaChange(
            Connection connection, TablePath tablePath, SchemaChangeEvent event)
            throws SQLException {
        if (event instanceof AlterTableColumnsEvent) {
            for (AlterTableColumnEvent columnEvent : ((AlterTableColumnsEvent) event).getEvents()) {
                applySchemaChange(connection, tablePath, columnEvent);
            }
        } else {
            if (event instanceof AlterTableChangeColumnEvent) {
                AlterTableChangeColumnEvent changeColumnEvent = (AlterTableChangeColumnEvent) event;
                if (!changeColumnEvent
                        .getOldColumn()
                        .equals(changeColumnEvent.getColumn().getName())) {
                    if (!columnExists(connection, tablePath, changeColumnEvent.getOldColumn())
                            && columnExists(
                                    connection,
                                    tablePath,
                                    changeColumnEvent.getColumn().getName())) {
                        log.warn(
                                "Column {} already exists in table {}. Skipping change column operation. event: {}",
                                changeColumnEvent.getColumn().getName(),
                                tablePath.getFullName(),
                                event);
                        return;
                    }
                }
                applySchemaChange(connection, tablePath, changeColumnEvent);
            } else if (event instanceof AlterTableModifyColumnEvent) {
                applySchemaChange(connection, tablePath, (AlterTableModifyColumnEvent) event);
            } else if (event instanceof AlterTableAddColumnEvent) {
                AlterTableAddColumnEvent addColumnEvent = (AlterTableAddColumnEvent) event;
                if (columnExists(connection, tablePath, addColumnEvent.getColumn().getName())) {
                    log.warn(
                            "Column {} already exists in table {}. Skipping add column operation. event: {}",
                            addColumnEvent.getColumn().getName(),
                            tablePath.getFullName(),
                            event);
                    return;
                }
                applySchemaChange(connection, tablePath, addColumnEvent);
            } else if (event instanceof AlterTableDropColumnEvent) {
                AlterTableDropColumnEvent dropColumnEvent = (AlterTableDropColumnEvent) event;
                if (!columnExists(connection, tablePath, dropColumnEvent.getColumn())) {
                    log.warn(
                            "Column {} does not exist in table {}. Skipping drop column operation. event: {}",
                            dropColumnEvent.getColumn(),
                            tablePath.getFullName(),
                            event);
                    return;
                }
                applySchemaChange(connection, tablePath, dropColumnEvent);
            } else {
                throw new UnsupportedOperationException("Unsupported schemaChangeEvent: " + event);
            }
        }
    }

    /**
     * Check if the column exists in the table
     *
     * @param connection
     * @param tablePath
     * @param column
     * @return
     */
    default boolean columnExists(Connection connection, TablePath tablePath, String column) {
        String selectColumnSQL =
                String.format(
                        "SELECT %s FROM %s WHERE 1 != 1",
                        quoteIdentifier(column), tableIdentifier(tablePath));
        try (Statement statement = connection.createStatement()) {
            return statement.execute(selectColumnSQL);
        } catch (SQLException e) {
            log.debug("Column {} does not exist in table {}", column, tablePath.getFullName(), e);
            return false;
        }
    }

    default void applySchemaChange(
            Connection connection, TablePath tablePath, AlterTableAddColumnEvent event)
            throws SQLException {
        String sourceDialectName = event.getSourceDialectName();
        boolean sameCatalog = StringUtils.equals(dialectName(), sourceDialectName);
        BasicTypeDefine typeDefine = getTypeConverter().reconvert(event.getColumn());
        String columnType =
                sameCatalog ? event.getColumn().getSourceType() : typeDefine.getColumnType();
        StringBuilder sqlBuilder =
                new StringBuilder()
                        .append("ALTER TABLE")
                        .append(" ")
                        .append(tableIdentifier(tablePath))
                        .append(" ")
                        .append("ADD COLUMN")
                        .append(" ")
                        .append(quoteIdentifier(event.getColumn().getName()))
                        .append(" ")
                        .append(columnType);

        // Only decorate with default value when source dialect is same as sink dialect
        // Todo Support for cross-database default values for ddl statements
        if (event.getColumn().getDefaultValue() == null) {
            sqlBuilder.append(" ").append(event.getColumn().isNullable() ? "NULL" : "NOT NULL");
        } else {
            if (event.getColumn().isNullable()) {
                sqlBuilder.append(" NULL");
            } else if (sameCatalog) {
                sqlBuilder.append(" ").append(event.getColumn().isNullable() ? "NULL" : "NOT NULL");
            } else if (SqlType.TIMESTAMP.equals(event.getColumn().getDataType().getSqlType())) {
                log.warn(
                        "Default value is not supported for column {} in table {}. Skipping add column operation. event: {}",
                        event.getColumn().getName(),
                        tablePath.getFullName(),
                        event);
            } else {
                sqlBuilder.append(" NOT NULL");
            }
            if (sameCatalog) {
                sqlBuilder
                        .append(" ")
                        .append(sqlClauseWithDefaultValue(typeDefine, sourceDialectName));
            }
        }

        if (event.getColumn().getComment() != null) {
            sqlBuilder
                    .append(" ")
                    .append("COMMENT ")
                    .append("'")
                    .append(event.getColumn().getComment())
                    .append("'");
        }
        if (event.getAfterColumn() != null) {
            sqlBuilder.append(" ").append("AFTER ").append(quoteIdentifier(event.getAfterColumn()));
        }

        String addColumnSQL = sqlBuilder.toString();
        try (Statement statement = connection.createStatement()) {
            log.info("Executing add column SQL: {}", addColumnSQL);
            statement.execute(addColumnSQL);
        }
    }

    default void applySchemaChange(
            Connection connection, TablePath tablePath, AlterTableChangeColumnEvent event)
            throws SQLException {
        if (event.getColumn().getDataType() == null) {
            StringBuilder sqlBuilder =
                    new StringBuilder()
                            .append("ALTER TABLE")
                            .append(" ")
                            .append(tableIdentifier(tablePath))
                            .append(" ")
                            .append("RENAME COLUMN")
                            .append(" ")
                            .append(quoteIdentifier(event.getOldColumn()))
                            .append(" TO ")
                            .append(quoteIdentifier(event.getColumn().getName()));
            try (Statement statement = connection.createStatement()) {
                log.info("Executing rename column SQL: {}", sqlBuilder);
                statement.execute(sqlBuilder.toString());
            }
            return;
        }
        String sourceDialectName = event.getSourceDialectName();
        boolean sameCatalog = StringUtils.equals(dialectName(), sourceDialectName);
        BasicTypeDefine typeDefine = getTypeConverter().reconvert(event.getColumn());
        String columnType =
                sameCatalog ? event.getColumn().getSourceType() : typeDefine.getColumnType();
        StringBuilder sqlBuilder =
                new StringBuilder()
                        .append("ALTER TABLE")
                        .append(" ")
                        .append(tableIdentifier(tablePath))
                        .append(" ")
                        .append("CHANGE COLUMN")
                        .append(" ")
                        .append(quoteIdentifier(event.getOldColumn()))
                        .append(" ")
                        .append(quoteIdentifier(event.getColumn().getName()))
                        .append(" ")
                        .append(columnType);
        // Only decorate with default value when source dialect is same as sink dialect
        // Todo Support for cross-database default values for ddl statements
        if (event.getColumn().getDefaultValue() == null) {
            sqlBuilder.append(" ").append(event.getColumn().isNullable() ? "NULL" : "NOT NULL");
        } else {
            if (event.getColumn().isNullable()) {
                sqlBuilder.append(" NULL");
            } else if (sameCatalog) {
                sqlBuilder.append(" ").append(event.getColumn().isNullable() ? "NULL" : "NOT NULL");
            } else if (SqlType.TIMESTAMP.equals(event.getColumn().getDataType().getSqlType())) {
                log.warn(
                        "Default value is not supported for column {} in table {}. Skipping add column operation. event: {}",
                        event.getColumn().getName(),
                        tablePath.getFullName(),
                        event);
            } else {
                sqlBuilder.append(" NOT NULL");
            }
            if (sameCatalog) {
                sqlBuilder
                        .append(" ")
                        .append(sqlClauseWithDefaultValue(typeDefine, sourceDialectName));
            }
        }
        if (event.getColumn().getComment() != null) {
            sqlBuilder
                    .append(" ")
                    .append("COMMENT ")
                    .append("'")
                    .append(event.getColumn().getComment())
                    .append("'");
        }
        if (event.getAfterColumn() != null) {
            sqlBuilder.append(" ").append("AFTER ").append(quoteIdentifier(event.getAfterColumn()));
        }

        String changeColumnSQL = sqlBuilder.toString();
        try (Statement statement = connection.createStatement()) {
            log.info("Executing change column SQL: {}", changeColumnSQL);
            statement.execute(changeColumnSQL);
        }
    }

    default void applySchemaChange(
            Connection connection, TablePath tablePath, AlterTableModifyColumnEvent event)
            throws SQLException {
        String sourceDialectName = event.getSourceDialectName();
        boolean sameCatalog = StringUtils.equals(dialectName(), sourceDialectName);
        BasicTypeDefine typeDefine = getTypeConverter().reconvert(event.getColumn());
        String columnType =
                sameCatalog ? event.getColumn().getSourceType() : typeDefine.getColumnType();
        StringBuilder sqlBuilder =
                new StringBuilder()
                        .append("ALTER TABLE")
                        .append(" ")
                        .append(tableIdentifier(tablePath))
                        .append(" ")
                        .append("MODIFY COLUMN")
                        .append(" ")
                        .append(quoteIdentifier(event.getColumn().getName()))
                        .append(" ")
                        .append(columnType);

        // Only decorate with default value when source dialect is same as sink dialect
        // Todo Support for cross-database default values for ddl statements
        if (event.getColumn().getDefaultValue() == null) {
            sqlBuilder.append(" ").append(event.getColumn().isNullable() ? "NULL" : "NOT NULL");
        } else {
            if (event.getColumn().isNullable()) {
                sqlBuilder.append(" NULL");
            } else if (sameCatalog) {
                sqlBuilder.append(" ").append(event.getColumn().isNullable() ? "NULL" : "NOT NULL");
            } else if (SqlType.TIMESTAMP.equals(event.getColumn().getDataType().getSqlType())) {
                log.warn(
                        "Default value is not supported for column {} in table {}. Skipping add column operation. event: {}",
                        event.getColumn().getName(),
                        tablePath.getFullName(),
                        event);
            } else {
                sqlBuilder.append(" NOT NULL");
            }
            if (sameCatalog) {
                sqlBuilder
                        .append(" ")
                        .append(sqlClauseWithDefaultValue(typeDefine, sourceDialectName));
            }
        }
        if (event.getColumn().getComment() != null) {
            sqlBuilder
                    .append(" ")
                    .append("COMMENT ")
                    .append("'")
                    .append(event.getColumn().getComment())
                    .append("'");
        }
        if (event.getAfterColumn() != null) {
            sqlBuilder.append(" ").append("AFTER ").append(quoteIdentifier(event.getAfterColumn()));
        }

        String modifyColumnSQL = sqlBuilder.toString();
        try (Statement statement = connection.createStatement()) {
            log.info("Executing modify column SQL: {}", modifyColumnSQL);
            statement.execute(modifyColumnSQL);
        }
    }

    default void applySchemaChange(
            Connection connection, TablePath tablePath, AlterTableDropColumnEvent event)
            throws SQLException {
        String dropColumnSQL =
                String.format(
                        "ALTER TABLE %s DROP COLUMN %s",
                        tableIdentifier(tablePath), quoteIdentifier(event.getColumn()));
        try (Statement statement = connection.createStatement()) {
            log.info("Executing drop column SQL: {}", dropColumnSQL);
            statement.execute(dropColumnSQL);
        }
    }

    /**
     * Get the SQL clause for define column default value
     *
     * @param columnDefine column define
     * @param sourceDialectName
     * @return SQL clause for define default value
     */
    default String sqlClauseWithDefaultValue(
            BasicTypeDefine columnDefine, String sourceDialectName) {
        Object defaultValue = columnDefine.getDefaultValue();
        if (Objects.nonNull(defaultValue)
                && needsQuotesWithDefaultValue(columnDefine)
                && !isSpecialDefaultValue(defaultValue, sourceDialectName)) {
            defaultValue = quotesDefaultValue(defaultValue);
        }
        return "DEFAULT " + defaultValue;
    }

    /**
     * Whether support default value
     *
     * @param columnDefine column define
     * @return whether support set default value
     */
    default boolean supportDefaultValue(BasicTypeDefine columnDefine) {
        return true;
    }

    /**
     * whether quotes with default value
     *
     * @param columnDefine column define
     * @return whether needs quotes with the type
     */
    default boolean needsQuotesWithDefaultValue(BasicTypeDefine columnDefine) {
        return false;
    }

    /**
     * whether is special default value e.g. current_timestamp
     *
     * @param defaultValue default value of column
     * @param sourceDialectName source dialect name
     * @return whether is special default value e.g current_timestamp
     */
    default boolean isSpecialDefaultValue(Object defaultValue, String sourceDialectName) {
        if (DatabaseIdentifier.MYSQL.equals(sourceDialectName)) {
            return DefaultValueUtils.isMysqlSpecialDefaultValue(defaultValue);
        }
        return false;
    }

    /**
     * quotes default value
     *
     * @param defaultValue default value of column
     * @return quoted default value
     */
    default String quotesDefaultValue(Object defaultValue) {
        return "'" + defaultValue + "'";
    }
}
