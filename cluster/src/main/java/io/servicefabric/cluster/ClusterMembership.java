package io.servicefabric.cluster;

import static com.google.common.base.Preconditions.checkArgument;
import static io.servicefabric.cluster.ClusterMemberStatus.SHUTDOWN;
import static io.servicefabric.cluster.ClusterMemberStatus.TRUSTED;
import static io.servicefabric.cluster.ClusterMembershipDataUtils.filterData;
import static io.servicefabric.cluster.ClusterMembershipDataUtils.gossipFilterData;
import static io.servicefabric.cluster.ClusterMembershipDataUtils.syncGroupFilter;
import static io.servicefabric.transport.TransportAddress.tcp;

import io.servicefabric.cluster.fdetector.FailureDetectorEvent;
import io.servicefabric.cluster.fdetector.IFailureDetector;
import io.servicefabric.cluster.gossip.IManagedGossipProtocol;
import io.servicefabric.transport.ITransport;
import io.servicefabric.transport.TransportAddress;
import io.servicefabric.transport.TransportEndpoint;
import io.servicefabric.transport.TransportHeaders;
import io.servicefabric.transport.TransportMessage;
import io.servicefabric.transport.protocol.Message;

import com.google.common.base.Preconditions;
import com.google.common.base.Splitter;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import rx.Observable;
import rx.Scheduler;
import rx.Subscriber;
import rx.Subscription;
import rx.functions.Action0;
import rx.functions.Action1;
import rx.functions.Func1;
import rx.observers.Subscribers;
import rx.subjects.PublishSubject;
import rx.subjects.SerializedSubject;
import rx.subjects.Subject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.annotation.Nonnull;

public final class ClusterMembership implements IManagedClusterMembership, IClusterMembership {

  private static final Logger LOGGER = LoggerFactory.getLogger(ClusterMembership.class);

  // qualifiers
  private static final String SYNC = "io.servicefabric.cluster/membership/sync";
  private static final String SYNC_ACK = "io.servicefabric.cluster/membership/syncAck";

  // filters
  private static final TransportHeaders.Filter SYNC_FILTER = new TransportHeaders.Filter(SYNC);
  private static final Func1<Message, Boolean> GOSSIP_MEMBERSHIP_FILTER = new Func1<Message, Boolean>() {
    @Override
    public Boolean call(Message message) {
      return message.data() != null && ClusterMembershipData.class.equals(message.data().getClass());
    }
  };

  private IFailureDetector failureDetector;
  private IManagedGossipProtocol gossipProtocol;
  private int syncTime = 10 * 1000;
  private int syncTimeout = 3 * 1000;
  private int maxSuspectTime = 60 * 1000;
  private int maxShutdownTime = 60 * 1000;
  private String syncGroup = "default";
  private List<TransportAddress> seedMembers = new ArrayList<>();
  private ITransport transport;
  private final TransportEndpoint localEndpoint;
  private final Scheduler scheduler;
  private volatile Subscription cmTask;
  private TickingTimer timer;
  private AtomicInteger periodNbr = new AtomicInteger();
  private ClusterMembershipTable membership = new ClusterMembershipTable();
  @SuppressWarnings("unchecked")
  private Subject<ClusterMember, ClusterMember> subject = new SerializedSubject(PublishSubject.create());
  private Map<String, String> localMetadata = new HashMap<>();

  /** Merges incoming SYNC data, merges it and sending back merged data with SYNC_ACK. */
  private Subscriber<TransportMessage> onSyncSubscriber = Subscribers.create(new Action1<TransportMessage>() {
    @Override
    public void call(TransportMessage transportMessage) {
      ClusterMembershipData data = (ClusterMembershipData) transportMessage.message().data();
      List<ClusterMember> updates = membership.merge(data);
      TransportEndpoint endpoint = transportMessage.endpoint();
      if (!updates.isEmpty()) {
        LOGGER.debug("Received Sync from {}, updates: {}", endpoint, updates);
        processUpdates(updates, true/* spread gossip */);
      } else {
        LOGGER.debug("Received Sync from {}, no updates", endpoint);
      }
      String correlationId = transportMessage.message().header(TransportHeaders.CORRELATION_ID);
      ClusterMembershipData syncAckData = new ClusterMembershipData(membership.asList(), syncGroup);
      Message message =
          new Message(syncAckData, TransportHeaders.QUALIFIER, SYNC_ACK, TransportHeaders.CORRELATION_ID, correlationId);
      transport.send(endpoint, message);
    }
  });

  /** Merges FD updates and processes them. */
  private Subscriber<FailureDetectorEvent> onFdSubscriber = Subscribers.create(new Action1<FailureDetectorEvent>() {
    @Override
    public void call(FailureDetectorEvent input) {
      List<ClusterMember> updates = membership.merge(input);
      if (!updates.isEmpty()) {
        LOGGER.debug("Received FD event {}, updates: {}", input, updates);
        processUpdates(updates, true/* spread gossip */);
      }
    }
  });

  /**
   * Merges gossip's {@link ClusterMembershipData} (not spreading gossip further).
   */
  private Subscriber<ClusterMembershipData> onGossipSubscriber = Subscribers
      .create(new Action1<ClusterMembershipData>() {
        @Override
        public void call(ClusterMembershipData data) {
          List<ClusterMember> updates = membership.merge(data);
          if (!updates.isEmpty()) {
            LOGGER.debug("Received gossip, updates: {}", updates);
            processUpdates(updates, false/* spread gossip */);
          }
        }
      });

  ClusterMembership(TransportEndpoint localEndpoint, Scheduler scheduler) {
    this.localEndpoint = localEndpoint;
    this.scheduler = scheduler;
  }

  public void setFailureDetector(IFailureDetector failureDetector) {
    this.failureDetector = failureDetector;
  }

  public void setGossipProtocol(IManagedGossipProtocol gossipProtocol) {
    this.gossipProtocol = gossipProtocol;
  }

  public void setSyncTime(int syncTime) {
    this.syncTime = syncTime;
  }

  public void setSyncTimeout(int syncTimeout) {
    this.syncTimeout = syncTimeout;
  }

  public void setMaxSuspectTime(int maxSuspectTime) {
    this.maxSuspectTime = maxSuspectTime;
  }

  public void setMaxShutdownTime(int maxShutdownTime) {
    this.maxShutdownTime = maxShutdownTime;
  }

  public void setSyncGroup(String syncGroup) {
    this.syncGroup = syncGroup;
  }

  public void setSeedMembers(Collection<TransportAddress> seedMembers) {
    Set<TransportAddress> set = new HashSet<>(seedMembers);
    set.remove(localEndpoint.address());
    this.seedMembers = new ArrayList<>(set);
  }

  public void setSeedMembers(String seedMembers) {
    List<TransportAddress> memberList = new ArrayList<>();
    for (String token : new HashSet<>(Splitter.on(',').splitToList(seedMembers))) {
      if (token.length() != 0) {
        try {
          memberList.add(tcp(token));
        } catch (IllegalArgumentException e) {
          LOGGER.warn("Skipped setting wellknown_member, caught: " + e);
        }
      }
    }
    // filter accidental duplicates/locals
    Set<TransportAddress> set = new HashSet<>(memberList);
    for (Iterator<TransportAddress> i = set.iterator(); i.hasNext();) {
      TransportAddress endpoint = i.next();
      String hostAddress = localEndpoint.address().hostAddress();
      int port = localEndpoint.address().port();
      if (endpoint.port() == port && endpoint.hostAddress().equals(hostAddress)) {
        i.remove();
      }
    }
    setSeedMembers(set);
  }

  public void setTransport(ITransport transport) {
    this.transport = transport;
  }

  public void setLocalMetadata(Map<String, String> localMetadata) {
    this.localMetadata = localMetadata;
  }

  public List<TransportAddress> getSeedMembers() {
    return new ArrayList<>(seedMembers);
  }

  @Override
  public Observable<ClusterMember> listenUpdates() {
    return subject;
  }

  @Override
  public List<ClusterMember> members() {
    return membership.asList();
  }

  @Override
  public ClusterMember member(String id) {
    checkArgument(!Strings.isNullOrEmpty(id), "Member id can't be null or empty");
    return membership.get(id);
  }

  @Override
  public ClusterMember localMember() {
    return membership.get(localEndpoint);
  }

  @Override
  public void start() {
    // Start timer
    timer = new TickingTimer();
    timer.start();

    // Register itself initially before SYNC/SYNC_ACK
    List<ClusterMember> updates = membership.merge(new ClusterMember(localEndpoint, TRUSTED, localMetadata));
    processUpdates(updates, false/* spread gossip */);

    // Listen to SYNC requests from joining/synchronizing members
    transport.listen()
        .filter(syncFilter())
        .filter(syncGroupFilter(syncGroup))
        .map(filterData(localEndpoint))
        .subscribe(onSyncSubscriber);

    // Listen to 'suspected/trusted' events from FailureDetector
    failureDetector.listenStatus().subscribe(onFdSubscriber);

    // Listen to 'membership' message from GossipProtocol
    gossipProtocol.listen().filter(GOSSIP_MEMBERSHIP_FILTER).map(gossipFilterData(localEndpoint))
        .subscribe(onGossipSubscriber);

    // Conduct 'initialization phase': take seed addresses, send SYNC to all and get at least one SYNC_ACK from any
    // of them
    if (!seedMembers.isEmpty()) {
      LOGGER.debug("Initialization phase: making first Sync (wellknown_members={})", seedMembers);
      doInitialSync(seedMembers);
    }

    // Schedule 'running phase': select randomly single seed address, send SYNC and get SYNC_ACK
    if (!seedMembers.isEmpty()) {
      cmTask = scheduler.createWorker().schedulePeriodically(new Action0() {
        @Override
        public void call() {
          try {
            // TODO [AK]: During running phase it should send to both seed or not seed members (issue #38)
            List<TransportAddress> members = selectRandomMembers(seedMembers);
            LOGGER.debug("Running phase: making Sync (selected_members={}))", members);
            doSync(members, scheduler);
          } catch (Exception e) {
            LOGGER.error("Unhandled exception: {}", e, e);
          }
        }
      }, syncTime, syncTime, TimeUnit.MILLISECONDS);
    }
  }

  @Override
  public void stop() {
    if (cmTask != null) {
      cmTask.unsubscribe();
    }
    subject.onCompleted();
    onGossipSubscriber.unsubscribe();
    onSyncSubscriber.unsubscribe();
    onFdSubscriber.unsubscribe();
    timer.stop();
  }

  private void doInitialSync(List<TransportAddress> seedMembers) {
    String period = Integer.toString(periodNbr.incrementAndGet());
    sendSync(seedMembers, period);

    Future<TransportMessage> future =
        transport.listen()
            .filter(syncAckFilter(period))
            .filter(syncGroupFilter(syncGroup))
            .map(filterData(localEndpoint))
            .take(1)
            .toBlocking()
            .toFuture();

    TransportMessage message;
    try {
      message = future.get(syncTimeout, TimeUnit.MILLISECONDS);
    } catch (Exception e) {
      LOGGER.info("Timeout getting SyncAck from {}", seedMembers);
      return;
    }
    onSyncAck(message);
  }

  private void doSync(final List<TransportAddress> members, Scheduler scheduler) {
    String period = Integer.toString(periodNbr.incrementAndGet());
    sendSync(members, period);
    transport.listen().filter(syncAckFilter(period)).filter(syncGroupFilter(syncGroup)).map(filterData(localEndpoint))
        .take(1).timeout(syncTimeout, TimeUnit.MILLISECONDS, scheduler)
        .subscribe(Subscribers.create(new Action1<TransportMessage>() {
          @Override
          public void call(TransportMessage transportMessage) {
            onSyncAck(transportMessage);
          }
        }, new Action1<Throwable>() {
          @Override
          public void call(Throwable throwable) {
            LOGGER.info("Timeout getting SyncAck from {}", members);
          }
        }));
  }

  private void sendSync(List<TransportAddress> members, String period) {
    ClusterMembershipData syncData = new ClusterMembershipData(membership.asList(), syncGroup);
    final Message message =
        new Message(syncData, TransportHeaders.QUALIFIER, SYNC, TransportHeaders.CORRELATION_ID, period);
    for (TransportAddress memberAddress : members) {
      Futures.addCallback(transport.connect(memberAddress), new FutureCallback<TransportEndpoint>() {
        @Override
        public void onSuccess(TransportEndpoint endpoint) {
          transport.send(endpoint, message);
        }

        @Override
        public void onFailure(@Nonnull Throwable t) {
          LOGGER.error("Failed to send sync", t);
        }
      });
    }
  }

  private void onSyncAck(TransportMessage transportMessage) {
    ClusterMembershipData data = (ClusterMembershipData) transportMessage.message().data();
    TransportEndpoint endpoint = transportMessage.endpoint();
    List<ClusterMember> updates = membership.merge(data);
    if (!updates.isEmpty()) {
      LOGGER.debug("Received SyncAck from {}, updates: {}", endpoint, updates);
      processUpdates(updates, true/* spread gossip */);
    } else {
      LOGGER.debug("Received SyncAck from {}, no updates", endpoint);
    }
  }

  private List<TransportAddress> selectRandomMembers(List<TransportAddress> members) {
    List<TransportAddress> list = new ArrayList<>(members);
    Collections.shuffle(list, ThreadLocalRandom.current());
    return ImmutableList.of(list.get(ThreadLocalRandom.current().nextInt(list.size())));
  }

  /**
   * Takes {@code updates} and process them in next order.
   * <ul>
   * <li>recalculates 'cluster members' for {@link #gossipProtocol} and {@link #failureDetector} by filtering out
   * {@code REMOVED/SHUTDOWN} members</li>
   * <li>if {@code spreadGossip} was set {@code true} -- converts {@code updates} to {@link ClusterMembershipData} and
   * send it to cluster via {@link #gossipProtocol}</li>
   * <li>publishes updates locally (see {@link #listenUpdates()})</li>
   * <li>iterates on {@code updates}, if {@code update} become {@code SUSPECTED} -- schedules a timer (
   * {@link #maxSuspectTime}) to remove the member (on {@code TRUSTED} -- cancels the timer)</li>
   * <li>iterates on {@code updates}, if {@code update} become {@code SHUTDOWN} -- schedules a timer (
   * {@link #maxShutdownTime}) to remove the member</li>
   * </ul>
   * 
   * @param updates list of updates after merge
   * @param spreadGossip flag indicating should updates be gossiped to cluster
   */
  private void processUpdates(List<ClusterMember> updates, boolean spreadGossip) {
    if (updates.isEmpty()) {
      return;
    }

    // Reset cluster members on FailureDetector and Gossip
    Collection<TransportEndpoint> endpoints = membership.getTrustedOrSuspectedEndpoints();
    failureDetector.setClusterEndpoints(endpoints);
    gossipProtocol.setClusterEndpoints(endpoints);

    // Publish updates to cluster
    if (spreadGossip) {
      gossipProtocol.spread(new Message(new ClusterMembershipData(updates, syncGroup)));
    }
    // Publish updates locally
    for (ClusterMember update : updates) {
      subject.onNext(update);
    }

    // Check state transition
    for (final ClusterMember member : updates) {
      LOGGER.debug("Member {} became {}", member.endpoint(), member.status());
      switch (member.status()) {
        case SUSPECTED:
          failureDetector.suspect(member.endpoint());
          timer.schedule(member.id(), new Runnable() {
            @Override
            public void run() {
              LOGGER.debug("Time to remove SUSPECTED member={} from membership", member.endpoint());
              processUpdates(membership.remove(member.endpoint()), false/* spread gossip */);
            }
          }, maxSuspectTime, TimeUnit.MILLISECONDS);
          break;
        case TRUSTED:
          failureDetector.trust(member.endpoint());
          timer.cancel(member.id());
          break;
        case SHUTDOWN:
          timer.schedule(new Runnable() {
            @Override
            public void run() {
              LOGGER.debug("Time to remove SHUTDOWN member={} from membership", member.endpoint());
              membership.remove(member.endpoint());
            }
          }, maxShutdownTime, TimeUnit.MILLISECONDS);
          break;
        default:
          // ignore
      }
    }
  }

  @Override
  public void leave() {
    ClusterMember r1 = new ClusterMember(localEndpoint, SHUTDOWN, localMetadata);
    gossipProtocol.spread(new Message(new ClusterMembershipData(ImmutableList.of(r1), syncGroup)));
  }

  @Override
  public boolean isLocalMember(ClusterMember member) {
    checkArgument(member != null);
    return this.localMember().endpoint().equals(member.endpoint());
  }

  private TransportHeaders.Filter syncFilter() {
    return SYNC_FILTER;
  }

  private TransportHeaders.Filter syncAckFilter(String correlationId) {
    return new TransportHeaders.Filter(SYNC_ACK, correlationId);
  }
}
