/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.camunda.zeebe.engine.processing.resource;

import io.camunda.zeebe.engine.processing.common.CommandDistributionBehavior;
import io.camunda.zeebe.engine.processing.streamprocessor.DistributedTypedRecordProcessor;
import io.camunda.zeebe.engine.processing.streamprocessor.writers.StateWriter;
import io.camunda.zeebe.engine.processing.streamprocessor.writers.TypedRejectionWriter;
import io.camunda.zeebe.engine.processing.streamprocessor.writers.TypedResponseWriter;
import io.camunda.zeebe.engine.processing.streamprocessor.writers.Writers;
import io.camunda.zeebe.engine.state.deployment.DeployedProcess;
import io.camunda.zeebe.engine.state.deployment.PersistedDecision;
import io.camunda.zeebe.engine.state.deployment.PersistedDecisionRequirements;
import io.camunda.zeebe.engine.state.immutable.DecisionState;
import io.camunda.zeebe.engine.state.immutable.ElementInstanceState;
import io.camunda.zeebe.engine.state.immutable.ProcessState;
import io.camunda.zeebe.protocol.impl.record.value.deployment.DecisionRecord;
import io.camunda.zeebe.protocol.impl.record.value.deployment.DecisionRequirementsRecord;
import io.camunda.zeebe.protocol.impl.record.value.deployment.ProcessRecord;
import io.camunda.zeebe.protocol.impl.record.value.resource.ResourceDeletionRecord;
import io.camunda.zeebe.protocol.record.RejectionType;
import io.camunda.zeebe.protocol.record.intent.DecisionIntent;
import io.camunda.zeebe.protocol.record.intent.DecisionRequirementsIntent;
import io.camunda.zeebe.protocol.record.intent.ProcessIntent;
import io.camunda.zeebe.protocol.record.intent.ResourceDeletionIntent;
import io.camunda.zeebe.stream.api.records.TypedRecord;
import io.camunda.zeebe.stream.api.state.KeyGenerator;
import io.camunda.zeebe.util.buffer.BufferUtil;
import java.util.Optional;

public class ResourceDeletionProcessor
    implements DistributedTypedRecordProcessor<ResourceDeletionRecord> {

  private final StateWriter stateWriter;
  private final TypedResponseWriter responseWriter;
  private final TypedRejectionWriter rejectionWriter;
  private final KeyGenerator keyGenerator;
  private final DecisionState decisionState;
  private final CommandDistributionBehavior commandDistributionBehavior;
  private final ProcessState processState;
  private final ElementInstanceState elementInstanceState;

  public ResourceDeletionProcessor(
      final Writers writers,
      final KeyGenerator keyGenerator,
      final DecisionState decisionState,
      final CommandDistributionBehavior commandDistributionBehavior,
      final ProcessState processState,
      final ElementInstanceState elementInstanceState) {
    stateWriter = writers.state();
    responseWriter = writers.response();
    rejectionWriter = writers.rejection();
    this.keyGenerator = keyGenerator;
    this.decisionState = decisionState;
    this.commandDistributionBehavior = commandDistributionBehavior;
    this.processState = processState;
    this.elementInstanceState = elementInstanceState;
  }

  @Override
  public void processNewCommand(final TypedRecord<ResourceDeletionRecord> command) {
    final var value = command.getValue();
    final long eventKey = keyGenerator.nextKey();
    stateWriter.appendFollowUpEvent(eventKey, ResourceDeletionIntent.DELETING, value);

    tryDeleteResources(command);

    stateWriter.appendFollowUpEvent(eventKey, ResourceDeletionIntent.DELETED, value);
    commandDistributionBehavior.distributeCommand(eventKey, command);
    responseWriter.writeEventOnCommand(eventKey, ResourceDeletionIntent.DELETING, value, command);
  }

  @Override
  public void processDistributedCommand(final TypedRecord<ResourceDeletionRecord> command) {
    final var value = command.getValue();
    stateWriter.appendFollowUpEvent(command.getKey(), ResourceDeletionIntent.DELETING, value);

    tryDeleteResources(command);

    stateWriter.appendFollowUpEvent(command.getKey(), ResourceDeletionIntent.DELETED, value);
    commandDistributionBehavior.acknowledgeCommand(command.getKey(), command);
  }

  @Override
  public ProcessingError tryHandleError(
      final TypedRecord<ResourceDeletionRecord> command, final Throwable error) {
    if (error instanceof final NoSuchResourceException exception) {
      rejectionWriter.appendRejection(command, RejectionType.NOT_FOUND, exception.getMessage());
      responseWriter.writeRejectionOnCommand(
          command, RejectionType.NOT_FOUND, exception.getMessage());
      return ProcessingError.EXPECTED_ERROR;
    } else if (error instanceof final ActiveProcessInstancesException exception) {
      rejectionWriter.appendRejection(command, RejectionType.INVALID_STATE, exception.getMessage());
      responseWriter.writeRejectionOnCommand(
          command, RejectionType.INVALID_STATE, exception.getMessage());
      return ProcessingError.EXPECTED_ERROR;
    }

    return ProcessingError.UNEXPECTED_ERROR;
  }

  private void tryDeleteResources(final TypedRecord<ResourceDeletionRecord> command) {
    final var value = command.getValue();

    final var processOptional =
        Optional.ofNullable(processState.getProcessByKey(value.getResourceKey()));
    if (processOptional.isPresent()) {
      deleteProcess(processOptional.get());
      return;
    }

    final var drgOptional = decisionState.findDecisionRequirementsByKey(value.getResourceKey());
    if (drgOptional.isPresent()) {
      deleteDecisionRequirements(drgOptional.get());
      return;
    }

    throw new NoSuchResourceException(value.getResourceKey());
  }

  private void deleteDecisionRequirements(final PersistedDecisionRequirements drg) {
    decisionState
        .findDecisionsByDecisionRequirementsKey(drg.getDecisionRequirementsKey())
        .forEach(this::deleteDecision);

    final var drgRecord =
        new DecisionRequirementsRecord()
            .setDecisionRequirementsId(BufferUtil.bufferAsString(drg.getDecisionRequirementsId()))
            .setDecisionRequirementsName(
                BufferUtil.bufferAsString(drg.getDecisionRequirementsName()))
            .setDecisionRequirementsVersion(drg.getDecisionRequirementsVersion())
            .setDecisionRequirementsKey(drg.getDecisionRequirementsKey())
            .setResourceName(BufferUtil.bufferAsString(drg.getResourceName()))
            .setChecksum(drg.getChecksum())
            .setResource(drg.getResource());

    stateWriter.appendFollowUpEvent(
        keyGenerator.nextKey(), DecisionRequirementsIntent.DELETED, drgRecord);
  }

  private void deleteDecision(final PersistedDecision persistedDecision) {
    final var decisionRecord =
        new DecisionRecord()
            .setDecisionId(BufferUtil.bufferAsString(persistedDecision.getDecisionId()))
            .setDecisionName(BufferUtil.bufferAsString(persistedDecision.getDecisionName()))
            .setVersion(persistedDecision.getVersion())
            .setDecisionKey(persistedDecision.getDecisionKey())
            .setDecisionRequirementsId(
                BufferUtil.bufferAsString(persistedDecision.getDecisionRequirementsId()))
            .setDecisionRequirementsKey(persistedDecision.getDecisionRequirementsKey());
    stateWriter.appendFollowUpEvent(keyGenerator.nextKey(), DecisionIntent.DELETED, decisionRecord);
  }

  private void deleteProcess(final DeployedProcess process) {
    // We don't add the checksum or resource in this event. The checksum is not easily available
    // and the resources are left out to prevent exceeding the maximum batch size.
    final var processRecord =
        new ProcessRecord()
            .setBpmnProcessId(process.getBpmnProcessId())
            .setVersion(process.getVersion())
            .setKey(process.getKey())
            .setResourceName(process.getResourceName());
    stateWriter.appendFollowUpEvent(keyGenerator.nextKey(), ProcessIntent.DELETING, processRecord);

    final var hasRunningInstances =
        elementInstanceState.hasActiveProcessInstances(process.getKey());

    if (!hasRunningInstances) {
      stateWriter.appendFollowUpEvent(keyGenerator.nextKey(), ProcessIntent.DELETED, processRecord);
    } else {
      throw new ActiveProcessInstancesException(process.getKey());
    }
  }

  private static final class NoSuchResourceException extends IllegalStateException {
    private static final String ERROR_MESSAGE_RESOURCE_NOT_FOUND =
        "Expected to delete resource but no resource found with key `%d`";

    private NoSuchResourceException(final long resourceKey) {
      super(String.format(ERROR_MESSAGE_RESOURCE_NOT_FOUND, resourceKey));
    }
  }

  private static final class ActiveProcessInstancesException extends IllegalStateException {
    private static final String ERROR_MESSAGE_RUNNING_INSTANCES =
        "Expected to delete resource with key `%d` but there are still running instances";

    private ActiveProcessInstancesException(final long processDefinitionKey) {
      super(String.format(ERROR_MESSAGE_RUNNING_INSTANCES, processDefinitionKey));
    }
  }
}
