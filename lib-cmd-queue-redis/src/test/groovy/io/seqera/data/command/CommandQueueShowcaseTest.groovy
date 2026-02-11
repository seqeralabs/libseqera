/*
 * Copyright 2025, Seqera Labs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.seqera.data.command

import com.github.f4b6a3.tsid.TsidCreator
import io.micronaut.context.ApplicationContext
import spock.lang.Specification

import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * End-to-end showcase demonstrating how to use the command queue
 * for executing long-running tasks asynchronously.
 *
 * This specification shows:
 * - How to define commands with typed parameters and results
 * - How to implement command handlers for synchronous and asynchronous execution
 * - How to submit commands and track their status
 * - How to retrieve typed results after completion
 * - How to handle failures and cancellations
 */
class CommandQueueShowcaseTest extends Specification {

    private ApplicationContext createContext() {
        ApplicationContext.run(
            ['command-queue.checker.interval': '500ms'],
            'test'
        )
    }

    // =========================================================================
    // SHOWCASE 1: Simple Synchronous Command
    // =========================================================================

    /**
     * Demonstrates a simple command that executes immediately and returns a result.
     * Use this pattern for quick operations that don't need async processing.
     */
    def 'showcase: simple computation command'() {
        given: 'create a new application context and command service'
        def app = createContext()
        def commandService = app.getBean(CommandService)
        commandService.registerHandler(new ComputationHandler())
        commandService.start()

        and: 'create a command to compute factorial'
        def params = new ComputationParams(operation: 'factorial', value: 5)
        def command = new ComputationCommand(
            id: TsidCreator.getTsid().toLowerCase(),
            type: 'computation',
            params: params
        )

        when: 'submit the command'
        def commandId = commandService.submit(command)

        and: 'wait for processing'
        sleep(2000)

        then: 'command succeeds with the computed result'
        def state = commandService.getState(commandId).orElseThrow()
        state.status() == CommandStatus.SUCCEEDED

        and: 'retrieve the typed result'
        def result = commandService.getResult(commandId, ComputationResult).orElseThrow()
        result.value == 120  // 5! = 120
        result.operation == 'factorial'

        cleanup:
        commandService.stop()
        app.close()
    }

    // =========================================================================
    // SHOWCASE 2: Long-Running Async Command with Status Polling
    // =========================================================================

    /**
     * Demonstrates a long-running command that returns RUNNING status initially
     * and completes asynchronously. The command queue periodically checks status
     * until the command reaches a terminal state.
     *
     * Use this pattern for:
     * - External API calls that take time
     * - Batch processing jobs
     * - File uploads/downloads
     * - Any operation that may take seconds to minutes
     */
    def 'showcase: long-running async command with status polling'() {
        given: 'create a new application context and command service'
        def app = createContext()
        def commandService = app.getBean(CommandService)
        commandService.registerHandler(new DataProcessingHandler())
        commandService.start()

        and: 'create a command to process data'
        def params = new DataProcessingParams(
            datasetId: 'dataset-123',
            processingSteps: ['validate', 'transform', 'aggregate'],
            estimatedDurationMs: 2000
        )
        def command = new DataProcessingCommand(
            id: TsidCreator.getTsid().toLowerCase(),
            type: 'data-processing',
            params: params
        )

        when: 'submit the command'
        def commandId = commandService.submit(command)

        and: 'check status immediately'
        sleep(500)
        def initialState = commandService.getState(commandId).orElseThrow()

        then: 'command is in RUNNING state (async processing started)'
        initialState.status() == CommandStatus.RUNNING

        when: 'wait for async completion via periodic status checks'
        sleep(4000)
        def finalState = commandService.getState(commandId).orElseThrow()

        then: 'command completes successfully'
        finalState.status() == CommandStatus.SUCCEEDED

        and: 'result contains processing details'
        def result = commandService.getResult(commandId, DataProcessingResult).orElseThrow()
        result.datasetId == 'dataset-123'
        result.recordsProcessed > 0
        result.completedSteps == ['validate', 'transform', 'aggregate']

        cleanup:
        commandService.stop()
        app.close()
    }

    // =========================================================================
    // SHOWCASE 3: Command Failure Handling
    // =========================================================================

    /**
     * Demonstrates how command failures are handled and reported.
     * Failed commands have their error message stored for retrieval.
     */
    def 'showcase: command failure with error reporting'() {
        given: 'create a new application context and command service'
        def app = createContext()
        def commandService = app.getBean(CommandService)
        commandService.registerHandler(new ComputationHandler())
        commandService.start()

        and: 'create a command that will fail (division by zero)'
        def params = new ComputationParams(operation: 'divide', value: 0)
        def command = new ComputationCommand(
            id: TsidCreator.getTsid().toLowerCase(),
            type: 'computation',
            params: params
        )

        when: 'submit the command'
        def commandId = commandService.submit(command)

        and: 'wait for processing'
        sleep(2000)

        then: 'command fails with error message'
        def state = commandService.getState(commandId).orElseThrow()
        state.status() == CommandStatus.FAILED
        state.error() == 'Division by zero'

        and: 'no result is available'
        !commandService.getResult(commandId, ComputationResult).isPresent()

        cleanup:
        commandService.stop()
        app.close()
    }

    // =========================================================================
    // SHOWCASE 4: Command Cancellation
    // =========================================================================

    /**
     * Demonstrates cancelling a pending command before it starts processing.
     * Useful for user-initiated cancellations or timeout scenarios.
     */
    def 'showcase: cancel pending command'() {
        given: 'create a new application context and command service'
        def app = createContext()
        def commandService = app.getBean(CommandService)
        commandService.registerHandler(new DataProcessingHandler())
        commandService.start()

        and: 'create a command'
        def params = new DataProcessingParams(
            datasetId: 'dataset-456',
            processingSteps: ['step1'],
            estimatedDurationMs: 10000  // Long duration
        )
        def command = new DataProcessingCommand(
            id: TsidCreator.getTsid().toLowerCase(),
            type: 'data-processing',
            params: params
        )

        when: 'submit and immediately cancel'
        def commandId = commandService.submit(command)
        def cancelled = commandService.cancel(commandId)

        then: 'cancellation succeeds'
        cancelled

        and: 'command state shows cancelled'
        def state = commandService.getState(commandId).orElseThrow()
        state.status() == CommandStatus.CANCELLED

        cleanup:
        commandService.stop()
        app.close()
    }

    // =========================================================================
    // SHOWCASE 5: Multiple Command Types
    // =========================================================================

    /**
     * Demonstrates registering multiple handlers for different command types.
     * Each handler processes its specific command type independently.
     */
    def 'showcase: multiple command types processed concurrently'() {
        given: 'create a new application context and command service'
        def app = createContext()
        def commandService = app.getBean(CommandService)
        commandService.registerHandler(new ComputationHandler())
        commandService.registerHandler(new NotificationHandler())
        commandService.start()

        and: 'create commands of different types'
        def computeCmd = new ComputationCommand(
            id: TsidCreator.getTsid().toLowerCase(),
            type: 'computation',
            params: new ComputationParams(operation: 'square', value: 7)
        )
        def notifyCmd = new NotificationCommand(
            id: TsidCreator.getTsid().toLowerCase(),
            type: 'notification',
            params: new NotificationParams(
                recipient: 'user@example.com',
                message: 'Your job completed',
                channel: 'email'
            )
        )

        when: 'submit both commands'
        def computeId = commandService.submit(computeCmd)
        def notifyId = commandService.submit(notifyCmd)

        and: 'wait for processing'
        sleep(3000)

        then: 'both commands complete successfully'
        def computeState = commandService.getState(computeId).orElseThrow()
        computeState.status() == CommandStatus.SUCCEEDED

        def notifyState = commandService.getState(notifyId).orElseThrow()
        notifyState.status() == CommandStatus.SUCCEEDED

        and: 'each has its typed result'
        def computeResult = commandService.getResult(computeId, ComputationResult).orElseThrow()
        computeResult.value == 49  // 7^2

        def notifyResult = commandService.getResult(notifyId, NotificationResult).orElseThrow()
        notifyResult.delivered
        notifyResult.recipient == 'user@example.com'

        cleanup:
        commandService.stop()
        app.close()
    }

    // =========================================================================
    // SHOWCASE 6: Batch Command Submission
    // =========================================================================

    /**
     * Demonstrates submitting multiple commands and tracking them all.
     * Useful for batch processing scenarios.
     */
    def 'showcase: batch command submission and tracking'() {
        given: 'create a new application context and command service'
        def app = createContext()
        def commandService = app.getBean(CommandService)
        commandService.registerHandler(new ComputationHandler())
        commandService.start()

        and: 'create batch of commands'
        def commands = (1..5).collect { n ->
            new ComputationCommand(
                id: TsidCreator.getTsid().toLowerCase(),
                type: 'computation',
                params: new ComputationParams(operation: 'square', value: n)
            )
        }

        when: 'submit all commands'
        def commandIds = commands.collect { cmd ->
            commandService.submit(cmd)
        }

        and: 'wait for all to complete'
        sleep(5000)

        then: 'all commands succeeded'
        def results = commandIds.collect { id ->
            commandService.getResult(id, ComputationResult).orElseThrow()
        }
        results.collect { it.value } == [1, 4, 9, 16, 25]  // 1^2, 2^2, 3^2, 4^2, 5^2

        cleanup:
        commandService.stop()
        app.close()
    }
}

// =============================================================================
// COMMAND DEFINITIONS
// =============================================================================

/**
 * Parameters for computation commands.
 */
class ComputationParams {
    String operation
    int value

    ComputationParams() {}

    ComputationParams(Map args) {
        this.operation = args.operation
        this.value = args.value as int
    }
}

/**
 * Result of computation commands.
 */
class ComputationResult {
    String operation
    long value
    long computedAt

    ComputationResult() {}

    ComputationResult(String operation, long value) {
        this.operation = operation
        this.value = value
        this.computedAt = System.currentTimeMillis()
    }
}

/**
 * A computation command implementation.
 */
class ComputationCommand implements Command<ComputationParams> {
    String id
    String type = 'computation'
    ComputationParams params

    @Override String id() { id }
    @Override String type() { type }
    @Override ComputationParams params() { params }
}

/**
 * Handler for computation commands - executes synchronously.
 */
class ComputationHandler implements CommandHandler<ComputationParams, ComputationResult> {

    @Override
    String type() { 'computation' }

    @Override
    CommandResult<ComputationResult> execute(Command<ComputationParams> command) {
        def params = command.params()

        try {
            long result = switch (params.operation) {
                case 'factorial' -> factorial(params.value)
                case 'square' -> (long) params.value * params.value
                case 'divide' -> {
                    if (params.value == 0) throw new ArithmeticException('Division by zero')
                    yield ((long)100) / params.value
                }
                default -> throw new IllegalArgumentException("Unknown operation: ${params.operation}")
            }

            return CommandResult.success(new ComputationResult(params.operation, result))
        } catch (Exception e) {
            return CommandResult.failure(e.message)
        }
    }

    private static long factorial(int n) {
        if (n <= 1) return 1
        return n * factorial(n - 1)
    }
}

// =============================================================================
// DATA PROCESSING COMMAND (Long-Running Async)
// =============================================================================

/**
 * Parameters for data processing commands.
 */
class DataProcessingParams {
    String datasetId
    List<String> processingSteps
    long estimatedDurationMs

    DataProcessingParams() {}

    DataProcessingParams(Map args) {
        this.datasetId = args.datasetId
        this.processingSteps = args.processingSteps as List<String>
        this.estimatedDurationMs = args.estimatedDurationMs as long
    }
}

/**
 * Result of data processing commands.
 */
class DataProcessingResult {
    String datasetId
    int recordsProcessed
    List<String> completedSteps
    long durationMs

    DataProcessingResult() {}

    DataProcessingResult(String datasetId, int recordsProcessed, List<String> completedSteps, long durationMs) {
        this.datasetId = datasetId
        this.recordsProcessed = recordsProcessed
        this.completedSteps = completedSteps
        this.durationMs = durationMs
    }
}

/**
 * A data processing command implementation.
 */
class DataProcessingCommand implements Command<DataProcessingParams> {
    String id
    String type = 'data-processing'
    DataProcessingParams params

    @Override String id() { id }
    @Override String type() { type }
    @Override DataProcessingParams params() { params }
}

/**
 * Handler for data processing commands - executes asynchronously.
 * Returns RUNNING immediately, then checkStatus() is called periodically
 * until processing completes.
 */
class DataProcessingHandler implements CommandHandler<DataProcessingParams, DataProcessingResult> {

    // Simulates external job tracking (in real impl, this would be external API state)
    private final Map<String, Instant> jobStartTimes = new ConcurrentHashMap<>()

    @Override
    String type() { 'data-processing' }

    @Override
    CommandResult<DataProcessingResult> execute(Command<DataProcessingParams> command) {
        // Start async processing - record start time
        jobStartTimes.put(command.id(), Instant.now())

        // Return RUNNING - the periodic checker will call checkStatus()
        return CommandResult.running()
    }

    @Override
    CommandResult<DataProcessingResult> checkStatus(Command<DataProcessingParams> command, CommandState state) {
        def params = command.params()
        def startTime = jobStartTimes.get(command.id())

        if (startTime == null) {
            return CommandResult.failure('Job not found')
        }

        // Check if enough time has passed (simulating external job completion)
        def elapsed = Duration.between(startTime, Instant.now()).toMillis()

        if (elapsed >= params.estimatedDurationMs) {
            // Job complete - return result
            jobStartTimes.remove(command.id())

            def result = new DataProcessingResult(
                params.datasetId,
                1000,  // Simulated record count
                params.processingSteps,
                elapsed
            )
            return CommandResult.success(result)
        }

        // Still running
        return CommandResult.running()
    }
}

// =============================================================================
// NOTIFICATION COMMAND
// =============================================================================

/**
 * Parameters for notification commands.
 */
class NotificationParams {
    String recipient
    String message
    String channel

    NotificationParams() {}

    NotificationParams(Map args) {
        this.recipient = args.recipient
        this.message = args.message
        this.channel = args.channel
    }
}

/**
 * Result of notification commands.
 */
class NotificationResult {
    String recipient
    String channel
    boolean delivered
    String messageId

    NotificationResult() {}

    NotificationResult(String recipient, String channel, boolean delivered, String messageId) {
        this.recipient = recipient
        this.channel = channel
        this.delivered = delivered
        this.messageId = messageId
    }
}

/**
 * A notification command implementation.
 */
class NotificationCommand implements Command<NotificationParams> {
    String id
    String type = 'notification'
    NotificationParams params

    @Override String id() { id }
    @Override String type() { type }
    @Override NotificationParams params() { params }
}

/**
 * Handler for notification commands.
 */
class NotificationHandler implements CommandHandler<NotificationParams, NotificationResult> {

    @Override
    String type() { 'notification' }

    @Override
    CommandResult<NotificationResult> execute(Command<NotificationParams> command) {
        def params = command.params()

        // Simulate sending notification
        def messageId = "msg-${UUID.randomUUID().toString().substring(0, 8)}"

        def result = new NotificationResult(
            params.recipient,
            params.channel,
            true,
            messageId
        )

        return CommandResult.success(result)
    }
}

