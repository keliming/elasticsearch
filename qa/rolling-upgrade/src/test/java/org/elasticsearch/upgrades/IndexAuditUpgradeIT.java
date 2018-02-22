/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.upgrades;

import org.apache.http.HttpEntity;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.elasticsearch.Version;
import org.elasticsearch.client.Response;
import org.elasticsearch.test.rest.yaml.ObjectPath;
import org.hamcrest.Matchers;
import org.junit.Before;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.apache.lucene.util.LuceneTestCase.AwaitsFix;

@AwaitsFix(bugUrl = "https://github.com/elastic/x-pack-elasticsearch/pull/4025")
public class IndexAuditUpgradeIT extends AbstractUpgradeTestCase {

    private Version minVersionInCluster;

    @Before
    public void findMinVersionInCluster() throws IOException {
        Response response = client().performRequest("GET", "_nodes");
        ObjectPath objectPath = ObjectPath.createFromResponse(response);
        Map<String, Object> nodesAsMap = objectPath.evaluate("nodes");
        Version version = Version.CURRENT;
        for (String id : nodesAsMap.keySet()) {
            Version nodeVersion = Version.fromString(objectPath.evaluate("nodes." + id + ".version"));
            if (nodeVersion.before(version)) {
                version = nodeVersion;
            }
        }
        minVersionInCluster = version;
    }

    public void testDocsAuditedInOldCluster() throws Exception {
        assumeTrue("only runs against old cluster", clusterType == CLUSTER_TYPE.OLD);
        assertBusy(() -> {
            assertAuditDocsExist();
            assertNumUniqueNodeNameBuckets(0); // TODO update on backport. Will become 2
        });
    }

    public void testDocsAuditedInMixedCluster() throws Exception {
        assumeTrue("only runs against mixed cluster", clusterType == CLUSTER_TYPE.MIXED);
        assertBusy(() -> {
            assertAuditDocsExist();
            // TODO update on backport. Will become 2 as new node won't index docs until a new version node is master
            assertNumUniqueNodeNameBuckets(0);
        });
    }

    public void testDocsAuditedInUpgradedCluster() throws Exception {
        assumeTrue("only runs against upgraded cluster", clusterType == CLUSTER_TYPE.UPGRADED);
        assertBusy(() -> {
            assertAuditDocsExist();
            // TODO update on backport. will become 4
            assertNumUniqueNodeNameBuckets(2);
        });
    }

    private void assertAuditDocsExist() throws Exception {
        final String type = minVersionInCluster.before(Version.V_6_0_0) ? "event" : "doc";
        Response response = client().performRequest("GET", "/.security_audit_log*/" + type + "/_count");
        assertEquals(200, response.getStatusLine().getStatusCode());
        Map<String, Object> responseMap = entityAsMap(response);
        assertNotNull(responseMap.get("count"));
        assertThat((Integer) responseMap.get("count"), Matchers.greaterThanOrEqualTo(1));
    }

    private void assertNumUniqueNodeNameBuckets(int numBuckets) throws Exception {
        // call API that will hit all nodes
        assertEquals(200, client().performRequest("GET", "/_nodes").getStatusLine().getStatusCode());

        HttpEntity httpEntity = new StringEntity(
                "{\n" +
                        "    \"aggs\" : {\n" +
                        "        \"nodes\" : {\n" +
                        "            \"terms\" : { \"field\" : \"node_name\" }\n" +
                        "        }\n" +
                        "    }\n" +
                        "}", ContentType.APPLICATION_JSON);
        Response aggResponse = client().performRequest("GET", "/.security_audit_log*/_search",
                Collections.singletonMap("pretty", "true"), httpEntity);
        Map<String, Object> aggResponseMap = entityAsMap(aggResponse);
        logger.debug("aggResponse {}", aggResponseMap);
        Map<String, Object> aggregations = (Map<String, Object>) aggResponseMap.get("aggregations");
        assertNotNull(aggregations);
        Map<String, Object> nodesAgg = (Map<String, Object>) aggregations.get("nodes");
        assertNotNull(nodesAgg);
        List<Map<String, Object>> buckets = (List<Map<String, Object>>) nodesAgg.get("buckets");
        assertNotNull(buckets);
        assertEquals(numBuckets, buckets.size());
    }
}
