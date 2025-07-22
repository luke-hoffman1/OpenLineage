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

## 8. Conclusion

Column-level lineage in the OpenLineage Spark integration provides valuable insights into how data flows between columns in different datasets. It's implemented as a modular system that analyzes Spark's logical plan to extract dependencies between expressions and map them to input and output columns.

The architecture separates concerns into different components, making it easier to understand and maintain. The process flow is well-defined, from initialization to facet integration, ensuring that column lineage information is accurately captured and included in OpenLineage events.

By understanding how column-level lineage works in the OpenLineage Spark integration, users can better track and analyze data lineage at a fine-grained level, improving data governance and quality.