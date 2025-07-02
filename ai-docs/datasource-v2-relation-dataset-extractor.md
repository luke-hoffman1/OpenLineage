# DataSourceV2RelationDatasetExtractor

## Overview

The `DataSourceV2RelationDatasetExtractor` is a utility class in the OpenLineage Spark 3 integration that extracts dataset information from Spark's `DataSourceV2Relation` objects. This class plays a crucial role in capturing lineage information from Spark 3's DataSource V2 API, which is a newer and more powerful API for interacting with data sources in Spark.

## Context in OpenLineage Architecture

As described in the [architecture overview](part1-architecture-overview.md), OpenLineage captures lineage information by intercepting Spark events and traversing the logical plan of Spark SQL queries. The [logical plan parsing](part3-logical-plan-parsing.md) document explains that OpenLineage uses two main types of extractors:

1. **QueryPlanVisitors**: Local scope, operate on a single node in the LogicalPlan
2. **DatasetBuilders**: Global scope, operate on the entire SparkListenerEvent

The `DataSourceV2RelationDatasetExtractor` is a utility class that supports multiple DatasetBuilders in extracting dataset information from `DataSourceV2Relation` objects. It's specifically designed for Spark 3's DataSource V2 API, which requires special handling because metadata might not be available in the optimized plan.

## Key Responsibilities

The `DataSourceV2RelationDatasetExtractor` has several key responsibilities:

1. **Extract Datasets**: The primary function is to extract OpenLineage datasets from `DataSourceV2Relation` objects, which represent tables or data sources in Spark 3's DataSource V2 API.

2. **Handle Extension Lineage**: The class can detect and process extension lineage information, which allows for custom lineage tracking in specialized data sources.

3. **Handle Query Extension Lineage**: It can extract lineage information from SQL queries embedded in the relation's properties.

4. **Identify Datasets**: It provides methods to extract dataset identifiers from relations, which are used to uniquely identify datasets in the lineage graph.

5. **Add Facets**: It enriches datasets with facets like schema, storage, catalog, and data source information.

## Usage in the OpenLineage Spark Integration

The `DataSourceV2RelationDatasetExtractor` is used by multiple dataset builders in the OpenLineage Spark 3 integration:

- **DataSourceV2RelationInputOnStartDatasetBuilder**: Extracts input datasets from DataSourceV2Relation objects at the start of a Spark job or SQL execution.
- **DataSourceV2RelationInputOnEndDatasetBuilder**: Extracts input datasets at the end of execution.
- **DataSourceV2RelationOutputDatasetBuilder**: Extracts output datasets.
- **DataSourceV2ScanRelationOnStartInputDatasetBuilder**: Handles the specialized DataSourceV2ScanRelation objects.
- **TableContentChangeDatasetBuilder**: Tracks changes to table content.
- **InputFieldsCollector**: Collects field information for column-level lineage.

## Key Methods

### extract

```
public static <D extends OpenLineage.Dataset> List<D> extract(
    DatasetFactory<D> datasetFactory, 
    OpenLineageContext context, 
    DataSourceV2Relation relation)
```

This method extracts datasets from a DataSourceV2Relation. It handles different types of relations:

1. Relations with query extension lineage (SQL queries)
2. Relations with extension lineage (custom lineage)
3. Standard catalog-based relations

For each type, it extracts the appropriate dataset identifiers and adds relevant facets like schema, storage, catalog, and data source information.

### getDatasetIdentifier

```
public static Optional<DatasetIdentifier> getDatasetIdentifier(
    OpenLineageContext context, 
    DataSourceV2Relation relation)
```

This method attempts to extract a dataset identifier from a DataSourceV2Relation. It handles cases where:

1. The relation has no identifier (tries to get it from the relation itself)
2. The relation has an identifier and catalog (uses PlanUtils3 to get the identifier)

### getDatasetIdentifierExtended

```
public static List<DatasetIdentifier> getDatasetIdentifierExtended(
    OpenLineageContext context, 
    DataSourceV2Relation relation)
```

This is an extended version of getDatasetIdentifier that returns a list of identifiers. It handles:

1. Relations with extension lineage
2. Relations with query extension lineage (parses SQL to extract table information)
3. Relations with standard catalog information

## Integration with Spark's DataSource V2 API

Spark 3 introduced the DataSource V2 API, which provides a more powerful and flexible way to interact with data sources. The `DataSourceV2RelationDatasetExtractor` is specifically designed to work with this API, extracting lineage information from:

1. **TableCatalog**: A catalog of tables that can be read or written.
2. **Table Properties**: Metadata about the table, which can include custom lineage information.
3. **Identifiers**: Names that uniquely identify tables in a catalog.

## Conclusion

The `DataSourceV2RelationDatasetExtractor` is a critical component in the OpenLineage Spark 3 integration, enabling the extraction of lineage information from Spark's modern DataSource V2 API. It supports multiple dataset builders and handles various types of relations, ensuring comprehensive lineage capture in Spark 3 applications.

By providing utility methods for dataset extraction and identification, it simplifies the implementation of dataset builders and ensures consistent handling of DataSourceV2Relation objects throughout the OpenLineage Spark integration.
