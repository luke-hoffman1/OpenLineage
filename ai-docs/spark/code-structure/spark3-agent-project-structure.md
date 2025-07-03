# OpenLineage Spark 3 Agent: Project Structure

This document provides a detailed overview of the `spark3` module, the OpenLineage integration responsible for extracting lineage data from Apache Spark 3.x applications. It adapts the core OpenLineage functionality to the specific APIs and features of Spark 3.

---

## High-Level Goal

The primary goal of the **Spark 3 agent** is to listen to Spark execution events, inspect the `LogicalPlan` associated with those events, and translate Spark's plan into OpenLineage `Dataset` and `Job` facets. It captures metadata about data sources, data sinks, and the transformations that occur within a Spark job.

---

## Directory Structure

The project follows a standard Java project layout. All source code is located in `src/main/java`, with corresponding tests in `src/test/java`.

```
integration/spark/spark3/
├── src/
│   ├── main/
│   │   └── java/
│   │       └── io/openlineage/spark3/agent/
│   │           ├── lifecycle/
│   │           │   └── plan/
│   │           │       ├── catalog/
│   │           │       └── column/
│   │           └── utils/
│   └── test/
│       └── java/
│           └── io/openlineage/spark3/agent/
└── build.gradle
```


---

## Core Lineage Extraction Logic

The logic for extracting lineage resides in the `lifecycle.plan` package. This code is responsible for visiting nodes in Spark's `LogicalPlan` and building the corresponding OpenLineage events.

### Dataset Builders & Plan Visitors

These classes are responsible for identifying the input and output datasets for a given Spark operation. They are designed as a collection of `PartialFunction` builders that match specific `LogicalPlan` nodes.

* **Dataset Builders**: Located in `lifecycle.plan`, these classes create OpenLineage `Dataset` objects from Spark commands. Key examples include:
    * `CreateReplaceDatasetBuilder`: Handles `CREATE TABLE` and `REPLACE TABLE` operations.
    * `AppendDataDatasetBuilder`: Processes data appends to existing tables.
    * `MergeIntoCommand...DatasetBuilder`: A set of builders that handle the inputs and outputs for `MERGE INTO` commands, a common operation in data warehousing.
    * `DataSourceV2...DatasetBuilder`: A series of builders for handling Spark's modern `DataSourceV2` API, which is critical for interacting with data sources like Delta Lake, Iceberg, and others.

* **Plan Visitors**: These classes handle Spark commands that don't fit the typical input/output model but still represent important lineage events. Examples include:
    * `DropTableVisitor`: Captures when a table is dropped.
    * `CreateTableLikeCommandVisitor`: Handles `CREATE TABLE LIKE ...` statements.
    * `RefreshTableCommandVisitor`: Captures `REFRESH TABLE` commands.

### Catalog Handlers

The `lifecycle.plan.catalog` package contains handlers for extracting metadata from different types of Spark catalogs. Since every data source has a different way of identifying itself (e.g., table names, file paths, server addresses), these handlers normalize the metadata into a consistent OpenLineage `Dataset` identifier.

* **`CatalogHandler.java`**: The main interface for all catalog-specific logic.
* **Implementations**: There are specific handlers for major data sources, including:
    * `IcebergHandler`
    * `DeltaHandler` & `DatabricksDeltaHandler`
    * `JdbcHandler`
    * `CosmosHandler`

### Column-Level Lineage

The `lifecycle.plan.column` package is dedicated to extracting column-level lineage, which tracks how individual columns from input datasets are transformed into output columns.

* **Core Utilities**:
    * `ColumnLevelLineageUtils.java`: Contains the primary logic for traversing the Spark `LogicalPlan` to connect input and output fields.
    * `ExpressionDependencyCollector.java`: Collects dependencies between Spark `Expression` objects, which is the key to understanding column transformations.
* **Visitors**: The `column.visitors` sub-package contains classes that analyze specific types of operations to determine column dependencies, such as for `UNION` and `MERGE` statements.

---

## Utilities

The `utils` package contains helper classes that provide common functionality used across the agent.

* `PlanUtils3.java`: A collection of utility methods for traversing and extracting information from Spark 3 `LogicalPlan` objects.
* `DataSourceV2RelationDatasetExtractor.java`: A helper for extracting dataset information specifically from `DataSourceV2` relations.

---

## Testing Strategy

The `src/test` directory mirrors the source package structure, ensuring comprehensive test coverage for all components. Tests cover:

* **Dataset Builders & Visitors**: Verifying that the correct lineage is extracted for various Spark SQL commands and operations.
* **Catalog Handlers**: Ensuring that dataset identifiers are correctly parsed from different catalog types.
* **Column-Level Lineage**: Testing that column dependencies are accurately tracked through complex transformations.

---

## Build and Integration

* **`build.gradle`**: This file defines the project's dependencies, including the `shared` OpenLineage module and the required Spark 3 libraries. It also configures the build process for the `spark3` agent.
* **Integration with Core**: The `spark3` module implements the abstract interfaces defined in the `shared` module. This modular design allows OpenLineage to support multiple Spark versions (e.g., Spark 2 and Spark 3) with version-specific logic, while sharing a common core infrastructure.