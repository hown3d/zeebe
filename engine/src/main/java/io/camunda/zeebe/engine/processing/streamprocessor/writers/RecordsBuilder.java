/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.camunda.zeebe.engine.processing.streamprocessor.writers;

import io.camunda.zeebe.util.buffer.BufferWriter;

/** Things that only a stream processor should write to the log stream (+ commands) */
public interface RecordsBuilder
    extends CommandsBuilder, EventsBuilder, RejectionsBuilder, BufferWriter {
  void configureSourceContext(long sourceRecordPosition);
}
