/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.0. You may not use this file
 * except in compliance with the Zeebe Community License 1.0.
 */
package io.zeebe.transport.impl;

import io.zeebe.dispatcher.FragmentHandler;
import io.zeebe.transport.impl.TransportChannel.ChannelLifecycleListener;
import java.nio.channels.SocketChannel;

public final class DefaultChannelFactory implements TransportChannelFactory {

  @Override
  public TransportChannel buildClientChannel(
      final ChannelLifecycleListener listener,
      final RemoteAddressImpl remoteAddress,
      final int maxMessageSize,
      final FragmentHandler readHandler) {
    return new TransportChannel(listener, remoteAddress, maxMessageSize, readHandler);
  }

  @Override
  public TransportChannel buildServerChannel(
      final ChannelLifecycleListener listener,
      final RemoteAddressImpl remoteAddress,
      final int maxMessageSize,
      final FragmentHandler readHandler,
      final SocketChannel media) {
    return new TransportChannel(listener, remoteAddress, maxMessageSize, readHandler, media);
  }
}
