package com.micewriter.sandbox.e2e;

import org.apache.hadoop.conf.Configuration;
import org.apache.iceberg.Table;
import org.apache.iceberg.catalog.Catalog;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.nessie.NessieCatalog;
import org.apache.iceberg.io.CloseableIterable;
import org.apache.iceberg.data.Record;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class SystemE2EIT {

    private static final String APP_URL = "http://k8s-node-1.local";
    private static final String NESSIE_URI = "http://k8s-node-1.local:19120/api/v1";
    private static final String MINIO_URL = "http://k8s-node-1.local:9000";
    private static final String MINIO_ACCESS_KEY = "micewriter";
    private static final String MINIO_SECRET_KEY = "micewriter123";
    private static final String WAREHOUSE = "s3://iceberg";
    
    private static Catalog catalog;
    private static RestTemplate restTemplate = new RestTemplate();

    @BeforeAll
    public static void setup() {
        Configuration hadoopConf = new Configuration();
        hadoopConf.set("fs.s3a.endpoint", MINIO_URL);
        hadoopConf.set("fs.s3a.access.key", MINIO_ACCESS_KEY);
        hadoopConf.set("fs.s3a.secret.key", MINIO_SECRET_KEY);
        hadoopConf.set("fs.s3a.path.style.access", "true");
        hadoopConf.set("fs.s3.impl", "org.apache.hadoop.fs.s3a.S3AFileSystem");

        NessieCatalog nessieCatalog = new NessieCatalog();
        nessieCatalog.setConf(hadoopConf);
        nessieCatalog.initialize("nessie", Map.of(
                "ref", "main",
                "uri", NESSIE_URI,
                "warehouse", WAREHOUSE,
                "s3.endpoint", MINIO_URL,
                "s3.access-key-id", MINIO_ACCESS_KEY,
                "s3.secret-access-key", MINIO_SECRET_KEY,
                "s3.path-style-access", "true"
        ));
        catalog = nessieCatalog;
    }

    @Test
    public void testEndToEndIngestion() throws Exception {
        TableIdentifier tableId = TableIdentifier.of(Namespace.of("micewriter"), "telemetry_events");
        
        long initialRows = 0;
        if (catalog.tableExists(tableId)) {
            Table table = catalog.loadTable(tableId);
            table.refresh();
            // Count rows manually if needed, or rely on snapshots
            initialRows = countRows(table);
        }

        // 1. Send 1000 events
        System.out.println("Sending 1000 events...");
        ResponseEntity<Map> response = restTemplate.postForEntity(APP_URL + "/events/load?count=1000", null, Map.class);
        assertEquals(200, response.getStatusCode().value());
        System.out.println("Load test response: " + response.getBody());

        // Wait a tiny bit for the UDS stream to finish
        Thread.sleep(1000);

        // 2. Trigger flush
        System.out.println("Triggering manual flush...");
        ResponseEntity<Map> flushResponse = restTemplate.postForEntity(APP_URL + "/events/flush", null, Map.class);
        assertEquals(200, flushResponse.getStatusCode().value());
        System.out.println("Flush response: " + flushResponse.getBody());

        // Wait for Engine to compile Parquet and commit to Nessie
        System.out.println("Waiting 5 seconds for commit...");
        Thread.sleep(5000);

        // 3. Assert Data
        assertTrue(catalog.tableExists(tableId), "Table should exist after flush");
        Table table = catalog.loadTable(tableId);
        table.refresh();
        
        long finalRows = countRows(table);
        System.out.println("Rows before: " + initialRows + ", Rows after: " + finalRows);
        assertEquals(initialRows + 1000, finalRows, "Should have exactly 1000 new rows");
    }

    private long countRows(Table table) {
        long actualCount = 0;
        try (org.apache.iceberg.io.CloseableIterable<org.apache.iceberg.data.Record> reader = 
             org.apache.iceberg.data.IcebergGenerics.read(table).build()) {
            for (Record record : reader) {
                actualCount++;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return actualCount;
    }
}
