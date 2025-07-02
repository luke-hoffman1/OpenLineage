# Part 4: Building the OpenLineage Event - Facets and Builders

Identifying input and output datasets is only half the story. To provide rich, actionable lineage, the OpenLineage Spark integration attaches detailed metadata to every event it produces. This is accomplished through **Facets**—structured, reusable JSON objects that conform to the OpenLineage specification. This section explains what facets are and how they are constructed.

## 4.1 Understanding Facets

A Facet is a modular block of metadata that can be attached to the core OpenLineage models: `Run`, `Job`, `InputDataset`, and `OutputDataset`. Each facet has a well-defined structure and a specific purpose, allowing producers and consumers of lineage data to share rich, semantic information in a standardized way.

By using facets, the Spark integration can provide deep insights into the operational details of a job, such as data quality metrics, schema information, and error details, without cluttering the core lineage graph.

### Example Facet: `SQLJobFacet`
A facet is typically a simple Plain Old Java Object (POJO) that can be serialized to JSON. Here is the implementation of the `SQLJobFacet`, which is used to record the SQL query that generated a job.

```java
// From io.openlineage.client.OpenLineage.java
public class SQLJobFacet implements JobFacet {
  private String query; // The SQL query text

  // Standard getters, setters, and constructor
  public String getQuery() { return query; }
  public void setQuery(String query) { this.query = query; }
  
  // ...
}
```

When this facet is attached to a job, the resulting OpenLineage event JSON will look something like this:
```json
{
  "job": {
    "namespace": "my-namespace",
    "name": "my-job",
    "facets": {
      "sql": {
        "_producer": "https://github.com/OpenLineage/OpenLineage/...",
        "_schemaURL": "https://openlineage.io/spec/facets/1-0-0/SQLJobFacet.json",
        "query": "SELECT * FROM my_table"
      }
    }
  }
  // ...
}
```

### Common Facets in the Spark Integration

The agent uses a wide variety of standard and custom facets. Here are some of the most common ones:

| Facet Class (`io.openlineage.spark.agent.facets.*`) | Attached To | Purpose |
| ------------------------------------------- | ----------- | -------------------------------------------------------------------------------------------------------------------- |
| **`SchemaDatasetFacet`** | `InputDataset`, `OutputDataset` | Describes the dataset's schema, including a list of fields with their names, types, and descriptions. |
| **`DataSourceDatasetFacet`** | `InputDataset`, `OutputDataset` | Identifies the underlying data source with a name (e.g., `s3`, `kafka`, `postgresql`) and a URI. |
| **`OutputStatisticsOutputDatasetFacet`** | `OutputDataset` | Records quantitative metrics about the data that was written, including the number of rows (`rowCount`) and the physical size in bytes (`size`). |
| **`ErrorFacet`** | `Run` | Captures detailed information about a job failure, including the exception type, message, and a full stack trace. |
| **`SparkLogicalPlanFacet`** | `Run` | A debug-oriented facet that contains the string representation of the Spark `LogicalPlan`. This is invaluable for troubleshooting the agent itself. |
| **`SparkApplicationDetailsFacet`** | `Job` | Contains details about the Spark application, such as the application name, Spark master URL, and user who submitted the job. |
| **`SparkPropertyFacet`** | `Run` | Captures a subset of Spark configuration properties that were active for the run. |
| **`SQLJobFacet`** | `Job` | Contains the normalized SQL query text that initiated the job. |
| **`OwnershipJobFacet`** | `Job` | Records ownership information for the job, specified via Spark configuration. |
| **`ParentRunFacet`** | `Run` | Links a job run to a parent run, typically the overarching `SparkApplication` run. This creates a hierarchical view of executions. |

## 4.2 `CustomFacetBuilder` Catalog

Facets are not created directly by the core lineage visitors. Instead, their construction is delegated to a set of `CustomFacetBuilder` classes. This follows the same modular, resilient `PartialFunction` pattern used for dataset discovery.

A `CustomFacetBuilder` is a `PartialFunction` that is defined for a specific input—often a `SparkListenerEvent` but sometimes a `LogicalPlan` node or other context object. When the `OpenLineageRunEventBuilder` constructs the final event, it offers up these context objects to a list of registered facet builders. Any builder that is "defined" for the object gets to run and attach its facet to the appropriate part of the `RunEvent`.

These builders are primarily located in the `shared/src/main/java/io/openlineage/spark/agent/facets/builder/` package.

### Example Facet Builder: `ErrorFacetBuilder`
The `ErrorFacetBuilder` is a great example of this pattern. It listens for `SparkListenerJobEnd` events and checks if the job result was a `JobFailed`.

```java
// In ErrorFacetBuilder.java
public class ErrorFacetBuilder extends CustomFacetBuilder<SparkListenerJobEnd, ErrorFacet> {

  @Override
  public boolean isDefinedAt(SparkListenerJobEnd x) {
    // This builder only applies if the job has failed.
    return x.jobResult() instanceof JobFailed;
  }

  @Override
  protected void build(
      SparkListenerJobEnd event, BiConsumer<String, ? super ErrorFacet> consumer) {
    JobFailed jobFailed = (JobFailed) event.jobResult();
    
    // Construct the facet with the exception details
    ErrorFacet errorFacet =
        new ErrorFacet(
            jobFailed.exception(),
            ScalaConversionUtils.fromScala(jobFailed.exception().getStackTrace()));
            
    // Add the facet to the event, keyed by the name "spark.exception"
    consumer.accept("spark.exception", errorFacet);
  }
}
```

### Key Facet Builders

| Builder Class Name | Triggering Context/Event | Facet Created | Attached To | Description |
| -------------------- | ------------------------ | ------------- | ----------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **`ErrorFacetBuilder`** | `SparkListenerJobEnd` (with failure) | `ErrorFacet` | `Run` | When a job fails, this builder extracts the exception from the `JobFailed` result and attaches a detailed `ErrorFacet`. |
| **`OutputStatisticsOutputDatasetFacetBuilder`** | `SparkListenerJobEnd` | `OutputStatisticsOutputDatasetFacet` | `OutputDataset` | On job completion, this builder queries the `JobMetricsHolder` (which has been accumulating metrics from `SparkListenerTaskEnd` events) and attaches dataset-level statistics. |
| **`SparkApplicationDetailsFacetBuilder`** | `QueryExecution` or `SparkListenerJobStart` | `SparkApplicationDetailsFacet` | `Job` | Gathers and attaches details about the Spark application itself, such as the application name from the `SparkConf` and the Spark user. |
| **`LogicalPlanRunFacetBuilder`** | `QueryExecution` | `SparkLogicalPlanFacet` | `Run` | If enabled, this builder serializes the `LogicalPlan` from the `QueryExecution` object into a string and attaches it as a debug facet to the run. |
| **`SparkPropertyFacetBuilder`** | `SparkListenerEvent` | `SparkPropertyFacet` | `Run` | Collects relevant Spark properties from the `SparkConf` and attaches them to the run, providing a snapshot of the job's configuration. |
| **`OwnershipJobFacetBuilder`** | `SparkListenerJobEnd` | `OwnershipJobFacet` | `Job` | Reads ownership information provided in the `openlineage.yml` configuration or Spark properties and attaches it to the job. |
| **`SparkProcessingEngineRunFacetBuilder`** | `SparkListenerApplicationStart` | `ProcessingEngineRunFacet` | `Run` | Attached to the application-level `START` event, this facet identifies the processing engine as Spark and includes its version. |

This decoupled design for facet creation allows the agent to be highly extensible. New facets can be added to support new data sources or provide richer metadata simply by creating a new `CustomFacetBuilder` implementation, without modifying the core logic of the `OpenLineageRunEventBuilder`. 