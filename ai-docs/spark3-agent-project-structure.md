# OpenLineage Spark3 Agent: Project Structure

This document provides a detailed overview of the `spark3` module within the OpenLineage Spark integration. The Spark3 agent is designed to extract lineage information from Apache Spark 3.x applications, adapting the core OpenLineage functionality to the specific APIs and features of Spark 3.

## Module Overview

The `spark3` module implements version-specific components for Apache Spark 3.x, extending the abstract interfaces defined in the `shared` module. Its primary responsibility is to extract lineage information from Spark's `LogicalPlan` and other Spark 3-specific constructs.

## Directory Structure

```
integration/spark/spark3/
├── src/
│   ├── main/
│   │   └── java/
│   │       └── io/
│   │           └── openlineage/
│   │               └── spark3/
│   │                   └── agent/
│   │                       ├── lifecycle/
│   │                       │   └── plan/
│   │                       │       ├── catalog/
│   │                       │       └── column/
│   │                       │           └── visitors/
│   │                       └── utils/
│   └── test/
│       ├── java/
│       │   └── io/
│       │       └── openlineage/
│       │           └── spark3/
│       │               └── agent/
│       │                   ├── lifecycle/
│       │                   │   └── plan/
│       │                   │       ├── catalog/
│       │                   │       └── column/
│       │                   │           └── visitors/
│       │                   └── utils/
│       └── resources/
│           └── io/
│               └── openlineage/
│                   └── spark/
│                       └── agent/
└── build.gradle
```

## Source Code Components

### Lifecycle Plan Components

The `io.openlineage.spark3.agent.lifecycle.plan` package contains classes responsible for extracting lineage information from Spark 3's logical plan. These components are the core of the Spark 3 integration.

#### Dataset Builders

These classes are responsible for constructing OpenLineage `Dataset` objects from various Spark logical plan nodes:

* **`AppendDataDatasetBuilder.java`**: Builds datasets for Spark's `AppendData` commands, which append data to existing tables.
* **`CreateReplaceDatasetBuilder.java`**: Handles `CreateTable` and `ReplaceTable` commands, extracting lineage for table creation and replacement operations.
* **`DataSourceV2RelationInputOnEndDatasetBuilder.java`**: Builds input datasets from DataSourceV2 relations at the end of query execution.
* **`DataSourceV2RelationInputOnStartDatasetBuilder.java`**: Builds input datasets from DataSourceV2 relations at the start of query execution.
* **`DataSourceV2RelationOutputDatasetBuilder.java`**: Builds output datasets from DataSourceV2 relations.
* **`DataSourceV2ScanRelationOnEndInputDatasetBuilder.java`**: Builds input datasets from DataSourceV2 scan relations at the end of query execution.
* **`DataSourceV2ScanRelationOnStartInputDatasetBuilder.java`**: Builds input datasets from DataSourceV2 scan relations at the start of query execution.
* **`InMemoryRelationInputDatasetBuilder.java`**: Extracts lineage from in-memory relations (e.g., cached tables).
* **`LogicalRelationDatasetBuilder.java`**: Builds datasets from Spark's `LogicalRelation` nodes.
* **`MergeIntoCommandEdgeInputDatasetBuilder.java`**: Handles input datasets for `MERGE INTO` commands with edge cases.
* **`MergeIntoCommandEdgeOutputDatasetBuilder.java`**: Handles output datasets for `MERGE INTO` commands with edge cases.
* **`MergeIntoCommandInputDatasetBuilder.java`**: Builds input datasets for standard `MERGE INTO` commands.
* **`MergeIntoCommandOutputDatasetBuilder.java`**: Builds output datasets for standard `MERGE INTO` commands.
* **`SparkExtensionV1InputDatasetBuilder.java`**: Handles input datasets for Spark extension commands (V1 API).
* **`SparkExtensionV1OutputDatasetBuilder.java`**: Handles output datasets for Spark extension commands (V1 API).
* **`SubqueryAliasInputDatasetBuilder.java`**: Extracts lineage from subquery aliases for input datasets.
* **`SubqueryAliasOutputDatasetBuilder.java`**: Extracts lineage from subquery aliases for output datasets.
* **`TableContentChangeDatasetBuilder.java`**: Handles lineage for operations that change table content without explicit input/output relations.

#### Plan Visitors

These classes visit specific nodes in Spark's logical plan to identify operations and extract lineage:

* **`CreateTableLikeCommandVisitor.java`**: Visits `CreateTableLikeCommand` nodes to extract lineage for "CREATE TABLE LIKE" operations.
* **`DropTableVisitor.java`**: Extracts lineage information from `DropTable` commands.
* **`RefreshTableCommandVisitor.java`**: Handles lineage for `REFRESH TABLE` commands.

### Catalog Handlers

The `io.openlineage.spark3.agent.lifecycle.plan.catalog` package contains handlers for different catalog types in Spark 3:

* **`AbstractDatabricksHandler.java`**: Base class for Databricks-specific catalog handlers.
* **`CatalogHandler.java`**: Interface defining the contract for all catalog handlers.
* **`CatalogUtils3.java`**: Utility methods for working with Spark 3 catalogs.
* **`CosmosHandler.java`**: Handler for Azure Cosmos DB catalogs.
* **`DatabricksDeltaHandler.java`**: Handler for Databricks Delta Lake catalogs.
* **`DatabricksUnityV2Handler.java`**: Handler for Databricks Unity Catalog (V2).
* **`DeltaHandler.java`**: Handler for open-source Delta Lake catalogs.
* **`IcebergHandler.java`**: Handler for Apache Iceberg catalogs.
* **`JdbcHandler.java`**: Handler for JDBC catalogs.
* **`RelationHandler.java`**: Interface for handlers that work with Spark relations.
* **`V2SessionCatalogHandler.java`**: Handler for Spark's V2 session catalog.
* **`MissingDatasetIdentifierCatalogException.java`**: Exception thrown when a dataset identifier cannot be determined.
* **`UnsupportedCatalogException.java`**: Exception thrown for unsupported catalog types.

### Column-Level Lineage

The `io.openlineage.spark3.agent.lifecycle.plan.column` package contains classes for extracting column-level lineage:

* **`ColumnLevelLineageUtils.java`**: Utility methods for column-level lineage extraction.
* **`CustomCollectorsUtils.java`**: Utilities for custom collectors used in column lineage.
* **`ExpressionDependencyCollector.java`**: Collects dependencies between expressions for column lineage.
* **`InputFieldsCollector.java`**: Collects input fields from logical plans.
* **`JdbcColumnLineageVisitor.java`**: Visitor for extracting column lineage from JDBC operations.
* **`JdbcColumnLineageVisitorDelegate.java`**: Delegate for JDBC column lineage visitors.
* **`MergeIntoDeltaColumnLineageVisitor.java`**: Extracts column lineage from Delta Lake MERGE operations.
* **`OutputFieldsCollector.java`**: Collects output fields from logical plans.
* **`QueryRelationColumnLineageCollector.java`**: Collects column lineage from query relations.

#### Column Visitors

The `io.openlineage.spark3.agent.lifecycle.plan.column.visitors` package contains visitors for specific expression types:

* **`ExpressionDependencyVisitor.java`**: Base visitor interface for expression dependencies.
* **`IcebergMergeIntoDependencyVisitor.java`**: Visitor for Iceberg MERGE operations.
* **`UnionDependencyVisitor.java`**: Visitor for UNION operations.

### Utility Classes

The `io.openlineage.spark3.agent.utils` package contains utility classes:

* **`DataSourceV2RelationDatasetExtractor.java`**: Extracts dataset information from DataSourceV2 relations.
* **`DatasetVersionDatasetFacetUtils.java`**: Utilities for dataset version facets.
* **`ExtensionDataSourceV2Utils.java`**: Utilities for working with Spark extension DataSourceV2 APIs.
* **`PlanUtils3.java`**: Spark 3-specific plan utilities.

## Test Components

The test directory mirrors the main source structure, with test classes for each component:

### Lifecycle Plan Tests

* Tests for dataset builders (e.g., `AppendDataDatasetBuilderTest.java`, `CreateReplaceDatasetBuilderTest.java`)
* Tests for plan visitors (e.g., `CreateTableLikeCommandVisitorTest.java`, `DropTableVisitorTest.java`)

### Catalog Tests

* Tests for catalog handlers (e.g., `CatalogUtils3Test.java`, `DeltaHandlerTest.java`, `IcebergHandlerTest.java`)

### Column-Level Lineage Tests

* Tests for column lineage components (e.g., `ExpressionDependencyCollectorTest.java`, `InputFieldsCollectorTest.java`)
* Tests for column visitors (e.g., `UnionFieldDependencyCollectorTest.java`)

### Utility Tests

* Tests for utility classes (e.g., `DataSourceV2RelationDatasetExtractorTest.java`, `PlanUtils3Test.java`)

## Resources

* **`version.properties`**: Contains version information for the Spark 3 agent.

## Build Configuration

The `build.gradle` file configures the build process for the Spark 3 module, including dependencies on the `shared` module and Spark 3.x libraries.

## Integration with Core Components

The Spark 3 module integrates with the core OpenLineage components by:

1. Implementing the abstract interfaces defined in the `shared` module
2. Providing Spark 3-specific implementations of dataset builders, plan visitors, and catalog handlers
3. Extracting lineage information from Spark 3's logical plan and other constructs
4. Converting Spark-specific metadata into OpenLineage format

This modular approach allows the OpenLineage integration to support multiple Spark versions while sharing common logic and interfaces.