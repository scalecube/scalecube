package io.servicefabric.cluster.fdetector;

import io.servicefabric.transport.TransportEndpoint;
import io.servicefabric.transport.TransportPipelineFactory;
import io.servicefabric.transport.Transport;

import io.servicefabric.transport.TransportSettings;
import rx.schedulers.Schedulers;

import java.util.Arrays;
import java.util.List;

public class FailureDetectorBuilder {
  final FailureDetector target;

  FailureDetectorBuilder(TransportEndpoint transportEndpoint, Transport tf) {
    target = new FailureDetector(transportEndpoint, Schedulers.from(tf.getEventExecutor()));
    target.setTransport(tf);
  }

  public FailureDetectorBuilder set(List<TransportEndpoint> members) {
    target.setClusterEndpoints(members);
    return this;
  }

  public FailureDetectorBuilder pingTime(int pingTime) {
    target.setPingTime(pingTime);
    return this;
  }

  public FailureDetectorBuilder pingTimeout(int pingTimeout) {
    target.setPingTimeout(pingTimeout);
    return this;
  }

  public FailureDetectorBuilder ping(TransportEndpoint member) {
    target.setPingMember(member);
    return this;
  }

  public FailureDetectorBuilder noRandomMembers() {
    target.setRandomMembers(Arrays.asList(new TransportEndpoint[0]));
    return this;
  }

  public FailureDetectorBuilder randomMembers(List<TransportEndpoint> members) {
    target.setRandomMembers(members);
    return this;
  }

  public FailureDetectorBuilder block(TransportEndpoint dest) {
    Transport tf = (Transport) target.getTransport();
    TransportPipelineFactory pf = tf.getPipelineFactory();
    pf.blockMessagesTo(dest);
    return this;
  }

  public FailureDetectorBuilder block(List<TransportEndpoint> members) {
    for (TransportEndpoint dest : members) {
      block(dest);
    }
    return this;
  }

  public FailureDetectorBuilder network(TransportEndpoint member, int lostPercent, int mean) {
    Transport tf = (Transport) target.getTransport();
    TransportPipelineFactory pf = tf.getPipelineFactory();
    pf.setNetworkSettings(member, lostPercent, mean);
    return this;
  }

  public FailureDetectorBuilder unblockAll() {
    Transport tf = (Transport) target.getTransport();
    TransportPipelineFactory pf = tf.getPipelineFactory();
    pf.unblockAll();
    return this;
  }

  public static FailureDetectorBuilder FDBuilder(TransportEndpoint transportEndpoint) {
    Transport transport = Transport.newInstance(transportEndpoint, TransportSettings.DEFAULT_WITH_NETWORK_EMULATOR);
    return new FailureDetectorBuilder(transportEndpoint, transport);
  }

  public static FailureDetectorBuilder FDBuilder(TransportEndpoint transportEndpoint, Transport tf) {
    return new FailureDetectorBuilder(transportEndpoint, tf);
  }

  public FailureDetector target() {
    return target;
  }

  public FailureDetectorBuilder init() {
    target.getTransport().start();
    target.start();
    return this;
  }
}
