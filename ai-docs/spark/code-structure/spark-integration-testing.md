# OpenLineage Spark Integration: Testing Framework

This document provides a comprehensive overview of the testing framework used in the OpenLineage Spark integration. It covers the test directory structure, test setup and execution patterns, dependency management for testing, and provides guidance for creating new tests.

## Test Directory Structure

The OpenLineage Spark integration tests are primarily located in the `integration/spark/app/src/test/java/io/openlineage/spark/agent` directory. The tests are organized into several categories:

### Test Categories

1. **Integration Tests**: Tests that verify the integration with specific technologies or services
   - `GoogleCloudIntegrationTest.java`: Tests integration with Google Cloud (BigQuery, GCS)
   - `SparkDeltaIntegrationTest.java`: Tests integration with Delta Lake
   - `SparkIcebergIntegrationTest.java`: Tests integration with Apache Iceberg
   - `EmrIntegrationTest.java`: Tests integration with AWS EMR
   - `DatabricksIntegrationTest.java`: Tests integration with Databricks

2. **Lifecycle Plan Visitor Tests**: Tests for specific plan visitors that extract lineage from Spark's logical plans
   - `AlterTableAddColumnsCommandVisitorTest.java`
   - `CreateTableCommandVisitorTest.java`
   - `DropTableCommandVisitorTest.java`
   - `JDBCRelationVisitorTest.java`
   - And many others for different Spark operations

3. **Column-Level Lineage Tests**: Tests for column-level lineage extraction
   - `ColumnLevelLineageDeltaTest.java`
   - `ColumnLevelLineageHiveTest.java`
   - `ColumnLevelLineageIcebergTest.java`
   - `ColumnLineageWithTransformationTypesTest.java`

4. **Core Component Tests**: Tests for core components of the OpenLineage Spark agent
   - `OpenLineageSparkListenerTest.java`: Tests the main Spark listener
   - `ArgumentParserTest.java`: Tests argument parsing
   - `SparkEventFilterTest.java`: Tests event filtering

5. **Utility Classes**: Helper classes for testing
   - `SparkTestUtils.java`: Common utilities for Spark tests
   - `MockServerUtils.java`: Utilities for working with the mock server
   - `RunEventVerifier.java`: Utilities for verifying OpenLineage events

### Base Test Classes

Several base classes provide common functionality for tests:

- `SparkContainerIntegrationTest.java`: Base class for integration tests using Testcontainers
- `ConfigurableIntegrationTest.java`: Base class for tests that can be configured externally

## Test Setup and Execution

### Test Framework

The OpenLineage Spark integration uses JUnit 5 (Jupiter) as its primary testing framework, along with several supporting libraries:

- **JUnit 5**: Core testing framework
- **AssertJ**: Fluent assertions library
- **Mockito**: Mocking framework
- **Testcontainers**: Library for creating Docker containers for tests
- **Awaitility**: Library for testing asynchronous operations

### Test Execution Patterns

Tests in the OpenLineage Spark integration follow several common patterns:

1. **Setup and Teardown**:
   - `@BeforeAll`: Initialize shared resources (e.g., mock server, Spark session)
   - `@BeforeEach`: Reset state before each test
   - `@AfterEach`: Clean up after each test
   - `@AfterAll`: Clean up shared resources

2. **Conditional Test Execution**:
   - `@EnabledIfSystemProperty`: Run tests only if a specific system property matches
   - `@EnabledIfEnvironmentVariable`: Run tests only if a specific environment variable is set
   - `@Tag`: Categorize tests for selective execution

3. **Containerized Testing**:
   - Many tests use Testcontainers to create isolated environments
   - Containers for Kafka, PostgreSQL, MockServer, etc.

4. **Event Verification**:
   - Tests emit OpenLineage events to a mock server
   - Events are captured and verified against expected patterns
   - `MockServerUtils.verifyEvents()` is commonly used to compare events

### Example: GoogleCloudIntegrationTest

The `GoogleCloudIntegrationTest` class provides a good example of how integration tests are structured:

```java
@Tag("integration-test")
@Tag("google-cloud")
@Slf4j
@EnabledIfEnvironmentVariable(named = "CI", matches = "true")
class GoogleCloudIntegrationTest {
    // Constants and configuration
    private static final String PROJECT_ID = /* ... */;
    private static final String BUCKET_NAME = /* ... */;
    private static final URI BUCKET_URI = /* ... */;
    
    // Test setup
    @BeforeAll
    public static void beforeAll() {
        Spark4CompatUtils.cleanupAnyExistingSession();
        mockServer = new ClientAndServer(MOCKSERVER_PORT);
        MockServerUtils.configureStandardExpectation(mockServer);
    }
    
    @BeforeEach
    public void beforeEach() {
        MockServerUtils.clearRequests(mockServer);
        spark = SparkSession.builder()
            // Configure Spark session for Google Cloud
            .config("spark.openlineage.transport.type", "http")
            .config("spark.openlineage.transport.url", "http://localhost:" + mockServer.getPort() + "/api/v1/lineage")
            // ... more configuration ...
            .getOrCreate();
    }
    
    // Test methods
    @Test
    @EnabledIfSystemProperty(named = SPARK_VERSION_PROPERTY, matches = SPARK_3_3)
    void testReadAndWriteFromBigquery() {
        // Setup test data
        String source_table = /* ... */;
        String target_table = /* ... */;
        
        // Execute Spark operations
        Dataset<Row> dataset = getTestDataset();
        dataset.write().format("bigquery").option("table", source_table).mode("overwrite").save();
        Dataset<Row> first = spark.read().format("bigquery").option("table", source_table).load();
        first.write().format("bigquery").option("table", target_table).mode("overwrite").save();
        
        // Verify OpenLineage events
        HashMap<String, String> replacements = new HashMap<>();
        // ... setup replacements ...
        
        List<RunEvent> events = MockServerUtils.getEventsEmitted(mockServer);
        assertThat(events).isNotEmpty();
        
        // Verify specific event patterns
        verifyEvents(mockServer, replacements, "pysparkBigquerySaveStart.json", "pysparkBigquerySaveEnd.json");
        
        // Additional assertions
        assertThat(events.stream()
            .map(event -> event.getJob().getName())
            .filter(name -> name.contains("spark-bigquery-local"))
            .distinct()
            .collect(Collectors.toList()))
        .isEmpty();
    }
    
    // Helper methods
    private static Dataset<Row> getTestDataset() {
        // Create test data
    }
}
```

Key aspects of this test:

1. **Tags**: The test is tagged as an integration test and specifically for Google Cloud
2. **Conditional Execution**: Only runs in CI environments and with specific Spark versions
3. **Setup**: Creates a mock server and configures a Spark session with Google Cloud settings
4. **Test Pattern**: 
   - Sets up test data
   - Executes Spark operations (read/write to BigQuery)
   - Captures and verifies OpenLineage events
5. **Verification**: Uses assertions to check that events match expected patterns

## Dependency Management for Testing

The OpenLineage Spark integration uses a sophisticated dependency management system to handle testing across different Spark and Scala versions.

### Build Configuration

The `build.gradle` file in the `integration/spark/app` module contains the configuration for test dependencies:

```gradle
dependencies {
    // Core testing frameworks
    testImplementation(platform("org.junit:junit-bom:${junit5Version}"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.junit.jupiter:junit-jupiter-params")
    testImplementation("org.assertj:assertj-core:${assertjVersion}")
    testImplementation("org.mockito:mockito-core:${mockitoVersion}")
    testImplementation("org.mockito:mockito-inline:${mockitoVersion}")
    testImplementation("org.mockito:mockito-junit-jupiter:${mockitoVersion}")
    
    // Container testing
    testImplementation(platform("org.testcontainers:testcontainers-bom:${testcontainersVersion}"))
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.testcontainers:mockserver")
    testImplementation("org.testcontainers:kafka")
    
    // Spark dependencies
    testImplementation("org.apache.spark:spark-core_${scala}:${spark}")
    testImplementation("org.apache.spark:spark-sql_${scala}:${spark}")
    testImplementation("org.apache.spark:spark-hive_${scala}:${spark}")
    testImplementation("org.apache.spark:spark-sql-kafka-0-10_${scala}:${spark}")
    
    // Other utilities
    testImplementation("org.awaitility:awaitility:4.3.0")
    testImplementation("org.postgresql:postgresql:${postgresqlVersion}")
    testImplementation("commons-beanutils:commons-beanutils:1.10.1")
}
```

### Conditional Dependencies

The build system uses functions to conditionally apply dependencies based on the Spark and Scala versions:

```gradle
addDependenciesToConfiguration(testSourceSet.implementationConfigurationName, spark, scala, this.&deltaDependencies)
addDependenciesToConfiguration(testSourceSet.implementationConfigurationName, spark, scala, this.&bigqueryDependencies)
addDependenciesToConfiguration(testSourceSet.implementationConfigurationName, spark, scala, this.&icebergDependencies)
```

These functions define version-specific dependencies:

```gradle
List<Dependency> bigqueryDependencies(String spark, String scala) {
    final def registry = [
        "3.2.4": [
            dependencies.create("com.google.cloud.spark:spark-3.2-bigquery:${bigqueryVersion}",
                { transitive = false }
            )
        ],
        "3.3.4": [
            dependencies.create("com.google.cloud.spark:spark-3.3-bigquery:${bigqueryVersion}",
                { transitive = false }
            )
        ],
        // ... more versions ...
    ];
    
    return registry.get(spark, [])
}
```

### Test Tasks

Different test tasks are defined for different types of tests:

```gradle
tasks.named("test", Test.class) {
    useJUnitPlatform {
        excludeTags("integration-test")
        if (!hasDeltaDependencies(spark, scala)) {
            excludeTags("delta")
        }
        if (!hasIcebergDependencies(spark, scala)) {
            excludeTags("iceberg")
        }
    }
    systemProperties = testSystemProperties(spark, scala)
}

tasks.register("integrationTest", Test.class) {
    useJUnitPlatform {
        includeTags("integration-test")
        excludeTags("configurable-integration-test")
        excludeTags("aws")
        excludeTags("databricks")
        // ... more configuration ...
    }
}

tasks.register("databricksIntegrationTest", Test) {
    useJUnitPlatform {
        includeTags("databricks")
    }
}

tasks.register("awsIntegrationTest", Test) {
    useJUnitPlatform {
        includeTags("aws")
    }
}
```

### System Properties

System properties are passed to tests to configure the test environment:

```gradle
def testSystemProperties = { String spark, String scala ->
    Map<String, String> openLineageSystemProperties = System.getProperties().findAll { key, value -> key.toString().startsWith("openlineage") }
    openLineageSystemProperties + [
        "spark.version": spark,
        "scala.binary.version": scala,
        "derby.system.home.base": derbySystemHomeBase.get().asFile.absolutePath,
        "spark.sql.warehouse.dir": sparkWarehouseDir.get().asFile.absolutePath,
        // ... more properties ...
    ]
}
```

## Creating New Tests

When creating new tests for the OpenLineage Spark integration, follow these guidelines:

### 1. Choose the Right Test Type

- **Unit Tests**: For testing individual components in isolation
- **Integration Tests**: For testing integration with external systems
- **Column-Level Lineage Tests**: For testing column-level lineage extraction

### 2. Use Appropriate Tags

Tag your tests to control when they run:

```java
@Tag("integration-test")  // For general integration tests
@Tag("aws")               // For AWS-specific tests
@Tag("databricks")        // For Databricks-specific tests
@Tag("delta")             // For Delta Lake tests
@Tag("iceberg")           // For Iceberg tests
```

### 3. Handle Conditional Execution

Use conditional annotations to control when tests run:

```java
@EnabledIfSystemProperty(named = "spark.version", matches = "3.3.4")
@EnabledIfEnvironmentVariable(named = "CI", matches = "true")
```

### 4. Set Up Test Environment

Follow the pattern of existing tests:

```java
@BeforeAll
public static void beforeAll() {
    // Set up shared resources
}

@BeforeEach
public void beforeEach() {
    // Set up per-test resources
    spark = SparkSession.builder()
        // Configure Spark session
        .getOrCreate();
}
```

### 5. Verify OpenLineage Events

Use the `MockServerUtils` to verify events:

```java
// Capture events
List<RunEvent> events = MockServerUtils.getEventsEmitted(mockServer);

// Verify events match expected patterns
verifyEvents(mockServer, replacements, "expectedStart.json", "expectedEnd.json");

// Make specific assertions
assertThat(events.stream()
    .map(event -> event.getJob().getName())
    .filter(name -> name.contains("specific-pattern"))
    .collect(Collectors.toList()))
.isNotEmpty();
```

### 6. Clean Up Resources

Always clean up resources in teardown methods:

```java
@AfterEach
public void afterEach() {
    // Clean up per-test resources
}

@AfterAll
public static void afterAll() {
    // Clean up shared resources
    Spark4CompatUtils.cleanupAnyExistingSession();
    MockServerUtils.stopMockServer(mockServer);
}
```

### 7. Handle Different Spark Versions

Be aware of version-specific behavior:

```java
if (SparkContainerProperties.SPARK_VERSION.startsWith("3.")) {
    // Spark 3.x specific code
} else if (SparkContainerProperties.SPARK_VERSION.startsWith("4.")) {
    // Spark 4.x specific code
}
```

## Conclusion

The OpenLineage Spark integration uses a comprehensive testing framework to ensure compatibility across different Spark and Scala versions, as well as integration with various data sources. By following the patterns established in existing tests, you can create effective tests for new features or bug fixes.

The test suite is organized to allow selective execution of tests based on tags and conditions, making it possible to run only the relevant tests for a specific change. The dependency management system ensures that tests have access to the appropriate libraries for the Spark and Scala versions being tested.