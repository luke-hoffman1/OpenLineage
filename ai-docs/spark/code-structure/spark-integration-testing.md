# OpenLineage Spark Integration: Testing Framework

This document provides a comprehensive yet accessible overview of the testing framework used in the OpenLineage Spark integration. It outlines test organization, setup, execution patterns, dependency management, and best practices for writing new tests.

---

## 📘 Table of Contents

1. [Overview](#overview)
2. [Test Directory Structure](#test-directory-structure)
3. [Test Setup and Execution](#test-setup-and-execution)
4. [Dependency Management for Testing](#dependency-management-for-testing)
5. [Creating New Tests](#creating-new-tests)
6. [Conclusion](#conclusion)

---

## 🧭 Overview

This guide is intended for **contributors and developers** working on or extending the OpenLineage Spark integration test suite. Whether you're adding new functionality, fixing bugs, or reviewing changes, this guide explains how to understand, run, and write tests effectively.

---

## 🗂 Test Directory Structure

Tests live in:
`integration/spark/app/src/test/java/io/openlineage/spark/agent`

They are categorized as follows:

### ✅ Test Categories

| Category                    | Description                                  | Example Classes                                                           |
| --------------------------- | -------------------------------------------- | ------------------------------------------------------------------------- |
| **Integration Tests**       | Test integrations with external systems      | `GoogleCloudIntegrationTest`, `DatabricksIntegrationTest`                 |
| **Lifecycle Plan Visitors** | Validate lineage extraction from Spark plans | `CreateTableCommandVisitorTest`, `JDBCRelationVisitorTest`                |
| **Column-Level Lineage**    | Validate column-level tracking               | `ColumnLevelLineageDeltaTest`, `ColumnLineageWithTransformationTypesTest` |
| **Core Components**         | Unit tests for Spark agent logic             | `OpenLineageSparkListenerTest`, `ArgumentParserTest`                      |
| **Utilities**               | Shared helpers and test utilities            | `SparkTestUtils`, `MockServerUtils`, `RunEventVerifier`                   |

### 🧱 Base Test Classes

* `SparkContainerIntegrationTest`: Sets up containerized Spark environments
* `ConfigurableIntegrationTest`: Allows runtime test customization

---

## ⚙️ Test Setup and Execution

### 📦 Frameworks & Libraries

* **JUnit 5**: Core testing platform
* **AssertJ**: Fluent assertions
* **Mockito**: Mocking
* **Testcontainers**: Container orchestration
* **Awaitility**: Wait for async behavior

### 🧪 Common Test Patterns

* **Setup / Teardown**:

  ```java
  @BeforeAll / @BeforeEach
  @AfterEach / @AfterAll
  ```

* **Conditional Execution**:

   * `@EnabledIfSystemProperty`
   * `@EnabledIfEnvironmentVariable`
   * `@Tag`

* **MockServer Event Verification**:

  ```java
  List<RunEvent> events = MockServerUtils.getEventsEmitted(mockServer);
  verifyEvents(mockServer, replacements, "expectedStart.json", "expectedEnd.json");
  ```

* **Containerized Tests**:
  Uses Kafka, PostgreSQL, GCS, etc., through Testcontainers

---

### 🔍 Example: `GoogleCloudIntegrationTest`

Key elements:

* **Conditional execution**:

  ```java
  @Tag("google-cloud")
  @EnabledIfEnvironmentVariable(named = "CI", matches = "true")
  ```

* **Spark session configuration**:

  ```java
  spark = SparkSession.builder()
      .config("spark.openlineage.transport.url", "http://localhost:" + mockServer.getPort())
      .getOrCreate();
  ```

* **Event assertion**:

  ```java
  assertThat(events).isNotEmpty();
  ```

> ✅ **Best Practices**
>
> * Use appropriate tags
> * Verify events with known patterns
> * Clean up shared resources using `Spark4CompatUtils.cleanupAnyExistingSession()`

---

## 📦 Dependency Management for Testing

### Gradle Test Dependencies

From `integration/spark/app/build.gradle`:

```gradle
dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core")
    testImplementation("org.mockito:mockito-core")
    testImplementation("org.testcontainers:mockserver")
    testImplementation("org.awaitility:awaitility:4.3.0")
    testImplementation("org.apache.spark:spark-sql_${scala}:${spark}")
    // ... more dependencies ...
}
```

### 🔄 Version-Specific Logic

Dependencies adapt based on Spark version:

```gradle
List<Dependency> bigqueryDependencies(String spark, String scala) {
    return [
        "3.3.4": [dependencies.create("com.google.cloud.spark:spark-3.3-bigquery:${bigqueryVersion}")]
    ].get(spark, [])
}
```

### 🧪 Test Tasks

```gradle
tasks.named("test", Test.class) {
    useJUnitPlatform {
        excludeTags("integration-test")
    }
}

tasks.register("integrationTest", Test.class) {
    useJUnitPlatform {
        includeTags("integration-test")
    }
}
```

> 💡 Use `@Tag("delta")`, `@Tag("databricks")`, etc. to selectively run tests.

---

## ✍️ Creating New Tests

### 🧭 Pick the Right Type

| Test Type        | Purpose                               |
| ---------------- | ------------------------------------- |
| Unit Test        | Validate internal logic or utilities  |
| Integration Test | Verify behavior with external systems |
| Column-Level     | Verify column lineage and transforms  |

### 🏷 Use Tags & Conditions

```java
@Tag("integration-test")
@EnabledIfSystemProperty(named = "spark.version", matches = "3.3.4")
```

### 🧪 Setup Spark & MockServer

```java
@BeforeEach
public void setup() {
    spark = SparkSession.builder().getOrCreate();
    MockServerUtils.configureStandardExpectation(mockServer);
}
```

### ✅ Verify Events

```java
List<RunEvent> events = MockServerUtils.getEventsEmitted(mockServer);
assertThat(events).isNotEmpty();
```

### 🧹 Clean Up

```java
@AfterEach
public void teardown() {
    spark.stop();
    MockServerUtils.stopMockServer(mockServer);
}
```

### ⚠️ Handle Version Differences

```java
if (SparkContainerProperties.SPARK_VERSION.startsWith("3.")) {
    // Spark 3.x specific behavior
}
```

---

## ✅ Conclusion

The OpenLineage Spark test framework supports robust validation across Spark versions and external systems. It enables:

* **Tag-based test targeting** (`@Tag("aws")`, `@Tag("databricks")`, etc.)
* **Reusable test setup via base classes**
* **Version-aware dependency resolution**
* **MockServer verification of lineage output**

> 🔁 **Tip**: Use `./gradlew integrationTest -Dtags=google-cloud` to run scoped tests.

By following these structured patterns, contributors can confidently validate features, ensure integration correctness, and add new tests effectively.

---