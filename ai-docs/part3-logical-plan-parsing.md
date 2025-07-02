# Part 3: The Heart of the Agent - Parsing the Logical Plan

Once the `SparkSQLExecutionContext` has captured the `QueryExecution` object for a Spark SQL job, the core lineage extraction process can begin. This process revolves around traversing Spark's `LogicalPlan`, a tree-like data structure that represents the user's query. This section explains the mechanism used for this traversal and catalogs the key components responsible for identifying datasets.

## 3.1 The Visitor Pattern and `PartialFunction`

### Spark's `LogicalPlan`
A `LogicalPlan` is an Abstract Syntax Tree (AST) that represents the data transformations a user has requested in a query, before Spark's Catalyst optimizer determines the best physical execution strategy. It's a tree of nodes, where each node is a Scala `case class` representing an operation (e.g., `Project`, `Filter`, `Join`) or a data relation (e.g., `HiveTableRelation`).

To extract lineage, the agent needs to walk this tree and identify the nodes that represent data sources (inputs) and data sinks (outputs).

### The Visitor Pattern with `PartialFunction` and a Smart Base Class
Instead of a classic Visitor pattern with `visit(NodeX)`, `visit(NodeY)`, etc., the OpenLineage agent uses a more flexible and resilient approach. The foundation is Scala's `PartialFunction`, but the implementation is made elegant and simple through a key abstract base class: `QueryPlanVisitor`.

A `PartialFunction[A, B]` is a function that is only defined for a subset of inputs of type `A`. It has two key methods:
1.  **`isDefinedAt(A): Boolean`**: Returns `true` if the function can handle the given input.
2.  **`apply(A): B`**: Executes the function's logic on the input.

The agent's visitors, which almost always extend `QueryPlanVisitor`, are `PartialFunction<LogicalPlan, List<Dataset>>`. This is where the clever design comes in.

#### The `QueryPlanVisitor` Base Class
You astutely observed that many visitors, like `InsertIntoHiveTableVisitor`, do not implement an `isDefinedAt` method. This is because the `QueryPlanVisitor<T extends LogicalPlan, ...>` base class provides a default implementation. Using Java reflection, this default method automatically checks if the `LogicalPlan` node it receives is an instance of the generic type `T` that the specific visitor was defined with.

For example, when defining `class MyVisitor extends QueryPlanVisitor<InsertIntoHiveTable, ...>`, the inherited `isDefinedAt` method effectively becomes `return x instanceof InsertIntoHiveTable;`.

This design has significant advantages:
*   **Simplicity & Less Boilerplate**: For the common case of matching a single `LogicalPlan` node type, the visitor author doesn't need to write any `isDefinedAt` logic at all.
*   **Resilience**: The agent can safely iterate through a list of visitors, and only the ones whose generic type matches the current node will execute. This prevents the agent from breaking if it encounters an unknown or new `LogicalPlan` node type. A visitor only needs to override `isDefinedAt` if it requires more complex logic than a simple type check (e.g., checking a property of the node).
*   **Modularity and Composability**: Each visitor remains a small, self-contained class focused on handling a single type of `LogicalPlan` node. The agent can chain these partial functions together, and the first one whose type matches gets to process the node.

#### What About `apply()`?
While `isDefinedAt` is conveniently handled by the base class, every concrete visitor **must** provide its own implementation of the `apply(LogicalPlan x)` method. This is the core logic of the visitor, containing the code to extract metadata and build the dataset.

Here is a corrected, real-world example of `InsertIntoHiveTableVisitor`. Note the absence of an `isDefinedAt` override because the generic type `InsertIntoHiveTable` is all that's needed for the check.

```java
// In InsertIntoHiveTableVisitor.java
// The class signature tells the base class all it needs for isDefinedAt.
public class InsertIntoHiveTableVisitor
    extends QueryPlanVisitor<InsertIntoHiveTable, OpenLineage.OutputDataset> {

  public InsertIntoHiveTableVisitor(OpenLineageContext context) {
    super(context);
  }

  // No isDefinedAt method is needed here! The base class handles it.
  // It will only be called if the input is an instance of InsertIntoHiveTable.

  @Override
  public List<OpenLineage.OutputDataset> apply(LogicalPlan x) {
    InsertIntoHiveTable cmd = (InsertIntoHiveTable) x;

    // We can skip the temporary view check here because another visitor,
    // CreateViewVisitor, will handle those cases separately.

    // Extract table metadata
    CatalogTable table = cmd.table();
    
    // Build the dataset using PathUtils and other helpers
    return Collections.singletonList(
        outputDataset()
            .getDataset(
                PathUtils.fromCatalogTable(table, context.getSparkSession().sparkContext()),
                table.schema(),
                getDatasetVersion(table)));
  }
}
```

### Plan Traversal with `OpenLineageRunEventBuilder`
The traversal logic is orchestrated by the `OpenLineageRunEventBuilder`. When its `build()` method is called, it uses `PlanUtils.visit()` to walk the `LogicalPlan` tree. This utility method recursively traverses every node in the plan and, for each node, attempts to apply the list of registered visitors.

```java
// Simplified logic in PlanUtils.java
public static <T, D extends Dataset> List<D> visit(
    PartialFunction<LogicalPlan, List<D>> visitor, LogicalPlan plan) {
  List<D> datasets = new ArrayList<>();
  // Apply the visitor to the current node
  if (visitor.isDefinedAt(plan)) {
    datasets.addAll(visitor.apply(plan));
  }
  // Recursively call visit on all children of the current node
  plan.children().forEach(child -> datasets.addAll(visit(visitor, child)));
  return datasets;
}
```
The `OpenLineageRunEventBuilder` calls this for both its input and output visitor lists, aggregating all discovered datasets.

## 3.2 The Two Types of Dataset Extractors: Visitors and Builders

While both `QueryPlanVisitor`s and `DatasetBuilder`s create datasets, they operate at different scopes and are designed for different kinds of tasks. Understanding their distinction is key to understanding the agent's design.

The fundamental difference is their **scope**:

*   A **`QueryPlanVisitor`** is designed to be **local**. It operates on a *single node* within Spark's `LogicalPlan` tree. It's applied recursively to every node in the plan, and its job is to identify if that specific node represents a dataset it understands, without needing much context about the rest of the plan.

*   A **`DatasetBuilder`** is designed to be **global**. It typically operates on the entire `SparkListenerEvent`, which gives it access to the full `QueryExecution` object. This means it can see the original query, the optimized plan, the physical plan, and other metadata. This broad context allows it to handle complex scenarios where lineage isn't obvious from inspecting a single node in isolation.

Here's a table summarizing the key differences:

| Feature                  | `QueryPlanVisitor`                                                                          | `DatasetBuilder`                                                                                             |
| ------------------------ | ------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------ |
| **Input**                | A single `LogicalPlan` node.                                                                | The entire `SparkListenerEvent` (or the `QueryExecution` from it).                                           |
| **Scope**                | **Local**: Sees one tree node at a time.                                                    | **Global**: Sees the entire execution context.                                                               |
| **Typical Use Case**     | Simple, well-defined operations. E.g., reading a Hive table (`HiveTableRelation`) or writing to a file (`InsertIntoHadoopFsRelationCommand`). | Complex operations or newer APIs. E.g., DataSource V2 commands (`AppendData`, `MergeIntoTable`), where context is key. |
| **Invocation**           | Applied recursively to **every node** in the plan tree by a utility like `PlanUtils.visit()`. | Invoked **once per event** by a factory that decides which builder is appropriate for the overall plan.      |
| **Primary Responsibility** | Match a specific `LogicalPlan` class and extract its dataset information.                   | Analyze the entire `QueryExecution` to find and construct datasets, often for more intricate lineage patterns. |

### Example 1: `QueryPlanVisitor` in Action (`HiveTableRelationVisitor`)

-   **Goal**: Identify when a query reads from a Hive table (e.g., `SELECT * FROM my_hive_table;`).
-   **Relevant Node**: The `LogicalPlan` contains a `HiveTableRelation` node representing the read operation.

The `HiveTableRelationVisitor` extends `QueryPlanVisitor<HiveTableRelation, ...>`. When the plan traversal reaches the `HiveTableRelation` node, the visitor's `isDefinedAt` check passes automatically. Its `apply` method is then called, and it performs a **local** action: it looks only at the given node, extracts the table's name, and builds an `InputDataset`. It doesn't need to know anything else about the query.

### Example 2: `DatasetBuilder` in Action (`AppendDataDatasetBuilder`)

-   **Goal**: Identify when data is appended to a DataSource V2 table like Iceberg or Delta (e.g., `df.writeTo("v2_catalog.my_iceberg_table").append()`).
-   **Relevant Node**: The plan contains an `AppendData` node. However, this node alone may lack crucial metadata like the table's physical location or snapshot ID.

The `Spark3DatasetBuilderFactory` receives the entire Spark event, sees the `AppendData` operation, and decides to use the `AppendDataDatasetBuilder`. This builder performs a **global** analysis. It looks at the `AppendData` node and can also inspect its children—specifically, the `DataSourceV2Relation` node that holds the rich V2 catalog information. This allows it to build a far more detailed `OutputDataset`, including the table version, which would be impossible for a visitor looking at the `AppendData` node in isolation.

In summary, `QueryPlanVisitor`s are specialists for common, self-contained tasks, while `DatasetBuilder`s are general contractors who can see the whole blueprint, making them suitable for more complex and context-dependent construction projects.

## 3.3 Catalog of `QueryPlanVisitor` Implementations

`QueryPlanVisitor`s are the primary mechanism for extracting lineage. They are `PartialFunction`s that match a specific `LogicalPlan` node and convert it into a list of `InputDataset`s or `OutputDataset`s. The `OpenLineageRunEventBuilder` is responsible for walking the `LogicalPlan` and applying these visitors to each node.

Below is a catalog of some of the most important visitors, primarily located in `shared/src/main/java/io/openlineage/spark/agent/lifecycle/plan/` and version-specific packages like `spark3/`.

| Visitor Class Name                          | Target Logical Plan Node(s)                    | Purpose                                                                                                     | Dataset Type |
| ------------------------------------------- | ---------------------------------------------- | ----------------------------------------------------------------------------------------------------------- | ------------ |
| **`InsertIntoHadoopFsRelationVisitor`**     | `InsertIntoHadoopFsRelationCommand`            | Extracts output path, file format, and schema for data written to HDFS-compatible filesystems (e.g., Parquet, ORC, CSV). | `OutputDataset` |
| **`InsertIntoHiveTableVisitor`**            | `InsertIntoHiveTable`                          | Extracts the database, table name, and storage location for data being written to a Hive table.             | `OutputDataset` |
| **`CreateDataSourceTableAsSelectCommandVisitor`** | `CreateDataSourceTableAsSelectCommand`         | Handles `CREATE TABLE ... AS SELECT ...` (CTAS) operations for non-Hive tables. Extracts the new table's metadata. | `OutputDataset` |
| **`CreateHiveTableAsSelectCommandVisitor`** | `CreateHiveTableAsSelectCommand`               | Handles CTAS operations for Hive tables. Extracts the new Hive table's metadata.                             | `OutputDataset` |
| **`HiveTableRelationVisitor`**              | `HiveTableRelation`                            | Identifies a read from a Hive table, extracting its name and location.                                      | `InputDataset` |
| **`LogicalRelationDatasetBuilder`**         | `LogicalRelation`                              | A key input builder that handles reads from various file-based sources (Parquet, JSON, etc.) and JDBC tables.  | `InputDataset` |
| **`LoadDataCommandVisitor`**                | `LoadDataCommand`                              | Handles the `LOAD DATA ... INTO TABLE` SQL command, identifying both the source file and the target table. | `Input/Output` |
| **`AlterTableRenameCommandVisitor`**        | `AlterTableRenameCommand`                      | Captures table rename operations, emitting lineage that shows an existing table being "consumed" and a new one being "produced". | `Input/Output` |
| **`DropTableCommandVisitor`**               | `DropTableCommand`, `DropTable` (Spark 3)      | Captures when a table is dropped. This is used by some backends to mark a dataset as deleted.            | `OutputDataset` |


## 3.4 Catalog of `DatasetBuilder` Implementations

While `QueryPlanVisitor`s are applied to every node in the plan tree, `DatasetBuilder`s are slightly different. They are also `PartialFunction`s, but they are typically invoked with the entire `SparkListenerEvent` as input. This gives them access to the full `QueryExecution` context, allowing them to implement more complex or holistic logic than a simple node visitor. They are particularly important for Spark 3's DataSource V2 API.

| Builder Class Name                                    | Triggering Event/Object                                | Purpose                                                                                                                                                                                                    | Dataset Type |
| ----------------------------------------------------- | ------------------------------------------------------ | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------ |
| **`LogicalRelationDatasetBuilder`**                   | `LogicalPlan` node (`LogicalRelation`)                 | Though named a "builder," this functions as a critical visitor. It handles a generic `LogicalRelation` node and delegates to more specific handlers like `JdbcRelationHandler` or extracts file URIs for file-based sources. | `InputDataset` |
| **`SaveIntoDataSourceCommandVisitor`**                | `LogicalPlan` node (`SaveIntoDataSourceCommand`)       | A visitor that handles the generic `df.write.save()` command, extracting the output dataset from the underlying relation.                                                                                | `OutputDataset` |
| **`AppendDataDatasetBuilder`** (Spark 3+)             | `SparkListenerEvent` -> `AppendData` node               | Handles data appends in the DataSource V2 API. It can extract rich information from V2 sources like Iceberg and Delta Lake, including table versions.                                                    | `OutputDataset` |
| **`DataSourceV2RelationInputOnStartDatasetBuilder`** (Spark 3+) | `SparkListenerSQLExecutionStart` -> `DataSourceV2Relation` | Captures input datasets from DataSource V2 sources at the *start* of a query. This is important for sources where metadata might not be available later in the optimized plan.                               | `InputDataset` |
| **`MergeIntoCommandOutputDatasetBuilder`** (Spark 3+) | `SparkListenerEvent` -> `MergeIntoTable`               | Handles the output of a `MERGE INTO` command, which is a common operation in data warehouses and Lakehouse tables (Delta, Iceberg).                                                                    | `OutputDataset` |
| **`MergeIntoCommandInputDatasetBuilder`** (Spark 3+)  | `SparkListenerEvent` -> `MergeIntoTable`               | Handles the *input* or source table for a `MERGE INTO` command. It works in conjunction with the output builder to capture the full picture of the merge operation.                                        | `InputDataset` |

This combination of generic plan traversal with visitors and more powerful, event-aware builders gives the OpenLineage integration the flexibility to handle the wide variety of operations and data sources available in Apache Spark. 