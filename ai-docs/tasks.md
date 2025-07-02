# Comprehensive Architecture Documentation Tasks

This document outlines the necessary tasks to create a deep and comprehensive guide to the OpenLineage Spark Integration architecture.

## Part 1: The Big Picture - How OpenLineage Intercepts Spark

### Task 1.1: The `spark-submit` Journey & Agent Initialization
- **Goal**: Explain how the OpenLineage agent gets loaded into a Spark application and activated.
- **Details**:
  - Explain how the `--jars` or `--packages` argument in `spark-submit` makes the agent JAR available on the classpath of the Spark driver and executors.
  - Detail the role of the `spark.extraListeners` configuration. Describe the mechanism by which the Spark `LiveListenerBus` finds, instantiates, and registers `io.openlineage.spark.agent.OpenLineageSparkListener`.
  - Illustrate the initialization flow: Spark Application Start -> `LiveListenerBus` instantiates listeners -> `OpenLineageSparkListener` is created.
  - Describe the creation of the `ContextFactory` and the selection of the correct `ExecutionContext` based on the Spark environment.

### Task 1.2: Core Components & High-Level Architecture
- **Goal**: Provide a high-level map of the major components and their interactions.
- **Details**:
  - Create a high-level architecture diagram (using Mermaid `graph TD`).
  - The diagram should include:
    - `Spark Driver / LiveListenerBus`
    - `OpenLineageSparkListener`
    - `ExecutionContext` (as a general concept)
    - `OpenLineageRunEventBuilder`
    - `QueryPlanVisitor`s & `DatasetBuilder`s
    - `CustomFacetBuilder`s
    - `EventEmitter`
    - `OpenLineage Backend`
  - Write a brief description for the responsibility of each component shown in the diagram.

### Task 1.3: The End-to-End Event Flow
- **Goal**: Show the dynamic interaction between components when a Spark job runs.
- **Details**:
  - Create a detailed sequence diagram (using Mermaid `sequenceDiagram`).
  - The sequence should start with a user action (e.g., `spark.sql("...")`) and trace the following events:
    1. Spark triggers a `SparkListenerSQLExecutionStart` event.
    2. `OpenLineageSparkListener` receives the event.
    3. It delegates to the active `SparkSQLExecutionContext`.
    4. The context captures the `QueryExecution` object containing the `LogicalPlan`.
    5. On a `SparkListenerJobEnd` event, the `OpenLineageRunEventBuilder` is invoked.
    6. `OpenLineageRunEventBuilder` traverses the `LogicalPlan` using registered visitors.
    7. Visitors create `InputDataset` and `OutputDataset` objects.
    8. `OpenLineageRunEventBuilder` invokes facet builders to gather additional metadata.
    9. A complete `RunEvent` is constructed.
    10. The `EventEmitter` serializes and sends the event to the OpenLineage backend.

## Part 2: Deep Dive into Listeners and Execution Contexts

### Task 2.1: `OpenLineageSparkListener`
- **Goal**: Fully document the main entry point of the agent.
- **Details**:
  - Explain its role as the primary interface between Spark's event bus and the OpenLineage agent logic.
  - Detail which specific `SparkListenerEvent`s it handles (e.g., `onJobStart`, `onJobEnd`, `onApplicationStart`, `onSQLExecutionStart`).
  - Explain how it uses the `ContextFactory` to create and manage the lifecycle of different `ExecutionContext`s.

### Task 2.2: The `ExecutionContext` Strategy
- **Goal**: Explain how the agent manages state for different types of Spark jobs.
- **Details**:
  - Explain the purpose of the `ExecutionContext` interface as a state holder for a single Spark execution unit.
  - Describe each implementation in detail:
    - **`SparkApplicationExecutionContext`**: Manages the overall application lifecycle. It's responsible for `START` and `COMPLETE` events for the entire Spark application run.
    - **`SparkSQLExecutionContext`**: Manages the lifecycle of a single Spark SQL query. Its primary role is to capture the `QueryExecution` object, which is the key to accessing the `LogicalPlan`.
    - **`RddExecutionContext`**: Manages the lifecycle for RDD-based jobs (those without a `QueryExecution`). It captures job and stage information for lineage derived from RDDs.
  - Explain why this contextual separation is critical for accurately mapping lineage to the correct jobs and queries.

## Part 3: The Heart of the Agent - Parsing the Logical Plan

### Task 3.1: The Visitor Pattern and `PartialFunction`
- **Goal**: Explain the core mechanism for traversing Spark's query plans.
- **Details**:
  - Briefly introduce Spark's `LogicalPlan` as an abstract syntax tree representing a data transformation.
  - Explain why the Visitor pattern is the ideal choice for traversing this tree structure without modifying the Spark internal classes.
  - Describe Scala's `PartialFunction` and its two key methods: `isDefinedAt` and `apply`. Explain how this makes the visitor code clean and resilient, allowing a visitor to safely ignore nodes it doesn't understand.

### Task 3.2: Catalog of `QueryPlanVisitor` Implementations
- **Goal**: Create a reference guide for all the plan visitors.
- **Details**:
  - Create a comprehensive table for the most common visitors found in `shared/src/main/java/io/openlineage/spark/agent/lifecycle/plan/` and the version-specific modules (`spark3/`, etc.).
  - For each visitor, document:
    - **Visitor Class Name**: e.g., `InsertIntoHadoopFsRelationVisitor`
    - **Target Logical Plan Node**: The specific Spark `LogicalPlan` class it targets (e.g., `InsertIntoHadoopFsRelationCommand`).
    - **Purpose**: What information does it extract? (e.g., "Extracts the output path, file format, and schema for data being written to HDFS-compatible filesystems.").
    - **Dataset Type**: `InputDataset` or `OutputDataset`.

### Task 3.3: Catalog of `DatasetBuilder` Implementations
- **Goal**: Document the more complex, event-driven builders.
- **Details**:
  - Explain the role of `AbstractQueryPlanDatasetBuilder` and its subtypes. Note that these are often more powerful than simple visitors, as they can be triggered by `SparkListenerEvent`s and can inspect the entire `QueryExecution` context, not just a single plan node.
  - Document key builders like `LogicalRelationDatasetBuilder` and `SaveIntoDataSourceCommandVisitor`. Explain how they handle generic cases (like reading from any `FileRelation`) and sometimes delegate to more specific handlers (like `JdbcRelationHandler`).

## Part 4: Building the OpenLineage Event - Facets and Builders

### Task 4.1: Understanding Facets
- **Goal**: Explain the concept of Facets for adding rich metadata.
- **Details**:
  - Explain what a Facet is in the OpenLineage specification: a reusable, structured JSON object for capturing specific metadata.
  - Provide a table of common Facets used in the Spark integration:
    - `dataSource`: Describes the source of the data (e.g., `file://`, `s3://`).
    - `schema`: Describes the dataset's columns and types.
    - `outputStatistics`: Records metrics about output data (e.g., row count, file size).
    - `error`: Captures exception details when a job fails.
    - `spark_logicalPlan`: A debug facet containing the serialized logical plan.

### Task 4.2: `CustomFacetBuilder` Catalog
- **Goal**: Document how facets are constructed and attached.
- **Details**:
  - Explain that `CustomFacetBuilder`s are also `PartialFunction`s that listen for various events (e.g., `SparkListenerJobStart`, `SparkListenerStageCompleted`, `StageInfo`) to build and attach facets.
  - Catalog the most important `CustomFacetBuilder`s:
    - `ErrorFacetBuilder`: Attaches error information on job failure.
    - `OutputStatisticsOutputDatasetFacetBuilder`: Uses `JobMetricsHolder` to aggregate and attach output stats like rows and bytes written.
    - `SparkApplicationDetailsFacetBuilder`: Gathers and attaches details about the Spark application itself (e-p., app name, master URL).
    - `DebugRunFacetBuilder`: Attaches a rich debug facet with classpath, system, and config information.

## Part 5: Extensibility and Advanced Topics

### Task 5.1: The Vendor SPI (Service Provider Interface)
- **Goal**: Explain how to add support for new technologies.
- **Details**:
  - Explain the purpose of the `vendor/` directory and its modular design.
  - Describe the `Vendor` interface (`isVendorAvailable`, `getVisitorFactory`, `getEventHandlerFactory`).
  - Walk through the process of how `Vendors.getVendors()` uses a class list to load available `Vendor` implementations, checks for their availability, and collects their factories.
  - This explains how integrations like Iceberg and Snowflake can provide their own `QueryPlanVisitor`s and `CustomFacetBuilder`s without modifying the core agent code.

### Task 5.2: Column-Level Lineage Architecture
- **Goal**: Provide a high-level overview of the complex column-level lineage feature.
- **Details**:
  - Explain that this is an optional and advanced feature.
  - Describe the role of `ColumnLevelLineageVisitor` and its methods (`collectInputs`, `collectOutputs`, `collectExpressionDependencies`).
  - Introduce the `ColumnLevelLineageBuilder`, explaining how it tracks dependencies between input and output columns using Spark's internal `ExprId` (Expression ID).
  - Emphasize that this is one of the most complex parts of the agent, as it requires a deep understanding of Spark's expression and catalyst optimizer internals. Provide an example of how a simple `SELECT colA, colB AS new_colB FROM table` would be processed. 