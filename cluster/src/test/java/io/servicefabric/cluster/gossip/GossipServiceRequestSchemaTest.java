package io.servicefabric.cluster.gossip;

import static io.netty.buffer.Unpooled.buffer;
import static io.netty.buffer.Unpooled.copiedBuffer;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import io.servicefabric.transport.TransportHeaders;
import io.servicefabric.transport.protocol.Message;

import io.netty.buffer.ByteBuf;
import io.servicefabric.transport.protocol.ProtostuffProtocol;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class GossipServiceRequestSchemaTest {

  private static final String testDataQualifier = "servicefabric/testData";

  private TestData testData;

  @Before
  public void init() throws Throwable {
    Map<String, String> properties = new HashMap<>();
    properties.put("key", "123");

    testData = new TestData();
    testData.setProperties(properties);
  }

  @Test
  public void testProtostuff() throws Exception {
    ProtostuffProtocol protocol = new ProtostuffProtocol();

    List<Gossip> gossips = getGossips();

    Message message = new Message(new GossipRequest(gossips), TransportHeaders.CORRELATION_ID, "CORR_ID");

    ByteBuf bb = buffer();
    protocol.getMessageSerializer().serialize(message, bb);

    assertTrue(bb.readableBytes() > 0);

    ByteBuf input = copiedBuffer(bb);

    Message deserializedMessage = protocol.getMessageDeserializer().deserialize(input);

    assertNotNull(deserializedMessage);
    Assert.assertEquals(deserializedMessage.data().getClass(), GossipRequest.class);
    Assert.assertEquals("CORR_ID", deserializedMessage.header(TransportHeaders.CORRELATION_ID));

    GossipRequest gossipRequest = (GossipRequest) deserializedMessage.data();
    assertNotNull(gossipRequest);
    assertNotNull(gossipRequest.getGossipList());
    assertNotNull(gossipRequest.getGossipList().get(0));

    Object msg = gossipRequest.getGossipList().get(0).getMessage().data();
    assertNotNull(msg);
    assertTrue(msg.toString(), msg instanceof TestData);
  }

  private List<Gossip> getGossips() {
    Gossip request = new Gossip("idGossip", new Message(testData, TransportHeaders.QUALIFIER, testDataQualifier));
    Gossip request2 = new Gossip("idGossip2", new Message(testData, TransportHeaders.QUALIFIER, testDataQualifier));
    List<Gossip> gossips = new ArrayList<>(2);
    gossips.add(request);
    gossips.add(request2);
    return gossips;
  }

  private static class TestData {

    private Map<String, String> properties;

    TestData() {}

    public Map<String, String> getProperties() {
      return properties;
    }

    public void setProperties(Map<String, String> properties) {
      this.properties = properties;
    }
  }

}
