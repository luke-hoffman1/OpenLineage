# Part 2: Deep Dive into Listeners and Execution Contexts

This section dives into the state management and event handling core of the agent. We will explore the `OpenLineageSparkListener`, which serves as the primary entry point, and the `ExecutionContext` strategy, which is fundamental to how the agent accurately captures lineage for different types of Spark operations.

## 2.1 `OpenLineageSparkListener`

The `io.openlineage.spark.agent.OpenLineageSparkListener` is the heart of the integration. It's the class that directly plugs into Spark's `LiveListenerBus` and acts as the central hub for all incoming Spark events.

### Role and Responsibilities

The listener's primary responsibility is to intercept `SparkListenerEvent`s, interpret them, and delegate them to the correct state-managing object—an `ExecutionContext`. It doesn't contain much business logic itself; instead, it acts as a traffic controller, ensuring that events related to a specific SQL query or RDD job are handled by a consistent context.

### Handled Spark Events

The listener overrides several methods from `org.apache.spark.scheduler.SparkListener` to handle key moments in the Spark application lifecycle:

*   **`onApplicationStart(SparkListenerApplicationStart event)`**: This is the initialization trigger. As described in Part 1, this is where the `ContextFactory` and `EventEmitter` are created. It also kicks off the `SparkApplicationExecutionContext` to send the initial `START` event for the application run.
*   **`onApplicationEnd(SparkListenerApplicationEnd event)`**: Signals the completion of the Spark application. This event is delegated to the `SparkApplicationExecutionContext` to send the final `COMPLETE` event for the application run.
*   **`onSQLExecutionStart(SparkListenerSQLExecutionStart event)`**: This event marks the beginning of a Spark SQL query. The listener uses the `executionId` from the event to create or retrieve a dedicated `SparkSQLExecutionContext`. This context captures the `QueryExecution` object, which is vital for lineage as it contains the query's `LogicalPlan`.
    ```java
    // In OpenLineageSparkListener.java
    private void sparkSQLExecStart(SparkListenerSQLExecutionStart startEvent) {
      if (contextFactory != null) {
        // Attempt to create a new context for this SQL execution
        Optional<ExecutionContext> executionContext =
            contextFactory.createSparkSQLExecutionContext(startEvent.executionId());

        // If successful, register it and forward the event
        executionContext.ifPresent(
            (context) -> {
              sparkSqlExecutionRegistry.put(startEvent.executionId(), context);
              context.start(startEvent);
            });
      }
    }
    ```
*   **`onSQLExecutionEnd(SparkListenerSQLExecutionEnd event)`**: Marks the end of a Spark SQL query. The event is passed to the corresponding `SparkSQLExecutionContext`, which triggers the building and emission of the final lineage event (e.g., `COMPLETE` or `FAIL`).
*   **`onJobStart(SparkListenerJobStart jobStart)`**: Fired when a Spark Job (a set of tasks) begins. The listener inspects the job's properties to see if it's associated with an existing `SQLExecution`. If so, it passes the event to the `SparkSQLExecutionContext`. If not, it creates a new `RddExecutionContext` to handle this non-SQL job.
    ```java
    // In OpenLineageSparkListener.java
    @Override
    public void onJobStart(SparkListenerJobStart jobStart) {
        // ...
        // Try to find a SQL context first
        Optional<ExecutionContext> sqlContext = getSqlExecutionId(jobStart.properties())
          .flatMap(id -> getSparkSQLExecutionContext(Long.parseLong(id)));
        
        if (sqlContext.isPresent()) {
            sqlContext.get().start(jobStart);
        } else {
            // If no SQL context, it must be an RDD-level job
            ExecutionContext hContext =
                rddExecutionRegistry.computeIfAbsent(
                    jobStart.jobId(), (jobId) -> contextFactory.createRddExecutionContext(jobId));
            hContext.start(jobStart);
        }
    }
    ```
*   **`onJobEnd(SparkListenerJobEnd jobEnd)`**: Fired when a Spark Job completes. This is a crucial event, as it's often the primary trigger for building and emitting lineage for both SQL and RDD contexts. A single SQL query can spawn multiple Spark jobs, and this event helps the context track their completion.
*   **`onTaskEnd(SparkListenerTaskEnd taskEnd)`**: This event is used to collect quantitative metrics about a job's execution. The `JobMetricsHolder` accumulates information from each task, such as the number of records and bytes written, which is later used by the `OutputStatisticsOutputDatasetFacetBuilder`.
*   **`onOtherEvent(SparkListenerEvent event)`**: A catch-all for other events. This is notably used to support older Spark versions where the `onSQLExecutionStart` and `onSQLExecutionEnd` methods don't exist on the listener interface. The agent uses reflection to check if the event is of the expected SQL type and handles it accordingly.

### Managing Execution Contexts

The listener maintains several static registries (`Map`s) to track active execution contexts:
*   `sparkSqlExecutionRegistry`: Maps a SQL `executionId` to its `SparkSQLExecutionContext`.
*   `rddExecutionRegistry`: Maps a `jobId` to its `RddExecutionContext`.

When an event arrives, the listener uses the IDs within the event to look up the correct context from these registries. If a context doesn't exist (e.g., on a `JobStart` for a new SQL query), it uses its `ContextFactory` instance to create a new one. This ensures that all events for a given execution are handled by the same stateful object.

## 2.2 The `ExecutionContext` Strategy

An `ExecutionContext` is a state machine responsible for managing the lifecycle of a single, logical unit of work within Spark. It holds the `OpenLineageRunEventBuilder` and all the necessary context (like the `LogicalPlan` or RDDs) to produce a valid OpenLineage event. This contextual separation is critical for accurately mapping lineage to the correct jobs and queries.

### `ExecutionContext` Interface

The `io.openlineage.spark.agent.lifecycle.ExecutionContext` interface defines the contract for these state machines. It includes methods for handling the start and end of applications, jobs, stages, and SQL executions.

### Implementations

There are three primary implementations of this interface:

#### 1. `SparkApplicationExecutionContext`
*   **Scope**: Manages the lifecycle of the entire Spark application.
*   **Responsibility**: Its main job is to emit the top-level `START` and `COMPLETE` events for the application itself. It creates a top-level `runId` and `jobName` for the application. The run information is then used to populate the `parentRun` facet in all subsequent job-level events, creating a clear hierarchy in the lineage backend. It does not process logical plans or generate fine-grained lineage.

#### 2. `SparkSQLExecutionContext`
*   **Scope**: Manages the lifecycle of a single Spark SQL query execution, identified by its `executionId`.
*   **Responsibility**: This is the most important and complex context.
    *   **Stateful Tracking**: It tracks the state of a query from `SparkListenerSQLExecutionStart` to `SparkListenerSQLExecutionEnd`, including all `SparkListenerJobStart`/`End` events that occur in between. It uses a series of boolean flags (`emittedOnJobStart`, `emittedOnSqlExecutionEnd`, etc.) to ensure events are sent only once and at the correct time.
    *   **Logical Plan Capture**: Its most critical function is to capture the `QueryExecution` object provided in the `start` event. This object is the gateway to the `LogicalPlan`, which is the abstract syntax tree representing the user's query. This plan is what the visitors will later parse.
    *   **Event Orchestration**: It is responsible for invoking the `OpenLineageRunEventBuilder` at the correct time. The lineage event for a SQL query is typically sent when the *first* `onJobEnd` event is received for that query. This is because the optimized logical plan is reliably available at this point, whereas waiting for `onSQLExecutionEnd` can sometimes be too late.
        ```java
        // In SparkSQLExecutionContext.java
        @Override
        public void end(SparkListenerJobEnd jobEnd) {
          if (olContext.getQueryExecution().isEmpty()) {
            return; // QueryExecution not yet available
          }
          if (finished.get()) {
            return; // Event has already been sent
          }
          if (activeJobId == null || activeJobId != jobEnd.jobId()) {
            return; // Not the job we are tracking
          }
          
          // Use a lock to ensure only one thread can emit the event
          if (finished.compareAndSet(false, true)) {
            // Trigger the builder and emitter
            runEventBuilder.build(
                olContext,
                new OpenLineageRunEventContext(jobEnd, olContext.getQueryExecution().get()));
            
            // Clean up this context from the listener's registry
            OpenLineageSparkListener.removeSparkSQLExecutionContext(executionId);
          }
        }
        ```
    *   **SQL Facet**: It is also responsible for creating the `SQLJobFacet`, which includes the normalized SQL query text itself.

#### 3. `RddExecutionContext`
*   **Scope**: Manages a Spark Job that was not initiated via the Spark SQL interface.
*   **Responsibility**: This context serves as a fallback for lineage from lower-level RDD operations.
    *   **RDD Traversal**: Since there is no `LogicalPlan`, this context attempts to derive lineage by traversing the RDD dependency graph. It starts from the `finalRDD` of the `ResultStage` in the active job.
        ```java
        // In RddExecutionContext.java
        public void setActiveJob(ActiveJob activeJob) {
          // ...
          RDD<?> finalRdd = activeJob.finalStage().rdd();
          this.inputs = findInputs(finalRdd.getDependencies());
          this.outputs = findOutputs(finalRdd, finalRdd.sparkContext().hadoopConfiguration());
          // ...
        }
        ```
    *   **Path Extraction**: It uses helper utilities like `RddPathUtils.findInputs` to recursively inspect RDDs (e.g., `HadoopRDD`, `MapPartitionsRDD`) and identify file-based input/output paths. The resulting lineage is generally less precise than what can be extracted from a `LogicalPlan` but is crucial for capturing lineage from older or more imperative Spark code. 