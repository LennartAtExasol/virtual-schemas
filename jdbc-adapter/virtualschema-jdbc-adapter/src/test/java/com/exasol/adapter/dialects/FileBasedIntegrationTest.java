package com.exasol.adapter.dialects;

import static org.junit.Assert.assertTrue;


import java.io.File;
import java.util.ArrayList;
import java.util.List;

import javax.json.JsonObject;
import javax.json.JsonValue;

import com.exasol.adapter.AdapterException;
import com.exasol.adapter.dialects.impl.ExasolSqlDialect;
import com.exasol.adapter.json.RequestJsonParser;
import com.exasol.adapter.jdbc.SchemaAdapterNotes;
import com.exasol.adapter.request.AdapterRequest;
import com.exasol.adapter.request.PushdownRequest;
import com.exasol.utils.JsonHelper;
import com.google.common.base.Charsets;
import com.google.common.io.Files;

import org.junit.Test;
import org.mockito.Mockito;


/**
 * This is an integration test for virtual schemas. The idea is that 
 * the jdbc-adapter and the EXASOL database have a common set of testdata 
 * to use. By doing this we avoid to write and keep tests in multiple 
 * locations.
 * <p>
 * This class is a testrunner that executes the given (in json file) test 
 * scenarios and asserts the results.
 * <p>
 * Writing a new test means writing new testdata files. The testfiles are 
 * in json format and have to have the extension .json. The following 
 * attributes have to be present:
 * <ul><li>testSchema: 
 * This is the schema definition. This test does not use the schema 
 * definition, since the testcase parses the pushdown request directly.
 * <li> testCases: 
 * A list of testcases to be performed on the schema, each of which contains:
 *   <ul><li> testQuery: 
 *     A single string containing the test query. This test does not use 
 *     the test query, since the testcase parses the pushdown request directly.
 *   <li> expectedPushdownRequest: 
 *     A list of pushdownRequests as they are generated by the database. 
 *     This is a list because a single query can generate multiple pushdowns 
 *     (e.g. join).
 *   <li> expectedPushdownResponse: 
 *     For each dialect that should be tested a list of strings with the 
 *     returned Pushdown SQLs.
 *
 */
public class FileBasedIntegrationTest {
    private static final String INTEGRATION_TESTFILES_DIR = "target/test-classes/integration";
    private static final String TEST_FILE_KEY_TESTCASES = "testCases";
    private static final String TEST_FILE_KEY_EXP_PD_REQUEST = "expectedPushdownRequest";
    private static final String TEST_FILE_KEY_EXP_PD_RESPONSE = "expectedPushdownResponse";
    private static final String TEST_FILE_KEY_DIALECT_EXASOL = "Exasol";
    private static final String JSON_API_KEY_INVOLVED_TABLES = "involvedTables";

    @Test
    public void testPushdownFromTestFile() throws Exception {
        File testDir = new File(INTEGRATION_TESTFILES_DIR);
        File[] files = testDir.listFiles((dir, name) -> name.endsWith(".json"));
        for (File testFile : files) {
            String jsonTest = Files.toString(testFile, Charsets.UTF_8);
            int numberOftests = getNumberOfTestsFrom(jsonTest);
            for (int testNr = 0; testNr < numberOftests; testNr++) {
                List<PushdownRequest> pushdownRequests = getPushdownRequestsFrom(jsonTest, testNr);
                List<String> expectedPushdownQueries = getExpectedPushdownQueriesFrom(jsonTest, testNr);
                for (PushdownRequest pushdownRequest: pushdownRequests) {
                    String pushdownQuery = generatePushdownQuery(pushdownRequest, hasMultipleTables(jsonTest, testNr));
                    assertExpectedPushdowns(expectedPushdownQueries, pushdownQuery, testFile.getName(), testNr);
                }
            }
        }
    }

    private void assertExpectedPushdowns(List<String> expectedPushdownQueries, String pushdownQuery, String testFile,
            int testNr) {
        boolean foundInExpected = expectedPushdownQueries.stream().anyMatch(pushdownQuery::contains);
        StringBuilder errorMessage = new StringBuilder();
        if (!foundInExpected)
        {
            errorMessage.append("Generated Pushdown: ");
            errorMessage.append(pushdownQuery);
            errorMessage.append(" not found in expected pushdowns (");
            errorMessage.append(expectedPushdownQueries);
            errorMessage.append("). Testfile: ");
            errorMessage.append(testFile);
            errorMessage.append(" ,Test#: ");
            errorMessage.append(testNr);
        }
        assertTrue(errorMessage.toString(), foundInExpected);
    }

    private int getNumberOfTestsFrom(String jsonTest) throws Exception {
        JsonObject root = JsonHelper.getJsonObject(jsonTest);
        return root.getJsonArray(TEST_FILE_KEY_TESTCASES).size();
    }

    private List<PushdownRequest> getPushdownRequestsFrom(String jsonTest, int testNr) throws Exception {
        JsonObject root = JsonHelper.getJsonObject(jsonTest);
        JsonObject test = root.getJsonArray(TEST_FILE_KEY_TESTCASES).getValuesAs(JsonObject.class).get(testNr);
        int numberOfPushdownRequests = test.getJsonArray(TEST_FILE_KEY_EXP_PD_REQUEST).size();
        List<PushdownRequest> pushdownRequests = new ArrayList<PushdownRequest>(numberOfPushdownRequests);
        for(int requestNr = 0; requestNr < numberOfPushdownRequests; requestNr++) {
            String req = test.getJsonArray(TEST_FILE_KEY_EXP_PD_REQUEST).get(requestNr).toString();
            RequestJsonParser parser = new RequestJsonParser();
            AdapterRequest request = parser.parseRequest(req);
            pushdownRequests.add((PushdownRequest) request);
        }
        return pushdownRequests;
    }

    private Boolean hasMultipleTables(String jsonTest, int testNr) throws Exception {
        JsonObject root = JsonHelper.getJsonObject(jsonTest);
        JsonObject test = root.getJsonArray(TEST_FILE_KEY_TESTCASES).getValuesAs(JsonObject.class).get(testNr);
        JsonValue req = test.getJsonArray(TEST_FILE_KEY_EXP_PD_REQUEST).get(0);
        int size = ((JsonObject) req).getJsonArray(JSON_API_KEY_INVOLVED_TABLES).size();
        return size > 1;
    }

    private String generatePushdownQuery(PushdownRequest pushdownRequest, Boolean multipleTables) throws AdapterException {
        String schemaName = "LS";
        SqlGenerationContext context = new SqlGenerationContext("", schemaName, false, multipleTables);
        SqlDialectContext dialectContext = new SqlDialectContext(Mockito.mock(SchemaAdapterNotes.class));
        ExasolSqlDialect dialect = new ExasolSqlDialect(dialectContext);
        final SqlGenerationVisitor sqlGeneratorVisitor = dialect.getSqlGenerationVisitor(context);
        return pushdownRequest.getSelect().accept(sqlGeneratorVisitor);
    }

    private List<String> getExpectedPushdownQueriesFrom(String jsonTest, int testNr) throws Exception {
        JsonObject root = JsonHelper.getJsonObject(jsonTest);
        JsonObject test = root.getJsonArray(TEST_FILE_KEY_TESTCASES).getValuesAs(JsonObject.class).get(testNr);
        int numberOfPushdownResponses = test.getJsonObject(TEST_FILE_KEY_EXP_PD_RESPONSE).getJsonArray(TEST_FILE_KEY_DIALECT_EXASOL).size();
        List<String> pushdownResponses = new ArrayList<>(numberOfPushdownResponses);
        for(int pushdownNr = 0; pushdownNr < numberOfPushdownResponses; pushdownNr++) {
            pushdownResponses.add(test.getJsonObject(TEST_FILE_KEY_EXP_PD_RESPONSE).getJsonArray(TEST_FILE_KEY_DIALECT_EXASOL).get(pushdownNr)
                    .toString().replaceAll("\\\\\"", "\"").replaceAll("^\"+", "").replaceAll("\"$", ""));
        }
        return pushdownResponses;
    }
}