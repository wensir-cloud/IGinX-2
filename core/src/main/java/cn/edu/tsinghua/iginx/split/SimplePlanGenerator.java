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
package cn.edu.tsinghua.iginx.split;

import cn.edu.tsinghua.iginx.core.context.*;
import cn.edu.tsinghua.iginx.metadata.MetaManager;
import cn.edu.tsinghua.iginx.plan.*;
import cn.edu.tsinghua.iginx.thrift.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;

import static cn.edu.tsinghua.iginx.utils.ByteUtils.getValuesByDataType;

public class SimplePlanGenerator implements IPlanGenerator {

    private static final Logger logger = LoggerFactory.getLogger(SimplePlanGenerator.class);

    private final SimplePlanSplitter planSplitter = new SimplePlanSplitter();

    @Override
    public List<? extends IginxPlan> generateSubPlans(RequestContext requestContext) {
        List<SplitInfo> splitInfoList;
        switch (requestContext.getType()) {
            case CreateDatabase:
                CreateDatabaseReq createDatabaseReq = ((CreateDatabaseContext) requestContext).getReq();
                CreateDatabasePlan createDatabasePlan = new CreateDatabasePlan(createDatabaseReq.getDatabaseName());
                createDatabasePlan.setDatabaseId(MetaManager.getInstance().chooseDatabaseIdForDatabasePlan());
                return Collections.singletonList(createDatabasePlan);
            case DropDatabase:
                DropDatabaseReq dropDatabaseReq = ((DropDatabaseContext) requestContext).getReq();
                DropDatabasePlan dropDatabasePlan = new DropDatabasePlan(dropDatabaseReq.getDatabaseName());
                dropDatabasePlan.setDatabaseId(MetaManager.getInstance().chooseDatabaseIdForDatabasePlan());
                return Collections.singletonList(dropDatabasePlan);
            case AddColumns:
                AddColumnsReq addColumnsReq = ((AddColumnsContext) requestContext).getReq();
                AddColumnsPlan addColumnsPlan = new AddColumnsPlan(
                    addColumnsReq.getPaths(),
                    addColumnsReq.getAttributes()
                );
                splitInfoList = planSplitter.getSplitResults(addColumnsPlan);
                return planSplitter.splitAddColumnsPlan(addColumnsPlan, splitInfoList);
            case DeleteColumns:
                DeleteColumnsReq deleteColumnsReq = ((DeleteColumnsContext) requestContext).getReq();
                DeleteColumnsPlan deleteColumnsPlan = new DeleteColumnsPlan(deleteColumnsReq.getPaths());
                return Collections.singletonList(deleteColumnsPlan);
            case InsertRecords:
                InsertRecordsReq insertRecordsReq = ((InsertRecordsContext) requestContext).getReq();
                InsertRecordsPlan insertRecordsPlan = new InsertRecordsPlan(
                        insertRecordsReq.getPaths(),
                        insertRecordsReq.getTimestamps().stream().mapToLong(Long::longValue).toArray(),
                        getValuesByDataType(insertRecordsReq.getValues(), insertRecordsReq.getAttributes()),
                        insertRecordsReq.getAttributes()
                );
                splitInfoList = planSplitter.getSplitResults(insertRecordsPlan);
                return planSplitter.splitInsertRecordsPlan(insertRecordsPlan, splitInfoList);
            case DeleteDataInColumns:
                // TODO
                break;
            case QueryData:
                QueryDataReq queryDataReq = ((QueryDataContext) requestContext).getReq();
                QueryDataPlan queryDataPlan = new QueryDataPlan(
                        queryDataReq.getPaths(),
                        queryDataReq.getStartTime(),
                        queryDataReq.getEndTime()
                );
                splitInfoList = planSplitter.getSplitResults(queryDataPlan);
                return planSplitter.splitQueryDataPlan(queryDataPlan, splitInfoList);
            default:
                logger.info("unimplemented method: " + requestContext.getType());
        }
        return null;
    }
}
