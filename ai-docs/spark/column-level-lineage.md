# Column-Level Lineage in OpenLineage Spark Integration

This document provides a comprehensive overview of how column-level lineage is extracted and processed in the OpenLineage Spark integration. It explains the architecture, components, and end-to-end flow of column lineage information from Spark's logical plan to the OpenLineage event.

## 1. Introduction to Column-Level Lineage

Column-level lineage provides fine-grained information about how data flows between columns in different datasets. It answers questions like:
- Which input columns were used to create a specific output column?
- What transformations were applied to input columns to produce output columns?
- How are columns related across different datasets?

In the OpenLineage Spark integration, column-level lineage is:
- Enabled by default for Spark 3.x (not supported in Spark 2.x)
- Implemented as a dataset facet called `columnLineage`
- Extracted by analyzing Spark's logical plan

## 2. Architecture Overview

The column-level lineage extraction process is implemented as a separate module within the OpenLineage Spark integration. It follows a modular architecture with clear separation of concerns:

```
┌─────────────────────────────────────────────────────────────────┐
│                  OpenLineageRunEventBuilder                     │
└───────────────────────────────┬─────────────────────────────────┘
                                │
                                ▼
┌─────────────────────────────────────────────────────────────────┐
│                    ColumnLevelLineageUtils                      │
└───────────────────────────────┬─────────────────────────────────┘
                                │
                                ▼
┌─────────────────────────────────────────────────────────────────┐
│                   ColumnLevelLineageContext                     │
└───────────────────────────────┬─────────────────────────────────┘
                                │
                                ▼
┌─────────────────────────────────────────────────────────────────┐
│                   ColumnLevelLineageBuilder                     │
└───────────────────────────────┬─────────────────────────────────┘
                                │
                 ┌──────────────┴──────────────┐
                 │                             │
                 ▼                             ▼
┌────────────────────────────┐  ┌────────────────────────────────┐
│   OutputFieldsCollector    │  │     InputFieldsCollector       │
└────────────────┬───────────┘  └────────────────┬───────────────┘
                 │                               │
                 └───────────────┬───────────────┘
                                 │
                                 ▼
┌─────────────────────────────────────────────────────────────────┐
│              ExpressionDependencyCollector                      │
└───────────────────────────────┬─────────────────────────────────┘
                                │
                                ▼
┌─────────────────────────────────────────────────────────────────┐
│              Specialized Dependency Visitors                     │
│  (IcebergMergeIntoDependencyVisitor, UnionDependencyVisitor)    │
└─────────────────────────────────────────────────────────────────┘
```

### 2.1 Key Components

#### ColumnLevelLineageUtils

This is the entry point for column lineage extraction. It has two implementations:

1. **Shared Module Implementation**: Acts as a dispatcher that checks the Spark version and delegates to the appropriate version-specific implementation.
2. **Spark 3.x Implementation**: Contains the actual logic for extracting column lineage from Spark 3.x logical plans.

#### ColumnLevelLineageContext

Holds the state during lineage collection, including:
- The Spark event being processed
- The OpenLineage context
- The column lineage builder
- The dataset namespace resolver

#### ColumnLevelLineageBuilder

The core component that builds the column lineage facet. It:
- Stores input and output columns with their expression IDs
- Tracks dependencies between expressions
- Builds the final column lineage facet

#### OutputFieldsCollector

Collects output fields from the logical plan by:
- Extracting output expressions from the root of the plan
- Handling special cases for Aggregate, Project, and CreateTableAsSelect nodes
- Using custom collectors for specific cases

#### InputFieldsCollector

Collects input fields from the logical plan by:
- Extracting dataset identifiers from various relation types (JDBC, RDD, DataSourceV2, etc.)
- Mapping input fields to their expression IDs

#### ExpressionDependencyCollector

The workhorse of column lineage extraction. It:
- Traverses the logical plan to identify dependencies between expressions
- Handles different types of expressions (Aggregate, If, CaseWhen, etc.)
- Uses specialized visitors for specific operations

#### Specialized Dependency Visitors

Handle specific operations that require special treatment:
- `IcebergMergeIntoDependencyVisitor`: Handles Iceberg MERGE operations
- `UnionDependencyVisitor`: Handles UNION operations

## 3. End-to-End Process Flow

The column lineage extraction process follows these steps:

### 3.1 Initialization

1. The `OpenLineageRunEventBuilder` builds output datasets for a Spark event.
2. For each output dataset, it calls `ColumnLevelLineageUtils.buildColumnLineageDatasetFacet` with:
   - The Spark event
   - The OpenLineage context
   - The schema facet from the dataset

### 3.2 Version Dispatching

1. The shared `ColumnLevelLineageUtils` checks if the Spark version is 3.x.
2. If it's Spark 3.x, it uses reflection to call the Spark 3-specific implementation.
3. If it's Spark 2.x or there's an error, it returns an empty Optional.

### 3.3 Context Setup

1. The Spark 3-specific `ColumnLevelLineageUtils` creates a `ColumnLevelLineageContext` with:
   - The Spark event
   - The OpenLineage context
   - A new `ColumnLevelLineageBuilder` initialized with the schema facet
   - A dataset namespace resolver

### 3.4 Logical Plan Analysis

1. It gets the adjusted logical plan from the query execution.
2. It collects output fields using `OutputFieldsCollector.collect()`.
3. It collects input fields and expression dependencies using:
   - `ExpressionDependencyCollector.collect()`
   - `InputFieldsCollector.collect()`

### 3.5 Facet Building

1. It creates a new `ColumnLineageDatasetFacetBuilder`.
2. It builds the fields part of the facet using the collected information.
3. It builds the dataset dependencies part of the facet if enabled.
4. It returns the built facet if it has fields, otherwise an empty Optional.

### 3.6 Facet Integration

1. The `OpenLineageRunEventBuilder` adds the column lineage facet to the dataset facets map.
2. It builds a new output dataset with the merged facets.
3. The column lineage facet is included in the final OpenLineage event.

## 4. Column Lineage Extraction Details

### 4.1 Output Field Collection

The `OutputFieldsCollector` collects output fields by:

1. Getting output expressions from the root of the logical plan.
2. Handling special cases for different node types:
   - For `Aggregate`, it adds the aggregate expressions.
   - For `Project`, it adds the project list.
   - For `CreateTableAsSelect`, it recursively gets outputs from the query.
3. If no outputs are found at the root, it calls `CustomCollectorsUtils.collectOutputs` to handle custom cases.
4. If still no outputs are found, it recursively collects outputs from the children of the plan.

### 4.2 Input Field Collection

The `InputFieldsCollector` collects input fields by:

1. Traversing the logical plan to find leaf nodes.
2. Extracting dataset identifiers from different relation types:
   - `JDBCRelation`: Extracts from JDBC connections
   - `LogicalRDD`: Extracts from RDDs
   - `DataSourceV2Relation`: Extracts from DataSourceV2 sources
   - `CatalogTable`: Extracts from catalog tables
   - `HadoopFsRelation`: Extracts from Hadoop filesystem relations
3. Mapping input fields to their expression IDs.

### 4.3 Expression Dependency Collection

The `ExpressionDependencyCollector` collects dependencies between expressions by:

1. Traversing the logical plan to find expressions.
2. Handling different types of nodes:
   - `Project`: Processes project expressions
   - `Aggregate`: Processes aggregate expressions
   - `Filter`: Processes filter conditions
   - `Join`: Processes join conditions
   - `Sort`: Processes sort expressions
   - `Window`: Processes window expressions
3. For each expression, it traverses its children to find dependencies.
4. It handles special cases for different expression types:
   - `AggregateExpression`: For aggregation functions
   - `If`: For conditional expressions
   - `CaseWhen`: For case-when expressions
   - `WindowExpression`: For window functions
   - `Alias`: For aliased expressions
5. It uses specialized visitors for specific operations:
   - `IcebergMergeIntoDependencyVisitor`: For Iceberg MERGE operations
   - `UnionDependencyVisitor`: For UNION operations

### 4.4 Transformation Types

The column lineage facet includes information about the type of transformation applied to input columns:

- `DIRECT`: The input column directly contributes to the output column.
- `INDIRECT`: The input column indirectly contributes to the output column (e.g., through a filter or join).

Subtypes provide more specific information:
- `IDENTITY`: The input column is passed through unchanged.
- `TRANSFORMATION`: The input column is transformed (e.g., through a function).
- `AGGREGATION`: The input column is aggregated (e.g., through SUM, COUNT).
- `FILTER`: The input column is used in a filter condition.
- `JOIN`: The input column is used in a join condition.
- `SORT`: The input column is used in a sort expression.
- `WINDOW`: The input column is used in a window function.
- `CONDITIONAL`: The input column is used in a conditional expression.

## 5. Version-Specific Differences

Column-level lineage is only supported in Spark 3.x, not in Spark 2.x. The shared module's `ColumnLevelLineageUtils` checks the Spark version and delegates to the Spark 3-specific implementation if applicable.

The Spark 3-specific implementation is in the `spark3` module, while the shared module contains the version-agnostic code. This separation allows for clean handling of version-specific differences.

### 5.1 Spark 3-Specific Implementation Details

The Spark 3-specific implementation of `ColumnLevelLineageUtils` is responsible for orchestrating the column lineage extraction process. Here's a detailed look at how it works:

1. The main method `buildColumnLineageDatasetFacet` takes a Spark event, OpenLineage context, and schema facet as input and returns an Optional column lineage dataset facet.

2. It first checks if the query execution is present and has an optimized plan, and if the schema facet is not null. If any of these conditions are not met, it returns an empty Optional.

3. It creates a `ColumnLevelLineageContext` with:
   - The Spark event
   - The OpenLineage context
   - A new `ColumnLevelLineageBuilder` initialized with the schema facet
   - A `DatasetNamespaceCombinedResolver` for resolving dataset namespaces

4. It gets the adjusted logical plan from the query execution, handling the special case of `SaveIntoDataSourceCommand`.

5. It collects output fields using `OutputFieldsCollector.collect()`.

6. It collects inputs and expression dependencies using `collectInputsAndExpressionDependencies()`, which:
   - Calls `ExpressionDependencyCollector.collect()` to collect dependencies between expressions
   - Calls `InputFieldsCollector.collect()` to collect input fields
   - Handles dataset caching by checking for `InMemoryRelation` nodes in the plan

7. It builds the column lineage dataset facet and returns it if it has fields.

### 5.2 Handling Different Data Source Types

The `InputFieldsCollector` handles different data source types in different ways:

#### DataSource V1 Inputs (e.g., JDBC, CSV)

For DataSource V1 inputs like JDBC relations, the `InputFieldsCollector` extracts dataset identifiers directly from the relation:

1. For `JDBCRelation`, it:
   - Extracts SQL metadata using `JdbcSparkUtils.extractQueryFromSpark`
   - Gets the JDBC URL and properties
   - Maps the input tables from the SQL metadata to dataset identifiers using `JdbcDatasetUtils.getDatasetIdentifier`
   - Resolves the namespace using the context's namespace resolver

2. For `HadoopFsRelation` (used for CSV, Parquet, etc.), it:
   - Extracts paths from the relation's location
   - Converts each path to a dataset identifier using `PathUtils.fromURI`

#### DataSource V2 Sinks (e.g., BigQuery)

For DataSource V2 sinks like BigQuery, the `InputFieldsCollector` delegates to `DataSourceV2RelationDatasetExtractor`:

1. For `DataSourceV2Relation`, it calls `DataSourceV2RelationDatasetExtractor.getDatasetIdentifierExtended`, which:
   - Checks if the dataset has extension lineage using `ExtensionDataSourceV2Utils.hasExtensionLineage`
   - Checks if the dataset has query extension lineage using `ExtensionDataSourceV2Utils.hasQueryExtensionLineage`
   - If it has query extension lineage, it parses the SQL query from the table properties and extracts input tables
   - Otherwise, it tries to get the dataset identifier from the catalog and identifier

### 5.3 Potential Issues with Mixed Data Source Types

When a DataSource V1 input (like JDBC/CSV) is passed to a DataSource V2 sink (like BigQuery), there can be issues with the column lineage context not being properly transferred between them:

1. **Context Loss**: The context (specifically, the column lineage information) might not be properly passed between the DataSource V1 input and the DataSource V2 sink.

2. **Expression ID Mapping**: The expression IDs from the DataSource V1 input might not be properly mapped to the expression IDs in the DataSource V2 sink.

3. **Query Parsing**: If the DataSource V2 sink uses query extension lineage, it parses the SQL query from the table properties. If this query doesn't properly reference the DataSource V1 input, the column lineage information might be lost.

These issues can result in the columnLineage facet not being populated correctly when a DataSource V1 input is used with a DataSource V2 sink.

## 6. Examples

### 6.1 Simple SQL Query

For a simple SQL query like:

```sql
SELECT id, name, age + 10 AS adjusted_age
FROM users
WHERE status = 'active'
```

The column lineage would show:
- `id` depends on `users.id` (DIRECT, IDENTITY)
- `name` depends on `users.name` (DIRECT, IDENTITY)
- `adjusted_age` depends on `users.age` (DIRECT, TRANSFORMATION)
- All outputs indirectly depend on `users.status` (INDIRECT, FILTER)

### 6.2 Aggregation Query

For an aggregation query like:

```sql
SELECT dept_id, AVG(salary) AS avg_salary
FROM employees
GROUP BY dept_id
```

The column lineage would show:
- `dept_id` depends on `employees.dept_id` (DIRECT, IDENTITY)
- `avg_salary` depends on `employees.salary` (DIRECT, AGGREGATION)

### 6.3 Join Query

For a join query like:

```sql
SELECT e.id, e.name, d.name AS dept_name
FROM employees e
JOIN departments d ON e.dept_id = d.id
```

The column lineage would show:
- `id` depends on `employees.id` (DIRECT, IDENTITY)
- `name` depends on `employees.name` (DIRECT, IDENTITY)
- `dept_name` depends on `departments.name` (DIRECT, IDENTITY)
- All outputs indirectly depend on `employees.dept_id` and `departments.id` (INDIRECT, JOIN)

### 6.4 Iceberg MERGE Operation

For an Iceberg MERGE operation like:

```sql
MERGE INTO target t
USING source s
ON t.id = s.id
WHEN MATCHED THEN UPDATE SET t.value = s.value
WHEN NOT MATCHED THEN INSERT (id, value) VALUES (s.id, s.value)
```

The column lineage is handled by the `IcebergMergeIntoDependencyVisitor`, which extracts dependencies from the matched and not-matched clauses. It would show:
- `target.id` depends on `source.id` (DIRECT, IDENTITY) for not-matched case
- `target.value` depends on `source.value` (DIRECT, IDENTITY) for both matched and not-matched cases

## 7. Integration with OpenLineage Events

The column lineage facet is attached to output datasets in the OpenLineage event. The `OpenLineageRunEventBuilder` calls `ColumnLevelLineageUtils.buildColumnLineageDatasetFacet` for each output dataset and adds the resulting facet to the dataset facets map with the key "columnLineage".

The facet is then included in the final OpenLineage event, providing detailed information about how columns in the output dataset are derived from columns in the input datasets.

## 8. Troubleshooting Common Issues

### 8.1 DataSource V1 to DataSource V2 Lineage Issues

When using a DataSource V1 input (like JDBC or CSV) with a DataSource V2 sink (like BigQuery), you might encounter issues with column lineage not being properly captured. Here are some common problems and potential solutions:

#### Symptoms:
- The columnLineage facet is missing or empty in the OpenLineage event
- Column lineage is captured for some data sources but not for mixed DataSource V1 to DataSource V2 flows

#### Potential Causes:

1. **Context Loss**: The column lineage context is not being properly passed between the DataSource V1 input and the DataSource V2 sink.

2. **Expression ID Mapping**: The expression IDs from the DataSource V1 input are not being properly mapped to the expression IDs in the DataSource V2 sink.

3. **Query Parsing**: If the DataSource V2 sink uses query extension lineage, it might not be properly parsing the SQL query that references the DataSource V1 input.

#### Solutions:

1. **Check Query Properties**: If using a DataSource V2 sink with query extension lineage, ensure that the query in the table properties correctly references the DataSource V1 input.

2. **Verify Input Collection**: Debug the `InputFieldsCollector.collect()` method to ensure it's correctly extracting dataset identifiers from the DataSource V1 input.

3. **Trace Expression Dependencies**: Use logging to trace how expression dependencies are collected and mapped between the DataSource V1 input and the DataSource V2 sink.

4. **Examine Logical Plan**: Analyze the logical plan to understand how the DataSource V1 input is connected to the DataSource V2 sink and ensure that the connection is properly captured by the column lineage extraction process.

### 8.2 Example: JDBC to BigQuery

For a flow that reads from a JDBC source and writes to BigQuery:

```sql
-- Read from JDBC source
val jdbcDF = spark.read
  .format("jdbc")
  .option("url", "jdbc:postgresql://localhost:5432/mydb")
  .option("dbtable", "users")
  .option("user", "username")
  .option("password", "password")
  .load()

-- Write to BigQuery
jdbcDF.write
  .format("bigquery")
  .option("table", "mydataset.users")
  .save()
```

The column lineage extraction process should:

1. Extract dataset identifiers from the JDBC relation using `InputFieldsCollector.extractDatasetIdentifier(context, relation)`
2. Extract dataset identifiers from the BigQuery relation using `DataSourceV2RelationDatasetExtractor.getDatasetIdentifierExtended(context, relation)`
3. Collect expression dependencies between the JDBC input and BigQuery output using `ExpressionDependencyCollector.collect(context, plan)`
4. Map the expression IDs from the JDBC input to the expression IDs in the BigQuery output

If the columnLineage facet is not being populated, check if the expression IDs are being properly mapped between the JDBC input and BigQuery output, and if the context is being properly passed between them.

## 9. Conclusion

Column-level lineage in the OpenLineage Spark integration provides valuable insights into how data flows between columns in different datasets. It's implemented as a modular system that analyzes Spark's logical plan to extract dependencies between expressions and map them to input and output columns.

The architecture separates concerns into different components, making it easier to understand and maintain. The process flow is well-defined, from initialization to facet integration, ensuring that column lineage information is accurately captured and included in OpenLineage events.

By understanding how column-level lineage works in the OpenLineage Spark integration, users can better track and analyze data lineage at a fine-grained level, improving data governance and quality.

When working with mixed data source types, particularly DataSource V1 inputs and DataSource V2 sinks, special attention should be paid to how the column lineage context is passed between them to ensure accurate lineage information.