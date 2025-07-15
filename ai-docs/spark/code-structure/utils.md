### Key Utility Class: `PlanUtils.java`

The `util` package contains several helper classes, but the most significant is `PlanUtils`. This class is a central toolkit that provides a wide range of functionality used across the entire Spark integration. Its methods can be grouped into a few key areas of responsibility:

#### Visitor Composition and Resilient Execution

The agent's primary mechanism for analyzing Spark's `LogicalPlan` is a collection of "plan visitors" implemented as `PartialFunction`s. `PlanUtils` provides the logic to manage these visitors effectively.

* **`merge(Collection<PartialFunction> fns)`**: This is arguably the most important method in the class. It takes a list of individual plan visitors and combines them into a single, composite `PartialFunction`. The resulting function will try each visitor in sequence until it finds one that can handle the given `LogicalPlan` node. This allows for a clean, modular design where visitors for different Spark commands can be developed independently and then combined at runtime.
* **`safeIsDefinedAt(pfn, x)` and `safeApply(pfn, x)`**: These methods provide a layer of resilience to the agent. They execute the `isDefinedAt` and `apply` methods of a visitor within a `try-catch` block that traps a wide range of exceptions and errors (including `NoClassDefFoundError`). This ensures that a bug in a single visitor, or an issue arising from a missing optional dependency (e.g., a missing Iceberg library), will be logged without crashing the entire lineage collection process.

#### OpenLineage Facet & Schema Construction

`PlanUtils` acts as a bridge between Spark's internal data structures and the formal OpenLineage model by providing a set of factory methods.

* **`schemaFacet(StructType structType)`**: Translates a Spark `StructType` into a valid OpenLineage `SchemaDatasetFacet`. This method recursively handles complex and nested types, including structs, maps, and arrays, ensuring that the full data structure is represented in the lineage event.
* **`datasourceFacet(String namespaceUri)`**: Creates the `DatasourceDatasetFacet`, which identifies the underlying data source technology (e.g., `s3`, `hdfs`, `gs`) and the location of the data.
* **`parentRunFacet(...)`**: Constructs the `ParentRunFacet`, a critical component for linking job runs together to show causality and create an end-to-end data lineage graph across multiple Spark applications.

#### Path and Namespace Utilities

To create consistent and accurate dataset names, the class provides helpers for handling file paths and URIs.

* **`namespaceUri(URI uri)`**: Extracts a standardized namespace from a URI (e.g., generating `s3://my-bucket` from `s3://my-bucket/path/to/data`).
* **`getDirectoryPath(Path p, Configuration conf)`**: Normalizes a Hadoop `Path`. Many Spark data sources are directories of files (e.g., Parquet or ORC tables). This utility ensures that the agent can get the parent directory path, whether it's given a path to the directory itself or to a file within it.