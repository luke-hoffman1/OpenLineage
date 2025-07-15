# OpenLineage Spark Integration: Project Structure️

This document provides a comprehensive overview of the `openlineage-spark` integration's project structure. The integration is designed as a modular, multi-project Gradle build to support various versions of Apache Spark and facilitate testing and deployment across different environments.

The core architectural principle is to separate shared logic from version-specific implementations. A `shared` module provides the foundational APIs, data models, and services, while version-specific modules (`spark2`, `spark3`, etc.) act as compatibility layers that adapt the core logic to the nuances of each Spark release.

---

## Core Agent Modules

The following modules represent the heart of the Spark integration, containing the primary logic for event listening, lineage processing, and data modeling.

### `integration/spark/shared`

This module contains the version-agnostic code that serves as the foundation for the entire integration. It provides the common interfaces, data structures, and core logic that all version-specific modules rely on.

* `src/main/java/io/openlineage/spark/`
  * `api/`: Defines the essential contracts and builders for creating OpenLineage objects. This includes abstract builders for jobs, datasets, and facets, ensuring consistent data construction across all Spark versions.
  * `agent/`: The core agent implementation, containing several key sub-packages:
    * `lifecycle/`: Manages the lifecycle of Spark applications and queries, handling how and when OpenLineage events are created and emitted.
    * `facets/`: Contains builders for all the standard OpenLineage `facets` (e.g., `DataSourceDatasetFacet`, `JobTypeJobFacet`) that enrich the core event data.
    * `models/`: Internal data models that represent Spark entities and job metadata.
    * `filters/`: Provides filtering logic to control which Spark events are processed, allowing for the exclusion of irrelevant or noisy events.
    * `util/`: A collection of common utility classes used throughout the integration. [Examples](utils.md)

### `integration/spark/app`

This module is the main entry point for the integration. It packages the core logic from the `shared` module and the appropriate version-specific module into a functional Spark agent.

* `src/main/java/io/openlineage/spark/agent/`
  * **`OpenLineageSparkListener`**: This is the most critical class. It hooks into the Spark execution lifecycle by implementing Spark's `QueryExecutionListener` and `SparkListener` interfaces. It listens for events like job start/end and query completion, triggering the lineage collection process.
  * **`EventEmitter`**: Responsible for serializing and sending the fully constructed OpenLineage events to a configured backend (e.g., a Marquis or an HTTP endpoint).
  * **`ArgumentParser`**: Parses OpenLineage configuration provided via Spark properties, enabling customization of the agent's behavior.

---

## Spark Version-Specific Modules

To support multiple versions of Apache Spark, the project uses dedicated modules that handle API differences between releases. These modules implement the abstract components and interfaces defined in the `shared` module.

**Modules:** `spark2`, `spark3`, `spark31`, `spark32`, etc.

The primary responsibility of these modules is to extract lineage information from Spark's `LogicalPlan` and other version-specific constructs.

* **Key Components:**
  * **Plan Visitors**: These are the workhorses of lineage extraction. They traverse Spark's `LogicalPlan` tree for a given query and identify the data sources (inputs) and targets (outputs). Each visitor is tailored to recognize a specific type of Spark operation (e.g., `CreateTableAsSelect`, `LoadDataCommand`).
  * **Dataset Builders**: Once a plan visitor identifies a data source, it uses a corresponding dataset builder to construct an OpenLineage `Dataset` object, populating it with details like the table name, schema, and underlying data source information.
  * **Column-Level Lineage**: Contains logic specific to extracting column-level lineage, mapping which input columns are used to generate which output columns. This logic is often highly dependent on the Spark version.
  * **Catalog Implementations**: Provides code for interacting with different catalog types (e.g., `Hive`, `JDBC`, `Delta Lake`) in a version-specific manner.

---

## Support and Deployment Modules

These modules provide the necessary tooling for testing, packaging, and deploying the Spark integration.

### `integration/spark/cli`

A command-line interface used to run a comprehensive suite of integration tests.

* **`configurable-test.sh`**: The main test execution script that can run the Spark integration with various user-defined configurations.
* **`spark-conf.yml` & `spark-conf-docker.yml`**: Template configuration files for running tests in local and Dockerized environments, respectively.
* **`Dockerfile`**: Defines a Docker image for creating a self-contained environment for testing the CLI.

### `integration/spark/databricks`

Contains scripts and documentation for seamlessly deploying the OpenLineage integration on Databricks clusters.

* **`open-lineage-init-script.sh`**: An initialization script that can be configured on a Databricks cluster to automatically set up the OpenLineage agent.
* **`upload-to-databricks.sh` & `.ps1`**: Utility scripts (for Bash and PowerShell) to simplify uploading the integration jars to Databricks.

### `integration/spark/docker`

Provides utility scripts to support Docker-based integration testing, particularly for coordinating services.

* **`init-db.sh`**: A script used to initialize a database schema within a Docker container, often used for testing JDBC integrations.
* **`wait-for-it.sh`**: A simple helper script to ensure that a dependent service (like a database or an OpenLineage backend) is running before starting the tests.