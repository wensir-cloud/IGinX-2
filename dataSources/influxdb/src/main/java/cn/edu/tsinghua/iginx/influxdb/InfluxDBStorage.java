/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package cn.edu.tsinghua.iginx.influxdb;

import cn.edu.tsinghua.iginx.engine.physical.exception.NonExecutablePhysicalTaskException;
import cn.edu.tsinghua.iginx.engine.physical.exception.PhysicalException;
import cn.edu.tsinghua.iginx.engine.physical.exception.PhysicalTaskExecuteFailureException;
import cn.edu.tsinghua.iginx.engine.physical.exception.StorageInitializationException;
import cn.edu.tsinghua.iginx.engine.physical.storage.IStorage;
import cn.edu.tsinghua.iginx.engine.physical.storage.domain.Timeseries;
import cn.edu.tsinghua.iginx.engine.physical.task.StoragePhysicalTask;
import cn.edu.tsinghua.iginx.engine.physical.task.TaskExecuteResult;
import cn.edu.tsinghua.iginx.engine.shared.TimeRange;
import cn.edu.tsinghua.iginx.engine.shared.data.write.BitmapView;
import cn.edu.tsinghua.iginx.engine.shared.data.write.ColumnDataView;
import cn.edu.tsinghua.iginx.engine.shared.data.write.DataView;
import cn.edu.tsinghua.iginx.engine.shared.data.write.RowDataView;
import cn.edu.tsinghua.iginx.engine.shared.operator.Delete;
import cn.edu.tsinghua.iginx.engine.shared.operator.Insert;
import cn.edu.tsinghua.iginx.engine.shared.operator.Operator;
import cn.edu.tsinghua.iginx.engine.shared.operator.OperatorType;
import cn.edu.tsinghua.iginx.engine.shared.operator.Project;
import cn.edu.tsinghua.iginx.influxdb.query.entity.InfluxDBHistoryQueryRowStream;
import cn.edu.tsinghua.iginx.influxdb.query.entity.InfluxDBQueryRowStream;
import cn.edu.tsinghua.iginx.influxdb.query.entity.InfluxDBSchema;
import cn.edu.tsinghua.iginx.influxdb.tools.SchemaTransformer;
import cn.edu.tsinghua.iginx.metadata.entity.FragmentMeta;
import cn.edu.tsinghua.iginx.metadata.entity.StorageEngineMeta;
import cn.edu.tsinghua.iginx.metadata.entity.TimeInterval;
import cn.edu.tsinghua.iginx.metadata.entity.TimeSeriesInterval;
import cn.edu.tsinghua.iginx.utils.Pair;
import cn.edu.tsinghua.iginx.utils.StringUtils;
import com.influxdb.client.InfluxDBClient;
import com.influxdb.client.InfluxDBClientFactory;
import com.influxdb.client.domain.Bucket;
import com.influxdb.client.domain.Organization;
import com.influxdb.client.domain.WritePrecision;
import com.influxdb.client.write.Point;
import com.influxdb.query.FluxRecord;
import com.influxdb.query.FluxTable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import static com.influxdb.client.domain.WritePrecision.MS;

public class InfluxDBStorage implements IStorage {

    private static final Logger logger = LoggerFactory.getLogger(InfluxDBStorage.class);

    private static final String STORAGE_ENGINE = "influxdb";

    private static final WritePrecision WRITE_PRECISION = MS;

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");

    private static final String QUERY_DATA = "from(bucket:\"%s\") |> range(start: %s, stop: %s)";

    private static final String DELETE_DATA = "_measurement=\"%s\" AND _field=\"%s\" AND t=\"%s\"";

    private final StorageEngineMeta meta;

    private final InfluxDBClient client;

    private final String organizationName;

    private final Organization organization;

    private final Map<String, Bucket> bucketMap = new ConcurrentHashMap<>();

    private final Map<String, Bucket> historyBucketMap = new ConcurrentHashMap<>();

    public InfluxDBStorage(StorageEngineMeta meta) throws StorageInitializationException {
        this.meta = meta;
        if (!meta.getStorageEngine().equals(STORAGE_ENGINE)) {
            throw new StorageInitializationException("unexpected database: " + meta.getStorageEngine());
        }
        if (!testConnection()) {
            throw new StorageInitializationException("cannot connect to " + meta.toString());
        }
        Map<String, String> extraParams = meta.getExtraParams();
        String url = extraParams.getOrDefault("url", "http://localhost:8086/");
        client = InfluxDBClientFactory.create(url, extraParams.get("token").toCharArray());
        organizationName = extraParams.get("organization");
        organization = client.getOrganizationsApi()
                .findOrganizations().stream()
                .filter(o -> o.getName().equals(this.organizationName))
                .findFirst()
                .orElseThrow(IllegalStateException::new);
        if (meta.isHasData()) {
            reloadHistoryData();
        }
    }

    private boolean testConnection() {
        Map<String, String> extraParams = meta.getExtraParams();
        String url = extraParams.get("url");
        try {
            InfluxDBClient client = InfluxDBClientFactory.create(url, extraParams.get("token").toCharArray());
            client.close();
        } catch (Exception e) {
            logger.error("test connection error: {}", e.getMessage());
            return false;
        }
        return true;
    }

    private void reloadHistoryData() {
        List<Bucket> buckets = client.getBucketsApi().findBucketsByOrg(organization);
        for (Bucket bucket: buckets) {
            if (bucket.getType() == Bucket.TypeEnum.SYSTEM) {
                continue;
            }
            if (bucket.getName().startsWith("unit")) {
                continue;
            }
            logger.debug("history data bucket info: " + bucket);
            historyBucketMap.put(bucket.getName(), bucket);
        }
    }

    @Override
    public Pair<TimeSeriesInterval, TimeInterval> getBoundaryOfStorage() throws PhysicalException {
        List<String> bucketNames = new ArrayList<>(historyBucketMap.keySet());
        bucketNames.sort(String::compareTo);
        if (bucketNames.size() == 0) {
            throw new PhysicalTaskExecuteFailureException("no data!");
        }
        TimeSeriesInterval tsInterval = new TimeSeriesInterval(bucketNames.get(0), StringUtils.nextString(bucketNames.get(bucketNames.size() - 1)));
        long minTime = Long.MAX_VALUE, maxTime = 0;
        for (Bucket bucket: historyBucketMap.values()) {
            String statement = String.format(
                    QUERY_DATA,
                    bucket.getName(),
                    ZonedDateTime.ofInstant(Instant.ofEpochMilli(0L), ZoneId.of("UTC")).format(FORMATTER),
                    ZonedDateTime.ofInstant(Instant.ofEpochMilli(((long) Integer.MAX_VALUE) * 1000), ZoneId.of("UTC")).format(FORMATTER)
            );
            logger.debug("execute statement: " + statement);
            // 查询 first
            List<FluxTable> tables = client.getQueryApi().query(statement + " |> first()", organization.getId());
            for (FluxTable table: tables) {
                for (FluxRecord record: table.getRecords()) {
                    long time = record.getTime().toEpochMilli();
                    minTime = Math.min(time, minTime);
                    maxTime = Math.max(time, maxTime);
                    logger.debug("record: " + InfluxDBStorage.toString(table, record));
                }
            }
            // 查询 last
            tables = client.getQueryApi().query(statement + " |> last()", organization.getId());
            for (FluxTable table: tables) {
                for (FluxRecord record: table.getRecords()) {
                    long time = record.getTime().toEpochMilli();
                    minTime = Math.min(time, minTime);
                    maxTime = Math.max(time, maxTime);
                    logger.debug("record: " + InfluxDBStorage.toString(table, record));
                }
            }
        }
        if (minTime == Long.MAX_VALUE) {
            minTime = 0;
        }
        if (maxTime == 0) {
            maxTime = Long.MAX_VALUE - 1;
        }
        TimeInterval timeInterval = new TimeInterval(minTime, maxTime + 1);
        return new Pair<>(tsInterval, timeInterval);
    }
    @Override
    public TaskExecuteResult execute(StoragePhysicalTask task) {
        List<Operator> operators = task.getOperators();
        if (operators.size() != 1) {
            return new TaskExecuteResult(new NonExecutablePhysicalTaskException("unsupported physical task"));
        }
        FragmentMeta fragment = task.getTargetFragment();
        Operator op = operators.get(0);
        String storageUnit = task.getStorageUnit();
        boolean isDummyStorageUnit = task.isDummyStorageUnit();
        if (op.getType() == OperatorType.Project) { // 目前只实现 project 操作符
            Project project = (Project) op;
            return isDummyStorageUnit ? executeHistoryProjectTask(fragment.getTimeInterval(), project) : executeProjectTask(fragment.getTimeInterval(), fragment.getTsInterval(), storageUnit, project);
        } else if (op.getType() == OperatorType.Insert) {
            Insert insert = (Insert) op;
            return executeInsertTask(storageUnit, insert);
        } else if (op.getType() == OperatorType.Delete) {
            Delete delete = (Delete) op;
            return executeDeleteTask(storageUnit, delete);
        }
        return new TaskExecuteResult(new NonExecutablePhysicalTaskException("unsupported physical task"));
    }

    private TaskExecuteResult executeHistoryProjectTask(TimeInterval timeInterval, Project project) {
        Map<String, String> bucketQueries = new HashMap<>();
        for (String pattern: project.getPatterns()) {
            Pair<String, String> pair = SchemaTransformer.processPatternForQuery(pattern);
            String bucketName = pair.k;
            String query = pair.v;
            String fullQuery = "";
            if (bucketQueries.containsKey(bucketName)) {
                fullQuery = bucketQueries.get(bucketName);
                fullQuery += " or ";
            }
            fullQuery += query;
            bucketQueries.put(bucketName, fullQuery);
        }

        long startTime = timeInterval.getStartTime();
        long endTime = timeInterval.getEndTime();
        if (endTime == Long.MAX_VALUE) {
            endTime = Integer.MAX_VALUE;
            endTime *= 1000;
        }

        Map<String, List<FluxTable>> bucketQueryResults = new HashMap<>();
        for (String bucket: bucketQueries.keySet()) {
            String statement = String.format("from(bucket:\"%s\") |> range(start: %s, stop: %s)",
                    bucket,
                    ZonedDateTime.ofInstant(Instant.ofEpochMilli(startTime), ZoneId.of("UTC")).format(FORMATTER),
                    ZonedDateTime.ofInstant(Instant.ofEpochMilli(endTime), ZoneId.of("UTC")).format(FORMATTER)
            );
            if (!bucketQueries.get(bucket).equals("()")) {
                statement += String.format(" |> filter(fn: (r) => %s)", bucketQueries.get(bucket));
            }
            logger.info("execute query: " + statement);
            bucketQueryResults.put(bucket, client.getQueryApi().query(statement, organization.getId()));
        }

        InfluxDBHistoryQueryRowStream rowStream = new InfluxDBHistoryQueryRowStream(bucketQueryResults, project.getPatterns());
        return new TaskExecuteResult(rowStream);
    }


    @Override
    public List<Timeseries> getTimeSeries() {
        return null;
    }

    @Override
    public void release() throws PhysicalException {
        client.close();
    }

    private TaskExecuteResult executeProjectTask(TimeInterval timeInterval, TimeSeriesInterval tsInterval, String storageUnit, Project project) {

        if (client.getBucketsApi().findBucketByName(storageUnit) == null) {
            logger.warn("storage engine {} doesn't exist", storageUnit);
            return new TaskExecuteResult(new InfluxDBQueryRowStream(Collections.emptyList()));
        }

        String statement = generateQueryStatement(storageUnit, project.getPatterns(), timeInterval.getStartTime(), timeInterval.getEndTime());
        List<FluxTable> tables = client.getQueryApi().query(statement, organization.getId());
        InfluxDBQueryRowStream rowStream = new InfluxDBQueryRowStream(tables);
        return new TaskExecuteResult(rowStream);
    }

    private static String generateQueryStatement(String bucketName, List<String> paths, long startTime, long endTime) {
        if (endTime == Long.MAX_VALUE) {
            endTime = Integer.MAX_VALUE;
            endTime *= 1000;
        }
        String statement = String.format(
                QUERY_DATA,
                bucketName,
                ZonedDateTime.ofInstant(Instant.ofEpochMilli(startTime), ZoneId.of("UTC")).format(FORMATTER),
                ZonedDateTime.ofInstant(Instant.ofEpochMilli(endTime), ZoneId.of("UTC")).format(FORMATTER)
        );
        if (paths.size() != 1 || !paths.get(0).equals("*")) {
            StringBuilder filterStr = new StringBuilder(" |> filter(fn: (r) => ");
            for (int i = 0; i < paths.size(); i++) {
                String path = paths.get(i);
                InfluxDBSchema schema = new InfluxDBSchema(path);
                if (i != 0) {
                    filterStr.append(" or ");
                }
                filterStr.append('(');

                filterStr.append(schema.getMeasurement().equals("*") ? "r._measurement =~ /.*/" : "r._measurement == \"" + schema.getMeasurement() + "\"");
                filterStr.append(" and ");
                filterStr.append(schema.getField().equals("*") ? "r._field =~ /.*/" : "r._field == \"" + schema.getField() + "\"");

                Map<String, String> tags = schema.getTags();
                if (!tags.isEmpty()) {
                    filterStr.append(" and ");
                    assert tags.size() == 1;
                    String key = InfluxDBSchema.TAG;
                    String value = tags.get(key);
                    if (value.contains("*")) {
                        StringBuilder valueBuilder = new StringBuilder();
                        for (Character character : value.toCharArray()) {
                            if (character.equals('.')) {
                                valueBuilder.append("\\.");
                            } else if (character.equals('*')) {
                                valueBuilder.append(".*");
                            } else {
                                valueBuilder.append(character);
                            }
                        }

                        value = valueBuilder.toString();
                        filterStr.append("r.").append(key).append(" =~ /");
                        filterStr.append(value);
                        filterStr.append("/");
                    } else {
                        filterStr.append("r.").append(key).append(" == \"");
                        filterStr.append(value);
                        filterStr.append("\"");
                    }
                }

                filterStr.append(')');
            }
            filterStr.append(')');
            statement += filterStr;
        }

        logger.info("generate query: " + statement);
        return statement;
    }

    private TaskExecuteResult executeInsertTask(String storageUnit, Insert insert) {
        DataView dataView = insert.getData();
        Exception e = null;
        switch (dataView.getRawDataType()) {
            case Row:
            case NonAlignedRow:
                e = insertRowRecords((RowDataView) dataView, storageUnit);
                break;
            case Column:
            case NonAlignedColumn:
                e = insertColumnRecords((ColumnDataView) dataView, storageUnit);
                break;
        }
        if (e != null) {
            return new TaskExecuteResult(null, new PhysicalException("execute insert task in influxdb failure", e));
        }
        return new TaskExecuteResult(null, null);
    }

    private Exception insertRowRecords(RowDataView data, String storageUnit) {
        Bucket bucket = bucketMap.get(storageUnit);
        if (bucket == null) {
            synchronized (this) {
                bucket = bucketMap.get(storageUnit);
                if (bucket == null) {
                    List<Bucket> bucketList = client.getBucketsApi()
                            .findBucketsByOrgName(this.organizationName).stream()
                            .filter(b -> b.getName().equals(storageUnit))
                            .collect(Collectors.toList());
                    if (bucketList.isEmpty()) {
                        bucket = client.getBucketsApi().createBucket(storageUnit, organization);
                    } else {
                        bucket = bucketList.get(0);
                    }
                    bucketMap.put(storageUnit, bucket);
                }
            }
        }
        if (bucket == null) {
            return new PhysicalTaskExecuteFailureException("create bucket failure!");
        }

        List<InfluxDBSchema> schemas = new ArrayList<>();
        for (int i = 0; i < data.getPathNum(); i++) {
            schemas.add(new InfluxDBSchema(data.getPath(i)));
        }

        List<Point> points = new ArrayList<>();
        for (int i = 0; i < data.getTimeSize(); i++) {
            BitmapView bitmapView = data.getBitmapView(i);
            int index = 0;
            for (int j = 0; j < data.getPathNum(); j++) {
                if (bitmapView.get(j)) {
                    InfluxDBSchema schema = schemas.get(j);
                    switch (data.getDataType(j)) {
                        case BOOLEAN:
                            points.add(Point.measurement(schema.getMeasurement()).addTags(schema.getTags()).addField(schema.getField(), (boolean) data.getValue(i, index)).time(data.getTimestamp(i), WRITE_PRECISION));
                            break;
                        case INTEGER:
                            points.add(Point.measurement(schema.getMeasurement()).addTags(schema.getTags()).addField(schema.getField(), (int) data.getValue(i, index)).time(data.getTimestamp(i), WRITE_PRECISION));
                            break;
                        case LONG:
                            points.add(Point.measurement(schema.getMeasurement()).addTags(schema.getTags()).addField(schema.getField(), (long) data.getValue(i, index)).time(data.getTimestamp(i), WRITE_PRECISION));
                            break;
                        case FLOAT:
                            points.add(Point.measurement(schema.getMeasurement()).addTags(schema.getTags()).addField(schema.getField(), (float) data.getValue(i, index)).time(data.getTimestamp(i), WRITE_PRECISION));
                            break;
                        case DOUBLE:
                            points.add(Point.measurement(schema.getMeasurement()).addTags(schema.getTags()).addField(schema.getField(), (double) data.getValue(i, index)).time(data.getTimestamp(i), WRITE_PRECISION));
                            break;
                        case BINARY:
                            points.add(Point.measurement(schema.getMeasurement()).addTags(schema.getTags()).addField(schema.getField(), new String((byte[]) data.getValue(i, index))).time(data.getTimestamp(i), WRITE_PRECISION));
                            break;
                    }

                    index++;
                }
            }
        }
        try {
            logger.info("开始数据写入");
            client.getWriteApiBlocking().writePoints(bucket.getId(), organization.getId(), points);
        } catch (Exception e) {
            logger.error("encounter error when write points to influxdb: ", e);
        } finally {
            logger.info("数据写入完毕！");
        }
        return null;
    }

    private Exception insertColumnRecords(ColumnDataView data, String storageUnit) {
        Bucket bucket = bucketMap.get(storageUnit);
        if (bucket == null) {
            synchronized (this) {
                bucket = bucketMap.get(storageUnit);
                if (bucket == null) {
                    List<Bucket> bucketList = client.getBucketsApi()
                            .findBucketsByOrgName(this.organizationName).stream()
                            .filter(b -> b.getName().equals(storageUnit))
                            .collect(Collectors.toList());
                    if (bucketList.isEmpty()) {
                        bucket = client.getBucketsApi().createBucket(storageUnit, organization);
                    } else {
                        bucket = bucketList.get(0);
                    }
                    bucketMap.put(storageUnit, bucket);
                }
            }
        }
        if (bucket == null) {
            return new PhysicalTaskExecuteFailureException("create bucket failure!");
        }

        List<Point> points = new ArrayList<>();
        for (int i = 0; i < data.getPathNum(); i++) {
            InfluxDBSchema schema = new InfluxDBSchema(data.getPath(i));
            BitmapView bitmapView = data.getBitmapView(i);
            int index = 0;
            for (int j = 0; j < data.getTimeSize(); j++) {
                if (bitmapView.get(j)) {
                    switch (data.getDataType(i)) {
                        case BOOLEAN:
                            points.add(Point.measurement(schema.getMeasurement()).addTags(schema.getTags()).addField(schema.getField(), (boolean) data.getValue(i, index)).time(data.getTimestamp(j), WRITE_PRECISION));
                            break;
                        case INTEGER:
                            points.add(Point.measurement(schema.getMeasurement()).addTags(schema.getTags()).addField(schema.getField(), (int) data.getValue(i, index)).time(data.getTimestamp(j), WRITE_PRECISION));
                            break;
                        case LONG:
                            points.add(Point.measurement(schema.getMeasurement()).addTags(schema.getTags()).addField(schema.getField(), (long) data.getValue(i, index)).time(data.getTimestamp(j), WRITE_PRECISION));
                            break;
                        case FLOAT:
                            points.add(Point.measurement(schema.getMeasurement()).addTags(schema.getTags()).addField(schema.getField(), (float) data.getValue(i, index)).time(data.getTimestamp(j), WRITE_PRECISION));
                            break;
                        case DOUBLE:
                            points.add(Point.measurement(schema.getMeasurement()).addTags(schema.getTags()).addField(schema.getField(), (double) data.getValue(i, index)).time(data.getTimestamp(j), WRITE_PRECISION));
                            break;
                        case BINARY:
                            points.add(Point.measurement(schema.getMeasurement()).addTags(schema.getTags()).addField(schema.getField(), new String((byte[]) data.getValue(i, index))).time(data.getTimestamp(j), WRITE_PRECISION));
                            break;
                    }
                    index++;
                }
            }
        }

        try {
            logger.info("开始数据写入");
            client.getWriteApiBlocking().writePoints(bucket.getId(), organization.getId(), points);
        } catch (Exception e) {
            logger.error("encounter error when write points to influxdb: ", e);
        } finally {
            logger.info("数据写入完毕！");
        }

        return null;
    }

    private TaskExecuteResult executeDeleteTask(String storageUnit, Delete delete) {
        if (delete.getTimeRanges() == null || delete.getTimeRanges().size() == 0) { // 没有传任何 time range
            Bucket bucket = bucketMap.get(storageUnit);
            if (bucket == null) {
                return new TaskExecuteResult(null, null);
            }
            bucketMap.remove(storageUnit);
            client.getBucketsApi().deleteBucket(bucket);
            return new TaskExecuteResult(null, null);
        }
        // 删除某些序列的某一段数据
        Bucket bucket = bucketMap.get(storageUnit);
        if (bucket == null) {
            synchronized (this) {
                bucket = bucketMap.get(storageUnit);
                if (bucket == null) {
                    List<Bucket> bucketList = client.getBucketsApi()
                            .findBucketsByOrgName(this.organizationName).stream()
                            .filter(b -> b.getName().equals(storageUnit))
                            .collect(Collectors.toList());
                    if (bucketList.isEmpty()) {
                        bucket = client.getBucketsApi().createBucket(storageUnit, organization);
                    } else {
                        bucket = bucketList.get(0);
                    }
                    bucketMap.put(storageUnit, bucket);
                }
            }
        }
        if (bucket == null) { // 没有数据，当然也不用删除
            return new TaskExecuteResult(null, null);
        }

        List<InfluxDBSchema> schemas = delete.getPatterns().stream().map(InfluxDBSchema::new).collect(Collectors.toList());
        for (InfluxDBSchema schema: schemas) {
            for (TimeRange timeRange: delete.getTimeRanges()) {
                client.getDeleteApi().delete(
                        OffsetDateTime.ofInstant(Instant.ofEpochMilli(timeRange.getActualBeginTime()), ZoneId.of("UTC")),
                        OffsetDateTime.ofInstant(Instant.ofEpochMilli(timeRange.getActualEndTime()), ZoneId.of("UTC")),
                        String.format(DELETE_DATA, schema.getMeasurement(), schema.getField(), schema.getTags().get(InfluxDBSchema.TAG)),
                        bucket,
                        organization
                );

            }
        }
        return new TaskExecuteResult(null, null);
    }

    public static String toString(FluxTable table, FluxRecord record) {
        StringBuilder str = new StringBuilder("measurement: " + record.getMeasurement() + ", field: " + record.getField() + ", value: " + record.getValue() + ", time: " + record.getTime().toEpochMilli());
        for (int i = 8; i < table.getColumns().size(); i++) {
            str.append(", ").append(table.getColumns().get(i).getLabel()).append(" = ").append(record.getValueByIndex(i));
        }
        return str.toString();
    }

}