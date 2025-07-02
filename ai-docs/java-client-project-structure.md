# OpenLineage Java Client: Project Structure

This document provides a comprehensive overview of the OpenLineage Java client's project structure. The client is designed as a modular, multi-project Gradle build to support various transport mechanisms and facilitate testing and deployment across different environments.

The core architectural principle is to provide a flexible and extensible client for emitting OpenLineage events to different backends. The main module provides the foundational APIs, data models, and services, while transport-specific modules act as adapters for different destination types.

---

## Core Client Module

The following module represents the heart of the Java client, containing the primary logic for event creation, transport, and circuit breaking.

### `client/java`

This module contains the core code that serves as the foundation for the entire client. It provides the common interfaces, data structures, and core logic that all transport-specific modules rely on.

* `src/main/java/io/openlineage/client/`
  * **`OpenLineage.java`**: The main entry point for creating OpenLineage events. It provides methods for creating jobs, runs, datasets, and other OpenLineage entities.
  * **`OpenLineageClient.java`**: The main client interface for emitting OpenLineage events.
  * **`Clients.java`**: Factory methods for creating OpenLineage clients with different configurations.
  * **`OpenLineageConfig.java`**: Configuration for the OpenLineage client.
  * **`Environment.java`**: Environment-specific configuration and utilities.
  * **`circuitBreaker/`**: Contains implementations of circuit breakers to handle failure scenarios:
    * Various circuit breaker implementations (NoOp, Static, Timeout, JavaRuntime, SimpleMemory, TaskQueue)
    * Circuit breaker configuration and factory classes
  * **`dataset/`**: Contains classes for building and managing datasets:
    * Dataset facet builders
    * Namespace utilities
  * **`job/`**: Contains classes for job configuration.
  * **`run/`**: Contains classes for run configuration.
  * **`metrics/`**: Contains classes for metrics collection and reporting:
    * Meter registry factories for different metrics backends
    * Micrometer integration
  * **`transports/`**: Contains the core transport interfaces and implementations:
    * **`Transport.java`**: The main interface for transporting OpenLineage events
    * **`TransportBuilder.java`**: Builder interface for creating transports
    * Various transport implementations (HTTP, Kafka, File, Console, etc.)
    * Transport configuration and factory classes
  * **`utils/`**: A collection of common utility classes used throughout the client:
    * Dataset identifier utilities
    * Reflection utilities
    * UUID utilities
    * Filesystem and JDBC utilities

---

## Transport-Specific Modules

To support multiple transport mechanisms, the project uses dedicated modules that implement the Transport interface for different backends.

**Modules:** `transports-s3`, `transports-gcs`, `transports-kinesis`, `transports-datazone`, `transports-gcplineage`

The primary responsibility of these modules is to implement the Transport interface for a specific backend.

* **Key Components:**
  * **Transport Implementation**: Each module provides a specific implementation of the Transport interface.
  * **Transport Builder**: A builder for creating and configuring the transport.
  * **Transport Config**: Configuration specific to the transport.

### Example: `client/java/transports-s3`

This module provides an implementation of the Transport interface for Amazon S3.

* `src/main/java/io/openlineage/client/transports/s3/`
  * **`S3Transport.java`**: Implementation of the Transport interface for S3.
  * **`S3TransportBuilder.java`**: Builder for creating and configuring the S3 transport.
  * **`S3TransportConfig.java`**: Configuration specific to the S3 transport.

---

## Support Modules

These modules provide additional functionality for the Java client.

### `client/java/generator`

A code generator for creating Java classes from the OpenLineage specification.

* `src/main/java/io/openlineage/client/`
  * **`Generator.java`**: The main entry point for the code generator.
  * **`JavaPoetGenerator.java`**: Uses JavaPoet to generate Java code.
  * **`SchemaParser.java`**: Parses the OpenLineage schema.
  * **`TypeResolver.java`**: Resolves types from the schema.

---

## Build and Configuration

The Java client uses Gradle for building and managing dependencies. The main build files are:

* **`build.gradle`**: The main build file for the core client.
* **`transports.build.gradle`**: Common build configuration for transport modules.
* **`settings.gradle`**: Project settings and module definitions.
* **`gradle.properties`**: Gradle and project properties.

The client can be configured using a YAML configuration file or environment variables. An example configuration is provided in `openlineage.example.yml`.