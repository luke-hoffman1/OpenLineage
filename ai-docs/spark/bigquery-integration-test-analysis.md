# BigQuery Integration Test Analysis

This document provides a detailed analysis of the `testReadAndWriteFromBigquery()` integration test in the OpenLineage Spark integration, focusing on how inputs and outputs are extracted from BigQuery operations.

## Test Overview

The `testReadAndWriteFromBigquery()` test in `GoogleCloudIntegrationTest.java` demonstrates how OpenLineage captures lineage information for BigQuery operations in Spark. The test:

1. Creates a test dataset
2. Writes it to a source BigQuery table
3. Reads from the source table
4. Writes to a target BigQuery table
5. Verifies that the correct lineage events are emitted

## Test Environment Setup

The test environment is set up with:

1. A mock server to capture OpenLineage events
2. A SparkSession configured with:
   - OpenLineageSparkListener registered as a Spark listener
   - HTTP transport configured to send events to the mock server
   - BigQuery and GCS credentials and configuration

```
// SparkSession configuration in beforeEach()
spark = SparkSession.builder()
    .master("local[*]")
    .appName("GoogleCloudIntegrationTest")
    .config("spark.driver.host", LOCAL_IP)
    .config("spark.driver.bindAddress", LOCAL_IP)
    .config("spark.ui.enabled", false)
    .config("spark.sql.shuffle.partitions", 1)
    .config("spark.sql.warehouse.dir", "file:/tmp/iceberg/")
    .config("spark.driver.extraJavaOptions", "-Dderby.system.home=/tmp/gctest")
    .config("spark.extraListeners", OpenLineageSparkListener.class.getCanonicalName())
    .config("spark.openlineage.transport.type", "http")
    .config("spark.openlineage.transport.url", 
            "http://localhost:" + mockServer.getPort() + "/api/v1/lineage")
    .config("spark.openlineage.namespace", NAMESPACE)
    // Additional BigQuery configurations omitted for brevity
    .getOrCreate();
```

## Data Flow

The test creates a simple data flow:

```
// Create test dataset with two columns (a, b)
Dataset<Row> dataset = getTestDataset();

// Write to source table
dataset.write()
    .format("bigquery")
    .option("table", source_table)
    .mode("overwrite")
    .save();

// Read from source table
Dataset<Row> first = spark.read()
    .format("bigquery")
    .option("table", source_table)
    .load();

// Write to target table
first.write()
    .format("bigquery")
    .option("table", target_table)
    .mode("overwrite")
    .save();
```

## Input/Output Extraction Process

### 1. Event Capture Mechanism

The OpenLineageSparkListener is the entry point for capturing lineage events:

1. It's registered as a Spark listener via the `spark.extraListeners` configuration
2. It receives Spark events like SQL execution start/end and job start/end
3. It creates execution contexts to track operations and their lineage
4. It emits OpenLineage events at appropriate points in the execution lifecycle

### 2. BigQuery Input Extraction

The `BigQueryNodeInputVisitor` is responsible for extracting input datasets from BigQuery operations:

1. It targets LogicalRelation nodes in the Spark execution plan that contain a BigQueryRelation
2. When it finds a matching node, it extracts:
   - The table name from the BigQueryRelation
   - The schema from the relation
3. For query-based inputs (DirectBigQueryRelation), it extracts datasets from the SQL query
4. It creates an InputDataset with:
   - namespace: "bigquery"
   - name: The fully qualified table name
   - facets: Schema information

### 3. BigQuery Output Extraction

The `BigQueryNodeOutputVisitor` is responsible for extracting output datasets from BigQuery operations:

1. It targets SaveIntoDataSourceCommand nodes that use a BigQueryRelationProvider
2. When it finds a matching node, it extracts:
   - The table name from the SparkBigQueryConfig
   - The schema from the command
3. It creates an OutputDataset with:
   - namespace: "bigquery"
   - name: The fully qualified table name
   - facets: Schema information

### 4. Event Generation

The test generates several OpenLineage events:

1. **START event** when writing to the target table begins:
   - Contains input dataset (source table) with schema
   - Contains output dataset (target table) without schema

2. **COMPLETE event** when writing to the target table finishes:
   - Contains input dataset (source table) with schema
   - Contains output dataset (target table) with schema and column lineage
   - Column lineage maps each output column to its source columns

### 5. Event Verification

The test verifies the events using:

1. `MockServerUtils.getEventsEmitted()` to retrieve all events
2. Assertions to check that events were emitted and have the expected structure
3. `verifyEvents()` to compare events against expected JSON templates
4. Additional checks to ensure no temporary job events are emitted

## Event Structure

### START Event (pysparkBigquerySaveStart.json)

```json
{
  "eventType": "START",
  "job": {
    "namespace": "{NAMESPACE}"
  },
  "inputs": [
    {
      "namespace": "bigquery",
      "name": "{PROJECT_ID}.{DATASET_ID}.{VERSION_NAME}_source",
      "facets": {
        "schema": {
          "fields": [
            {
              "name": "a",
              "type": "long"
            },
            {
              "name": "b",
              "type": "long"
            }
          ]
        }
      }
    }
  ],
  "outputs": [
    {
      "namespace": "bigquery",
      "name": "{PROJECT_ID}.{DATASET_ID}.{VERSION_NAME}_target"
    }
  ]
}
```

### COMPLETE Event (pysparkBigquerySaveEnd.json)

```json
{
  "eventType": "COMPLETE",
  "job": {
    "namespace": "{NAMESPACE}"
  },
  "inputs": [
    {
      "namespace": "bigquery",
      "name": "{PROJECT_ID}.{DATASET_ID}.{VERSION_NAME}_source",
      "facets": {
        "schema": {
          "fields": [
            {
              "name": "a",
              "type": "long"
            },
            {
              "name": "b",
              "type": "long"
            }
          ]
        }
      }
    }
  ],
  "outputs": [
    {
      "namespace": "bigquery",
      "name": "{PROJECT_ID}.{DATASET_ID}.{VERSION_NAME}_target",
      "facets": {
        "schema": {
          "fields": [
            {
              "name": "a",
              "type": "long"
            },
            {
              "name": "b",
              "type": "long"
            }
          ]
        },
        "columnLineage": {
          "fields": {
            "a": {
              "inputFields": [
                {
                  "namespace": "bigquery",
                  "name": "{PROJECT_ID}.{DATASET_ID}.{VERSION_NAME}_source",
                  "field": "a"
                }
              ]
            },
            "b": {
              "inputFields": [
                {
                  "namespace": "bigquery",
                  "name": "{PROJECT_ID}.{DATASET_ID}.{VERSION_NAME}_source",
                  "field": "b"
                }
              ]
            }
          }
        }
      }
    }
  ]
}
```

## Query-Based Operations

The test also includes a separate method `testReadAndWriteFromBigqueryUsingQuery()` that demonstrates how query-based BigQuery operations are handled:

1. It creates a source table and populates it with data
2. It reads from the source table using a SQL query
3. It writes to a target table
4. It verifies that the correct lineage events are emitted

The query-based event (pysparkBigqueryQueryEnd.json) is simpler and only includes the basic dataset information without schema or column lineage:

```json
{
  "eventType": "COMPLETE",
  "job": {
    "namespace": "{NAMESPACE}"
  },
  "inputs": [
    {
      "namespace": "bigquery",
      "name": "{PROJECT_ID}.{DATASET_ID}.{VERSION_NAME}_source_query_test"
    }
  ],
  "outputs": [
    {
      "namespace": "bigquery",
      "name": "{PROJECT_ID}.{DATASET_ID}.{VERSION_NAME}_target_query_test"
    }
  ]
}
```

## Key Components and Their Interactions

The key components involved in BigQuery lineage extraction are:

1. **OpenLineageSparkListener**: Hooks into Spark's event system and creates execution contexts
2. **BigQueryNodeInputVisitor**: Extracts input datasets from BigQuery operations
3. **BigQueryNodeOutputVisitor**: Extracts output datasets from BigQuery operations
4. **MockServerUtils**: Captures and verifies events in the test environment

The flow of data and metadata through the system is:

1. Spark executes BigQuery operations (read/write)
2. OpenLineageSparkListener receives Spark events
3. Visitor classes extract dataset information from the execution plan
4. OpenLineage events are created with input and output datasets
5. Events are sent to the configured endpoint (mock server in tests)
6. MockServerUtils captures and verifies the events

## Conclusion

The `testReadAndWriteFromBigquery()` integration test demonstrates how OpenLineage captures lineage information for BigQuery operations in Spark. The test shows that:

1. OpenLineage correctly identifies BigQuery tables as inputs and outputs
2. It captures schema information for both inputs and outputs
3. It tracks column-level lineage from source to target tables
4. It handles both direct table access and query-based operations
5. It filters out temporary job events to focus on the main data flow

This provides a comprehensive view of how data flows through BigQuery operations in Spark and how OpenLineage captures that information for data lineage tracking.