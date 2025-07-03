# OpenLineage Java Client: Project Structure

This document provides a comprehensive overview of the OpenLineage Java client's project structure. The client is designed as a modular, multi-project Gradle build to support various transport mechanisms and facilitate testing and deployment across different environments.

The core architectural principle is to provide a flexible and extensible client for emitting OpenLineage events. The main `client/java` module provides the foundational APIs and data models, while transport-specific modules (`transports-s3`, `transports-kinesis`, etc.) act as adapters for different backends.

---

## Core Client Module

The following module represents the heart of the Java client, containing the primary logic for event creation, transport, and resilience patterns.

### `client/java`

This module contains the core, version-agnostic code that serves as the foundation for the entire client.

* `src/main/java/io/openlineage/client/`
  * **`OpenLineage.java`**: The main entry point and fluent builder for creating OpenLineage events, jobs, runs, and datasets.
  * **`OpenLineageClient.java`**: The primary client interface for emitting OpenLineage events.
  * **`Clients.java`**: A factory class for creating `OpenLineageClient` instances with various configurations.
  * **`OpenLineageConfig.java`**: A configuration class for the `OpenLineageClient`.
  * **`circuitBreaker/`**: Contains multiple `CircuitBreaker` implementations (e.g., `NoOp`, `Static`, `Timeout`) to gracefully handle backend failures.
  * **`dataset/` & `job/` & `run/`**: Contain classes for building and configuring datasets, jobs, and runs.
  * **`metrics/`**: Provides classes for metrics collection via Micrometer, including `MeterRegistry` factories.
  * **`transports/`**: Defines the core transport layer, including the main `Transport` interface and several built-in implementations (HTTP, Kafka, File, Console).
  * **`utils/`**: A collection of common utility classes for tasks like reflection, UUID generation, and parsing dataset identifiers.

---

## Transport-Specific Modules

To support multiple backends, the project uses dedicated modules that implement the `Transport` interface from the core module. This allows the client to send events to various destinations like cloud storage or streaming platforms.

**Modules:** `transports-s3`, `transports-gcs`, `transports-kinesis`, `transports-datazone`, `transports-gcplineage`

Each transport module follows a consistent pattern:

* **Transport Implementation**: A class that implements the `Transport` interface for a specific backend (e.g., `S3Transport`).
* **Transport Builder**: A class that implements `TransportBuilder` for creating and configuring the transport (e.g., `S3TransportBuilder`).
* **Transport Config**: A configuration class for transport-specific settings (e.g., `S3TransportConfig`).

---

## Support Modules

This section covers modules that provide auxiliary functionality, such as code generation.

### `client/java/generator`

This module contains a code generator for creating Java model classes directly from the official OpenLineage JSON schema.

* **`Generator.java`**: The main entry point for the code generator.
* **`JavaPoetGenerator.java`**: Uses the JavaPoet library to generate source code.
* **`SchemaParser.java`**: Parses the OpenLineage schema definition.

---

## Build and Configuration

The Java client uses **Gradle** for building and dependency management. Key build files include:

* **`build.gradle`**: The main build file for the core client.
* **`transports.build.gradle`**: Common build configuration shared by all transport modules.
* **`settings.gradle`**: Defines all projects and modules included in the build.

The client can be configured using a YAML file (an `openlineage.example.yml` is provided) or through environment variables.