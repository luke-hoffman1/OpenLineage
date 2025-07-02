# Part 5: Extensibility and Advanced Topics

The OpenLineage Spark integration is designed to be both powerful out-of-the-box and highly extensible. This final section covers two key areas: the Service Provider Interface (SPI) that allows for third-party integrations, and the advanced architecture behind column-level lineage.

## 5.1 The Extensibility Model: The `OpenLineageEventHandlerFactory` SPI

A key design goal of the agent is to support a wide range of Spark data sources and platforms without creating a monolithic, dependency-heavy JAR. This is achieved through a Service Provider Interface (SPI) that allows third-party "vendor" integrations to plug into the agent's lifecycle.

The core of this SPI is the `io.openlineage.spark.api.OpenLineageEventHandlerFactory` interface.

### How the SPI Works

The mechanism relies on Java's standard `ServiceLoader` pattern:

1.  **Implementation**: A third-party integration (e.g., for Iceberg, Delta Lake, or Snowflake) provides a JAR that contains an implementation of the `OpenLineageEventHandlerFactory` interface. This factory is responsible for providing lists of custom visitors and builders specific to that technology. Here's a simplified view of the interface:
    ```java
    // In OpenLineageEventHandlerFactory.java
    public interface OpenLineageEventHandlerFactory {
      Collection<PartialFunction<LogicalPlan, List<InputDataset>>> createInputDatasetQueryPlanVisitors(
          OpenLineageContext context);

      Collection<PartialFunction<LogicalPlan, List<OutputDataset>>> createOutputDatasetQueryPlanVisitors(
          OpenLineageContext context);
          
      Collection<CustomFacetBuilder<?, ? extends RunFacet>> createRunFacetBuilders(
          OpenLineageContext context);

      // ... other methods for job, input, and output facet builders
    }
    ```

2.  **Service Declaration**: The vendor JAR must include a special file in its resources: `META-INF/services/io.openlineage.spark.api.OpenLineageEventHandlerFactory`. This text file contains a single line: the fully qualified class name of the factory implementation (e.g., `io.openlineage.iceberg.IcebergOpenLineageEventHandlerFactory`).

3.  **Discovery**: When the OpenLineage agent initializes, its `InternalEventHandlerFactory` uses `ServiceLoader.load()` to discover and instantiate all factory implementations available on the classpath.
    ```java
    // In InternalEventHandlerFactory.java
    public InternalEventHandlerFactory() {
      // Discover all registered factories on the classpath
      ServiceLoader<OpenLineageEventHandlerFactory> loader =
          ServiceLoader.load(OpenLineageEventHandlerFactory.class, getClass().getClassLoader());
      
      // Store them in a list for later processing
      this.eventHandlerFactories =
          StreamSupport.stream(loader.spliterator(), false).collect(Collectors.toList());
      // ...
    }
    ```

4.  **Integration**: The agent then calls the methods on each discovered factory (`createInputDatasetQueryPlanVisitors`, `createOutputDatasetFacetBuilders`, etc.) and aggregates the returned visitors and builders into its main processing pipeline.

### Example: How Iceberg Integration Works

The `vendor/iceberg` module provides a concrete example.
- It implements `IcebergOpenLineageEventHandlerFactory`.
- This factory provides visitors that know how to handle Iceberg-specific `LogicalPlan` nodes like `MergeIntoTable` and `DeleteFromTable`.
- It also provides facet builders like `IcebergScanReportInputDatasetFacetBuilder`, which extracts detailed metrics from Iceberg table scans.
- When a user includes both the main agent JAR and the `openlineage-iceberg` JAR in their `spark-submit`, the `ServiceLoader` finds the Iceberg factory, and its specialized visitors and builders are automatically added to the agent's execution. This allows the agent to emit rich, Iceberg-specific lineage without having a hard dependency on the Iceberg libraries in its core module.

This SPI model makes the agent lightweight and ensures that users only need to include the dependencies for the technologies they actually use.

## 5.2 Column-Level Lineage Architecture

Column-level lineage (CLL) is one of the most powerful—and complex—features of the OpenLineage Spark integration. It provides fine-grained dependency tracking, showing exactly which input columns are used to produce each output column.

### High-Level Overview

This feature is disabled by default and can be enabled via configuration. When active, a specialized set of visitors and builders work together to analyze the Spark `LogicalPlan` at the expression level.

The key components are:
*   **`ColumnLevelLineageVisitor` implementations**: These are specialized visitors that know how to extract column dependencies for specific `LogicalPlan` nodes (e.g., `MergeIntoDeltaColumnLineageVisitor`).
*   **`ColumnLevelLineageBuilder`**: This is the central engine for CLL. It is responsible for orchestrating the analysis and building the final `ColumnLevelLineageDatasetFacet`.
*   **Expression Collectors**: The builder uses helper classes like `ExpressionDependencyCollector` and `InputFieldsCollector` to recursively traverse Spark SQL `Expression` trees and identify dependencies between attributes.

### The Core Mechanism: Tracking `ExprId`

The entire process hinges on Spark's internal `ExprId` (Expression ID). Every column and every transformation in a `LogicalPlan` is an `Expression` that has a unique `ExprId`. The `ColumnLevelLineageBuilder` works by building a dependency graph based on these IDs.

Here is a simplified view of the builder's main logic:
```java
// In ColumnLevelLineageBuilder.java
public void build(OpenLineageRunEventContext context) {
    // 1. Extract all output fields from the head of the logical plan
    OutputFieldsCollector.collect(context, plan, outputFields);

    // 2. Extract all input fields from the leaf nodes of the plan
    InputFieldsCollector.collect(context, plan, inputFields);
    
    // For each output field, find its dependencies
    outputFields.forEach(
        (output) -> {
          // 3. Recursively traverse the expression tree for this output field
          ExpressionDependencyCollector.collect(context, output.getExpression(), dependencies);
          
          // ... post-process dependencies ...
          
          // 4. For each identified dependency, find the original input field
          // by matching ExprIds and construct the lineage link.
        });
        
    // 5. Build and attach the final ColumnLineageDatasetFacet
    // ...
}
```

The process can be broken down:

1.  **Output Collection**: It first traverses the final `LogicalPlan` node (e.g., the `Project` list in a `SELECT` statement) to identify all output columns and their `ExprId`s.
2.  **Input Collection**: It then walks the plan tree to find all the input relations (the source datasets) and collects their columns and `ExprId`s.
3.  **Dependency Traversal**: This is the most complex step. The `ExpressionDependencyCollector` recursively traverses the expression tree for each output column. For example, if an output column is defined by the expression `(a + b) AS c`, the collector will traverse into the `Add` expression, find the `AttributeReference` expressions for `a` and `b`, and record their `ExprId`s as dependencies.
4.  **Graph Building & Resolution**: The builder takes the dependency `ExprId`s and maps them back to the original input fields that were collected in step 2. This creates the final link from an output field to the specific input fields it was derived from.
5.  **Facet Generation**: After traversing all output expressions, the builder has a complete map of dependencies. It uses this map to construct the `ColumnLineageDatasetFacet`, linking each output field name to the input dataset and field names it depends on.

### Example: A Simple Query

Consider the query:
```sql
SELECT name, age * 2 AS double_age FROM users
```

The column lineage process would be:

1.  **Outputs**: Identify output columns `name` and `double_age`.
2.  **Inputs**: Identify the `users` table as the input, with columns `name` and `age`.
3.  **Dependencies**:
    *   Trace the output `name`. The builder finds it directly maps to the input `name` from the `users` table.
    *   Trace the output `double_age`. The builder finds its expression is `age * 2`. It traverses this `Multiply` expression, finds the attribute for the input column `age`, and records the dependency.
4.  **Facet**: The final facet would report:
    *   `name` depends on `users.name`.
    *   `double_age` depends on `users.age`.

This process is highly complex because Spark's Catalyst optimizer heavily rewrites the `LogicalPlan` and its expressions, but the use of immutable `ExprId`s provides a stable way to track dependencies through these transformations. 