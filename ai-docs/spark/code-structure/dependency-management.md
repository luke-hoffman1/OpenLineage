# Dependency Management in OpenLineage Spark Integration

This document provides an overview of how dependencies are managed in the OpenLineage Spark integration. Understanding these patterns is essential for contributors who want to add new features, fix bugs, or extend the integration to support new Spark versions or data sources.

## Project Structure

The OpenLineage Spark integration is organized as a multi-module Gradle project with the following key modules:

- **integration/spark**: The root project that aggregates all submodules
- **integration/spark/shared**: Contains common code used across all Spark versions
- **integration/spark/spark2, spark3, spark31, etc.**: Version-specific modules for different Spark releases
- **integration/spark/app**: The main entry point that brings everything together

## Scala Version Management

The project supports multiple Scala binary versions (primarily 2.12 and 2.13) using a custom Gradle plugin `io.openlineage.scala-variants`. This allows the same codebase to be compiled against different Scala versions, which is essential for Spark compatibility.

Key aspects of Scala version management:

- The default Scala binary version is defined in `gradle.properties` as `scala.binary.version` (default: 2.12)
- Each module uses `scalaVariants` block to define which Scala versions it supports
- Dependencies use Scala-specific configurations:
  - `scala212Implementation`, `scala212CompileOnly`, `testScala212Implementation`
  - `scala213Implementation`, `scala213CompileOnly`, `testScala213Implementation`
- The main build uses a configuration variable `activeRuntimeElementsConfiguration` (e.g., `scala212RuntimeElements`) to select the appropriate variant

## Spark Version Management

Each Spark version-specific module targets a specific Spark release:

| Module | Spark Version | Notes |
|--------|--------------|-------|
| shared | 3.2.4 | Base module with common code |
| spark2 | 2.4.8 | For Spark 2.x compatibility |
| spark3 | 3.2.4 | For Spark 3.0-3.2 compatibility |
| spark31 | 3.1.3 | Specific to Spark 3.1.x |
| spark32 | 3.2.4 | Specific to Spark 3.2.x |
| spark33 | 3.3.4 | Specific to Spark 3.3.x |
| spark34 | 3.4.3 | Specific to Spark 3.4.x |
| spark35 | 3.5.4 | Specific to Spark 3.5.x |
| spark40 | 4.0.0 | For Spark 4.0.x compatibility |

These versions are defined in `gradle.properties` with properties like `spark3.spark.version`.

## Dependency Types

The project uses several dependency scopes to manage how dependencies are included:

- **api**: Dependencies that are part of the public API and exposed to consumers
- **implementation**: Dependencies used internally but not exposed
- **compileOnly**: Dependencies available at compile time but not included in the final artifact
- **testFixturesApi/testImplementation**: Dependencies for testing

## Key Dependencies

### Core Dependencies

- **OpenLineage Java Client**: `io.openlineage:openlineage-java:${project.version}`
- **OpenLineage SQL Java**: `io.openlineage:openlineage-sql-java:${project.version}`
- **Spark Extension Entrypoint**: `io.openlineage:spark-extension-entrypoint:1.0.0`

### Spark Dependencies

Each module includes the appropriate Spark dependencies for its target version:

```gradle
compileOnly("org.apache.spark:spark-hive_${scalaBinaryVersion}:${sparkVersion}")
compileOnly("org.apache.spark:spark-sql_${scalaBinaryVersion}:${sparkVersion}")
compileOnly("org.apache.spark:spark-streaming_${scalaBinaryVersion}:${sparkVersion}")
```

These are marked as `compileOnly` because they're expected to be provided by the Spark environment at runtime.

### Data Source Integrations

The project includes dependencies for various data sources:

- **Delta Lake**: `io.delta:delta-core_${scalaBinaryVersion}:${deltaVersion}`
- **Apache Iceberg**: `org.apache.iceberg:iceberg-spark-runtime-3.2_${scalaBinaryVersion}:${icebergVersion}`
- **BigQuery**: `com.google.cloud.spark:spark-bigquery-with-dependencies_${scalaBinaryVersion}:${bigqueryVersion}`
- **Databricks**: `com.databricks:databricks-dbutils-scala_${scalaBinaryVersion}:${databricksVersion}`

These dependencies are typically marked as `compileOnly` to avoid version conflicts with the Spark environment.

## Dependency Conflict Management

The project uses several techniques to manage dependency conflicts:

1. **Exclusions**: Many dependencies exclude transitive dependencies that might conflict:
   ```gradle
   compileOnly("com.google.cloud.spark:spark-bigquery-with-dependencies_${scalaBinaryVersion}:${bigqueryVersion}") {
       exclude(group: "com.fasterxml.jackson.core")
       exclude(group: "com.fasterxml.jackson.module")
   }
   ```

2. **Resolution Strategy**: For specific problematic dependencies, the project uses resolution strategies:
   ```gradle
   configurations.configureEach {
       resolutionStrategy.eachDependency { DependencyResolveDetails details ->
           if (details.requested.group == "org.xerial.snappy" && details.requested.name == "snappy-java") {
               details.useVersion("[1.1.8.4,)")
           }
       }
   }
   ```

3. **Conditional Dependencies**: The app module uses functions to conditionally apply dependencies based on the Spark version:
   ```gradle
   addDependenciesToConfiguration(testSourceSet.implementationConfigurationName, spark, scala, this.&deltaDependencies)
   ```

## Building for Different Environments

The project supports building for different environments:

1. **Default Build**: Creates a JAR with all dependencies for the default Scala version
2. **Scala-Specific Builds**: Creates JARs for specific Scala versions
3. **Shadow JAR**: Creates a fat JAR with all dependencies included

## Common Pitfalls and Best Practices

### Pitfalls to Avoid

1. **Version Conflicts**: Be careful when adding new dependencies that might conflict with Spark's internal dependencies
2. **Scala Version Mismatch**: Ensure dependencies use the correct Scala binary version suffix
3. **Runtime vs. Compile-Time Dependencies**: Use `compileOnly` for dependencies that will be provided by Spark at runtime

### Best Practices

1. **Test with Multiple Spark Versions**: Changes should be tested against all supported Spark versions
2. **Minimize Dependencies**: Only add dependencies that are absolutely necessary
3. **Use the Shared Module**: Put common code in the shared module when possible
4. **Version-Specific Code**: Use the appropriate version-specific module for code that depends on Spark APIs that change between versions

## Extending the Integration

When adding support for a new data source or Spark feature:

1. Determine which Spark versions will be supported
2. Add dependencies to the appropriate modules
3. Implement the feature in the shared module if possible
4. For version-specific features, implement in the appropriate version-specific module
5. Update tests to verify the feature works across all supported Spark versions

## Conclusion

The OpenLineage Spark integration uses a sophisticated dependency management system to support multiple Spark and Scala versions. By understanding these patterns, contributors can effectively extend and maintain the integration while avoiding common pitfalls.