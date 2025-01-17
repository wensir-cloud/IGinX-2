package cn.edu.tsinghua.iginx.policy;

import cn.edu.tsinghua.iginx.conf.Constants;
import cn.edu.tsinghua.iginx.engine.shared.KeyRange;
import cn.edu.tsinghua.iginx.metadata.entity.KeyInterval;
import cn.edu.tsinghua.iginx.sql.statement.DataStatement;
import cn.edu.tsinghua.iginx.sql.statement.DeleteStatement;
import cn.edu.tsinghua.iginx.sql.statement.InsertStatement;
import cn.edu.tsinghua.iginx.sql.statement.SelectStatement;
import cn.edu.tsinghua.iginx.sql.statement.StatementType;
import java.util.*;

public class Utils {

    public static List<String> getPathListFromStatement(DataStatement statement) {
        switch (statement.getType()) {
            case SELECT:
                return new ArrayList<>(((SelectStatement) statement).getPathSet());
            case DELETE:
                return ((DeleteStatement) statement).getPaths();
            case INSERT:
                return ((InsertStatement) statement).getPaths();
            default:
                // TODO: case label. should we return empty list for other statements?
                break;
        }
        return Collections.emptyList();
    }

    public static List<String> getNonWildCardPaths(List<String> paths) {
        Set<String> beCutPaths = new TreeSet<>();
        for (String path : paths) {
            if (!path.contains(Constants.LEVEL_PLACEHOLDER)) {
                beCutPaths.add(path);
                continue;
            }
            String[] parts = path.split("\\" + Constants.LEVEL_SEPARATOR);
            if (parts.length == 0 || parts[0].equals(Constants.LEVEL_PLACEHOLDER)) {
                continue;
            }
            StringBuilder pathBuilder = new StringBuilder();
            for (String part : parts) {
                if (part.equals(Constants.LEVEL_PLACEHOLDER)) {
                    break;
                }
                if (pathBuilder.length() != 0) {
                    pathBuilder.append(Constants.LEVEL_SEPARATOR);
                }
                pathBuilder.append(part);
            }
            beCutPaths.add(pathBuilder.toString());
        }
        return new ArrayList<>(beCutPaths);
    }

    public static KeyInterval getTimeIntervalFromDataStatement(DataStatement statement) {
        StatementType type = statement.getType();
        switch (type) {
            case INSERT:
                InsertStatement insertStatement = (InsertStatement) statement;
                List<Long> times = insertStatement.getKeys();
                return new KeyInterval(times.get(0), times.get(times.size() - 1));
            case SELECT:
                SelectStatement selectStatement = (SelectStatement) statement;
                return new KeyInterval(
                        selectStatement.getStartTime(), selectStatement.getEndTime());
            case DELETE:
                DeleteStatement deleteStatement = (DeleteStatement) statement;
                List<KeyRange> keyRanges = deleteStatement.getKeyRanges();
                long startTime = Long.MAX_VALUE, endTime = Long.MIN_VALUE;
                for (KeyRange keyRange : keyRanges) {
                    if (keyRange.getBeginKey() < startTime) {
                        startTime = keyRange.getBeginKey();
                    }
                    if (keyRange.getEndKey() > endTime) {
                        endTime = keyRange.getEndKey();
                    }
                }
                startTime = startTime == Long.MAX_VALUE ? 0 : startTime;
                endTime = endTime == Long.MIN_VALUE ? Long.MAX_VALUE : endTime;
                return new KeyInterval(startTime, endTime);
            default:
                return new KeyInterval(0, Long.MAX_VALUE);
        }
    }
}
