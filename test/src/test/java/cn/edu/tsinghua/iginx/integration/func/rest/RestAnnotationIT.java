package cn.edu.tsinghua.iginx.integration.func.rest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import cn.edu.tsinghua.iginx.exceptions.SessionException;
import cn.edu.tsinghua.iginx.integration.controller.Controller;
import cn.edu.tsinghua.iginx.session.Session;
import cn.edu.tsinghua.iginx.thrift.DataType;
import com.alibaba.fastjson.JSONObject;
import java.io.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/*
一、annotation测试逻辑：
1、正确操作测试，验证单一操作正确性
2、错误操作测试，验证错误操作，或者无效操作结果
3、重复性操作测试，测试可重复操作的结果是否正确
4、操作对象重复，测试操作逻辑中，可重复添加的元素是否符合逻辑
5、复杂操作，测试多种操作组合结果是否正确

二、annotation测试条目：
1、查询annotation信息
2、查询数据以及annotation信息
3、对每个修改操作单独测试，并通过两种查询分别验证正确性：
    3.1、测试 add（增加标签操作），通过queryAnno以及queryAll两种方法测试
    3.2、测试 update（更新标签操作），通过queryAnno以及queryAll两种方法测试
    3.3、测试 delete（删除标签操作），通过queryAnno以及queryAll两种方法测试
4、测试重复性操作操作，查看结果正确性
    4.1、测试添加相同category，通过queryAnno以及queryAll两种方法测试
    4.2、测试不断更新相同结果的category，通过queryAnno以及queryAll两种方法测试
    4.3、测试不断删除的category，通过queryAnno以及queryAll两种方法测试
5、逻辑上重复的操作，如更新结果与原category相同，查看结果正确性
6、复杂操作，插入，添加，更新，删除，每步操作查看结果正确性

 */
public class RestAnnotationIT {
    private static final Logger logger = LoggerFactory.getLogger(RestAnnotationIT.class);

    private static Session session;

    public enum TYPE {
        APPEND,
        UPDATE,
        INSERT,
        QUERYANNO,
        QUERYALL,
        DELETE
    }

    private static final String[] API = {
        " http://127.0.0.1:6666/api/v1/datapoints/annotations/add",
        " http://127.0.0.1:6666/api/v1/datapoints/annotations/update",
        " http://127.0.0.1:6666/api/v1/datapoints/annotations",
        " http://127.0.0.1:6666/api/v1/datapoints/query/annotations",
        " http://127.0.0.1:6666/api/v1/datapoints/query/annotations/data",
        " http://127.0.0.1:6666/api/v1/datapoints/annotations/delete",
    };

    private static final String PREFIX = "curl -XPOST -H\"Content-Type: application/json\" -d @";

    public String orderGen(String fileName, TYPE type) {
        return PREFIX + fileName + API[type.ordinal()];
    }

    public String execute(String fileName, TYPE type, DataType dataType) throws Exception {
        StringBuilder ret = new StringBuilder();
        String curlArray = orderGen(fileName, type);
        Process process = null;
        try {
            ProcessBuilder processBuilder = new ProcessBuilder(curlArray.split(" "));
            if (dataType.equals(DataType.DOUBLE)) {
                processBuilder.directory(
                        new File("./src/test/resources/restAnnotation/doubleType"));
            } else if (dataType.equals(DataType.LONG)) {
                processBuilder.directory(new File("./src/test/resources/restAnnotation/longType"));
            } else if (dataType.equals(DataType.BINARY)) {
                processBuilder.directory(
                        new File("./src/test/resources/restAnnotation/binaryType"));
            }

            // 执行 url 命令
            process = processBuilder.start();

            // 输出子进程信息
            InputStreamReader inputStreamReaderINFO =
                    new InputStreamReader(process.getInputStream());
            BufferedReader bufferedReaderINFO = new BufferedReader(inputStreamReaderINFO);
            String lineStr;
            while ((lineStr = bufferedReaderINFO.readLine()) != null) {
                ret.append(lineStr);
            }
            // 等待子进程结束
            process.waitFor();

            return ret.toString();
        } catch (InterruptedException e) {
            // 强制关闭子进程（如果打开程序，需要额外关闭）
            process.destroyForcibly();
            return null;
        }
    }

    @BeforeClass
    public static void setUp() throws SessionException {
        session = new Session("127.0.0.1", 6888, "root", "root");
        session.openSession();
    }

    @AfterClass
    public static void tearDown() throws SessionException {
        session.closeSession();
    }

    //    @Before
    public void insertData(DataType dataType) {
        try {
            execute("insert.json", TYPE.INSERT, dataType);
        } catch (Exception e) {
            logger.error("Error occurred during execution ", e);
            fail();
        }
    }

    @After
    public void clearData() {
        Controller.clearData(session);
    }

    public void executeAndCompare(String json, String output, TYPE type, DataType dataType) {
        try {
            ByteArrayOutputStream baoStream = new ByteArrayOutputStream(10240);
            PrintStream cacheStream = new PrintStream(baoStream);
            System.setOut(cacheStream); // 不打印到控制台

            System.out.print(JSONObject.parse(output));
            String outputAns = baoStream.toString();

            ByteArrayOutputStream baoStream2 = new ByteArrayOutputStream(10240);
            PrintStream cacheStream2 = new PrintStream(baoStream2);
            System.setOut(cacheStream2); // 不打印到控制台

            System.out.print(JSONObject.parse(execute(json, type, dataType)));
            String result = baoStream.toString();

            //            String result = (String) JSONObject.parse(execute(json, type, dataType));
            assertEquals(outputAns, removeSpecialChar(result));
        } catch (Exception e) {
            logger.error("Error occurred during execution ", e);
            fail();
        }
    }

    public String ansFromFile(String fileName, DataType dataType) {
        StringBuilder ret = new StringBuilder();
        switch (dataType) {
            case DOUBLE:
                fileName = "./src/test/resources/restAnnotation/doubleType/ans/" + fileName;
                break;
            case LONG:
                fileName = "./src/test/resources/restAnnotation/longType/ans/" + fileName;
                break;
            case BINARY:
                fileName = "./src/test/resources/restAnnotation/binaryType/ans/" + fileName;
                break;
            default:
                throw new IllegalStateException("Unexpected DataType: " + dataType);
        }
        fileName += ".json";

        File file = new File(fileName);
        try {
            FileReader fr = new FileReader(file);
            try (BufferedReader br = new BufferedReader(fr)) {
                String line;
                while ((line = br.readLine()) != null) {
                    ret.append(line);
                }
            }
        } catch (Exception e) {
            logger.error("Error occurred during execution ", e);
            fail();
        }
        return removeSpecialChar(ret.toString());
    }

    /**
     * 去除字符串中的空格、回车、换行符、制表符等
     *
     * @param str
     * @return
     */
    public String removeSpecialChar(String str) {
        String s = "";
        if (str != null) {
            // 定义含特殊字符的正则表达式
            Pattern p = Pattern.compile("\\s*|\t|\r|\n");
            Matcher m = p.matcher(str);
            s = m.replaceAll("");
        }
        return s;
    }

    private String getMethodName() {
        StackTraceElement[] stacktrace = Thread.currentThread().getStackTrace();
        StackTraceElement e = stacktrace[2];
        return e.getMethodName();
    }

    public String getAns(String fileName, DataType dataType) {
        String ans;
        switch (dataType) {
            case DOUBLE:
                ans = ansFromFile(fileName, DataType.DOUBLE);
                break;
            case LONG:
                ans = ansFromFile(fileName, DataType.LONG);
                break;
            case BINARY:
                ans = ansFromFile(fileName, DataType.BINARY);
                break;
            default:
                throw new IllegalStateException("Unexpected DataType: " + dataType.toString());
        }
        return ans;
    }

    public void clearDataMen() {
        try {
            Controller.clearData(session);
        } catch (Exception e) {
            logger.error("Error occurred during execution ", e);
            fail();
        }
    }

    @Test
    public void testDoubleType() {
        DataType dataType = DataType.DOUBLE;

        /*
        1、查询annotation信息
        */
        testQueryAnno(dataType);

        /*
        2、查询数据以及annotation信息
        */
        testQueryAll(dataType);

        /*
        3、对每个修改操作单独测试，并通过两种查询分别验证正确性：
        3.1、测试 add（增加标签操作），通过queryAnno以及queryAll两种方法测试
        3.2、测试 update（更新标签操作），通过queryAnno以及queryAll两种方法测试
        3.3、测试 delete（删除标签操作），通过queryAnno以及queryAll两种方法测试
        */
        testAppendViaQueryAnno(dataType);
        testAppendViaQueryAll(dataType);
        testUpdateViaQueryAll(dataType);
        testUpdateViaQueryAnno(dataType);
        testDeleteViaQueryAll(dataType);
        testDeleteViaQueryAnno(dataType);

        /*
        4、测试重复性操作操作，查看结果正确性
        4.1、测试添加相同category，通过queryAnno以及queryAll两种方法测试
        4.2、测试不断更新相同结果的category，通过queryAnno以及queryAll两种方法测试
        */
        testDuplicateAppendViaQueryAnno(dataType);
        testDuplicateAppendViaQueryAll(dataType);
        testDuplicateUpdateViaQueryAnno(dataType);
        testDuplicateDeleteViaQueryAll(dataType);

        /*
        5、逻辑上重复的操作，如更新结果与原category相同，查看结果正确性
        */
        testSameUpdateViaQueryAll(dataType);
        testSameAppendViaQueryAll(dataType);

        /*
        6、复杂操作，插入，添加，更新，删除，每步操作查看结果正确性
        */
        testAppend2ViaQueryAll(dataType);
    }

    @Test
    public void testLongType() {
        DataType dataType = DataType.LONG;

        /*
        1、查询annotation信息
        */
        testQueryAnno(dataType);

        /*
        2、查询数据以及annotation信息
        */
        testQueryAll(dataType);

        /*
        3、对每个修改操作单独测试，并通过两种查询分别验证正确性：
        3.1、测试 add（增加标签操作），通过queryAnno以及queryAll两种方法测试
        3.2、测试 update（更新标签操作），通过queryAnno以及queryAll两种方法测试
        3.3、测试 delete（删除标签操作），通过queryAnno以及queryAll两种方法测试
        */
        testAppendViaQueryAnno(dataType);
        testAppendViaQueryAll(dataType);
        testUpdateViaQueryAll(dataType);
        testUpdateViaQueryAnno(dataType);
        testDeleteViaQueryAll(dataType);
        testDeleteViaQueryAnno(dataType);

        /*
        4、测试重复性操作操作，查看结果正确性
        4.1、测试添加相同category，通过queryAnno以及queryAll两种方法测试
        4.2、测试不断更新相同结果的category，通过queryAnno以及queryAll两种方法测试
        */
        testDuplicateAppendViaQueryAnno(dataType);
        testDuplicateAppendViaQueryAll(dataType);
        testDuplicateUpdateViaQueryAnno(dataType);
        testDuplicateDeleteViaQueryAll(dataType);

        /*
        5、逻辑上重复的操作，如更新结果与原category相同，查看结果正确性
        */
        testSameUpdateViaQueryAll(dataType);
        testSameAppendViaQueryAll(dataType);

        /*
        6、复杂操作，插入，添加，更新，删除，每步操作查看结果正确性
        */
        testAppend2ViaQueryAll(dataType);
    }

    @Test
    public void testBinaryType() {
        DataType dataType = DataType.BINARY;

        /*
        1、查询annotation信息
        */
        testQueryAnno(dataType);

        /*
        2、查询数据以及annotation信息
        */
        testQueryAll(dataType);

        /*
        3、对每个修改操作单独测试，并通过两种查询分别验证正确性：
        3.1、测试 add（增加标签操作），通过queryAnno以及queryAll两种方法测试
        3.2、测试 update（更新标签操作），通过queryAnno以及queryAll两种方法测试
        3.3、测试 delete（删除标签操作），通过queryAnno以及queryAll两种方法测试
        */
        testAppendViaQueryAnno(dataType);
        testAppendViaQueryAll(dataType);
        testUpdateViaQueryAll(dataType);
        testUpdateViaQueryAnno(dataType);
        testDeleteViaQueryAll(dataType);
        testDeleteViaQueryAnno(dataType);

        /*
        4、测试重复性操作操作，查看结果正确性
        4.1、测试添加相同category，通过queryAnno以及queryAll两种方法测试
        4.2、测试不断更新相同结果的category，通过queryAnno以及queryAll两种方法测试
        */
        testDuplicateAppendViaQueryAnno(dataType);
        testDuplicateAppendViaQueryAll(dataType);
        testDuplicateUpdateViaQueryAnno(dataType);
        testDuplicateDeleteViaQueryAll(dataType);

        /*
        5、逻辑上重复的操作，如更新结果与原category相同，查看结果正确性
        */
        testSameUpdateViaQueryAll(dataType);
        testSameAppendViaQueryAll(dataType);

        /*
        6、复杂操作，插入，添加，更新，删除，每步操作查看结果正确性
        */
        testAppend2ViaQueryAll(dataType);
    }

    @Test
    public void testAllAppend() {
        for (int i = 0; i < 3; i++) {
            DataType dataType = null;
            if (i == 0) dataType = DataType.DOUBLE;
            if (i == 1) dataType = DataType.LONG;
            if (i == 2) dataType = DataType.BINARY;

            testAppendViaQueryAnno(dataType);
            testAppendViaQueryAll(dataType);

            testDuplicateAppendViaQueryAnno(dataType);
            testDuplicateAppendViaQueryAll(dataType);

            testAppend2ViaQueryAll(dataType);
        }
    }

    @Test
    public void testAllDelete() {
        for (int i = 0; i < 3; i++) {
            DataType dataType = null;
            if (i == 0) dataType = DataType.DOUBLE;
            if (i == 1) dataType = DataType.LONG;
            if (i == 2) dataType = DataType.BINARY;

            testDeleteViaQueryAll(dataType);
            testDeleteViaQueryAnno(dataType);

            testDuplicateDeleteViaQueryAll(dataType);
        }
    }

    @Test
    public void testAllUpdate() {
        for (int i = 0; i < 3; i++) {
            DataType dataType = null;
            if (i == 0) dataType = DataType.DOUBLE;
            if (i == 1) dataType = DataType.LONG;
            if (i == 2) dataType = DataType.BINARY;

            testUpdateViaQueryAll(dataType);
            testUpdateViaQueryAnno(dataType);

            testDuplicateUpdateViaQueryAnno(dataType);

            testSameUpdateViaQueryAll(dataType);
        }
    }

    public void testQueryAnno(DataType dataType) {
        insertData(dataType);
        String ans = getAns(getMethodName(), dataType);
        executeAndCompare("queryAnno.json", ans, TYPE.QUERYANNO, dataType);
        clearDataMen();
    }

    public void testQueryAll(DataType dataType) {
        insertData(dataType);
        String ans = getAns(getMethodName(), dataType);
        executeAndCompare("queryData.json", ans, TYPE.QUERYALL, DataType.DOUBLE);
        clearDataMen();
    }

    public void testAppendViaQueryAnno(DataType dataType) {
        insertData(dataType);
        try {
            execute("add.json", TYPE.APPEND, DataType.DOUBLE);
            String ans = getAns(getMethodName(), dataType);
            executeAndCompare("queryAppendViaQueryAnno.json", ans, TYPE.QUERYANNO, DataType.DOUBLE);
            clearDataMen();
        } catch (Exception e) {
            logger.error("Error occurred during execution ", e);
            fail();
        }
    }

    public void testAppendViaQueryAll(DataType dataType) {
        insertData(dataType);
        try {
            execute("add.json", TYPE.APPEND, dataType);
            String ans = getAns(getMethodName(), dataType);
            executeAndCompare("queryAppendViaQueryAll.json", ans, TYPE.QUERYALL, dataType);
            clearDataMen();
        } catch (Exception e) {
            logger.error("Error occurred during execution ", e);
            fail();
        }
    }

    public void testUpdateViaQueryAll(DataType dataType) {
        insertData(dataType);
        try {
            execute("update.json", TYPE.UPDATE, dataType);
            String ans = getAns(getMethodName(), dataType);
            executeAndCompare("queryUpdateViaQueryAll.json", ans, TYPE.QUERYALL, dataType);
            clearDataMen();
        } catch (Exception e) {
            logger.error("Error occurred during execution ", e);
            fail();
        }
    }

    public void testUpdateViaQueryAnno(DataType dataType) {
        insertData(dataType);
        try {
            execute("update.json", TYPE.UPDATE, dataType);
            String ans = getAns(getMethodName(), dataType);
            executeAndCompare("queryUpdateViaQueryAnno.json", ans, TYPE.QUERYANNO, dataType);
            clearDataMen();
        } catch (Exception e) {
            logger.error("Error occurred during execution ", e);
            fail();
        }
    }

    public void testDeleteViaQueryAll(DataType dataType) {
        insertData(dataType);
        try {
            execute("delete.json", TYPE.DELETE, dataType);
            String ans = getAns(getMethodName(), dataType);
            executeAndCompare("deleteViaQueryAll.json", ans, TYPE.QUERYALL, dataType);
            clearDataMen();
        } catch (Exception e) {
            logger.error("Error occurred during execution ", e);
            fail();
        }
    }

    public void testDeleteViaQueryAnno(DataType dataType) {
        insertData(dataType);
        try {
            execute("delete.json", TYPE.DELETE, dataType);
            String ans = getAns(getMethodName(), dataType);
            executeAndCompare("deleteViaQueryAnno.json", ans, TYPE.QUERYANNO, dataType);
            clearDataMen();
        } catch (Exception e) {
            logger.error("Error occurred during execution ", e);
            fail();
        }
    }

    /*
    4、测试重复性操作操作，查看结果正确性
    4.1、测试添加相同category，通过queryAnno以及queryAll两种方法测试
    4.2、测试不断更新相同结果的category，通过queryAnno以及queryAll两种方法测试
     */

    public void testDuplicateAppendViaQueryAnno(DataType dataType) {
        try {
            execute("insert2.json", TYPE.INSERT, dataType);
            execute("add2.json", TYPE.APPEND, dataType);
            execute("add2.json", TYPE.APPEND, dataType);

            String ans = getAns(getMethodName(), dataType);
            executeAndCompare("testAppend2ViaQueryAll.json", ans, TYPE.QUERYALL, dataType);
            clearDataMen();
        } catch (Exception e) {
            logger.error("Error occurred during execution ", e);
            fail();
        }
    }

    public void testDuplicateAppendViaQueryAll(DataType dataType) {
        insertData(dataType);
        try {
            execute("add.json", TYPE.APPEND, dataType);
            execute("add.json", TYPE.APPEND, dataType);

            String ans = getAns(getMethodName(), dataType);
            executeAndCompare("queryAppendViaQueryAll.json", ans, TYPE.QUERYALL, dataType);
            clearDataMen();
        } catch (Exception e) {
            logger.error("Error occurred during execution ", e);
            fail();
        }
    }

    public void testDuplicateUpdateViaQueryAll(DataType dataType) {
        insertData(dataType);
        try {
            execute("update.json", TYPE.UPDATE, dataType);
            execute("update.json", TYPE.UPDATE, dataType);

            String ans = getAns(getMethodName(), dataType);
            executeAndCompare("queryUpdateViaQueryAll.json", ans, TYPE.QUERYALL, dataType);
            clearDataMen();
        } catch (Exception e) {
            logger.error("Error occurred during execution ", e);
            fail();
        }
    }

    public void testDuplicateUpdateViaQueryAnno(DataType dataType) {
        insertData(dataType);
        try {
            execute("update.json", TYPE.UPDATE, dataType);
            execute("update.json", TYPE.UPDATE, dataType);

            String ans = getAns(getMethodName(), dataType);
            executeAndCompare("queryUpdateViaQueryAnno.json", ans, TYPE.QUERYANNO, dataType);
            clearDataMen();
        } catch (Exception e) {
            logger.error("Error occurred during execution ", e);
            fail();
        }
    }

    public void testDuplicateDeleteViaQueryAll(DataType dataType) {
        insertData(dataType);
        try {
            execute("delete.json", TYPE.DELETE, dataType);
            execute("delete.json", TYPE.DELETE, dataType);

            String ans = getAns(getMethodName(), dataType);
            executeAndCompare("deleteViaQueryAll.json", ans, TYPE.QUERYALL, dataType);
            clearDataMen();
        } catch (Exception e) {
            logger.error("Error occurred during execution ", e);
            fail();
        }
    }

    /*
    5、逻辑上重复的操作，如更新结果与原category相同，查看结果正确性
     */

    public void testSameUpdateViaQueryAll(DataType dataType) {
        insertData(dataType);
        try {
            execute("updateSame.json", TYPE.UPDATE, dataType);
            String ans = getAns(getMethodName(), dataType);
            executeAndCompare("queryData.json", ans, TYPE.QUERYALL, dataType);
            clearDataMen();
        } catch (Exception e) {
            logger.error("Error occurred during execution ", e);
            fail();
        }
    }

    public void testSameAppendViaQueryAll(DataType dataType) {
        insertData(dataType);
        try {
            execute("addSame.json", TYPE.APPEND, dataType);
            String ans = getAns(getMethodName(), dataType);
            executeAndCompare("queryAppendViaQueryAll.json", ans, TYPE.QUERYALL, dataType);
            clearDataMen();
        } catch (Exception e) {
            logger.error("Error occurred during execution ", e);
            fail();
        }
    }

    /*
    6、复杂操作，插入，添加，更新，删除，每步操作查看结果正确性
     */

    public void testAppend2ViaQueryAll(DataType dataType) {
        try {
            execute("insert2.json", TYPE.INSERT, dataType);
            execute("add2.json", TYPE.APPEND, dataType);

            execute("delete.json", TYPE.DELETE, dataType);
            String ans = getAns(getMethodName(), dataType);
            executeAndCompare("testAppend2ViaQueryAll.json", ans, TYPE.QUERYALL, dataType);
            clearDataMen();
        } catch (Exception e) {
            logger.error("Error occurred during execution ", e);
            fail();
        }
    }
}
