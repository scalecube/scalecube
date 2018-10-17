package io.scalecube.gateway;

import com.codahale.metrics.Counter;
import com.codahale.metrics.Meter;
import io.scalecube.services.metrics.Metrics;
import java.util.Optional;

public class GatewayMetrics {

  public static final String METRIC_CONNECTION = "connection";
  public static final String METRIC_REQ = "request";
  public static final String METRIC_RESP = "response";
  public static final String METRIC_SERVICE_RESP = "service-response";

  private final Counter connectionCounter;
  private final Meter requestMeter;
  private final Meter responseMeter;
  private final Meter serviceResponseMeter;

  /**
   * Constructor for gateway metrics.
   *
   * @param prefix prefix for gateway metrics instance
   * @param metrics microservices metrics
   */
  public GatewayMetrics(String prefix, Metrics metrics) {
    connectionCounter =
        Optional.ofNullable(metrics.getCounter(prefix, METRIC_CONNECTION)).orElse(null);
    requestMeter = Optional.ofNullable(metrics.getMeter(prefix, "", METRIC_REQ)).orElse(null);
    responseMeter = Optional.ofNullable(metrics.getMeter(prefix, "", METRIC_RESP)).orElse(null);
    serviceResponseMeter =
        Optional.ofNullable(metrics.getMeter(prefix, "", METRIC_SERVICE_RESP)).orElse(null);
  }

  /** Increment connection counter. */
  public void incConnection() {
    if (connectionCounter != null) {
      connectionCounter.inc();
    }
  }

  /** Decrement connection counter. */
  public void decConnection() {
    if (connectionCounter != null) {
      connectionCounter.dec();
    }
  }

  /** Mark request for calls/sec measurement. */
  public void markRequest() {
    if (requestMeter != null) {
      requestMeter.mark();
    }
  }

  /** Mark response for calls/sec measurement. */
  public void markResponse() {
    if (responseMeter != null) {
      responseMeter.mark();
    }
  }

  /** Mark service response for calls/sec measurement. */
  public void markServiceResponse() {
    if (serviceResponseMeter != null) {
      serviceResponseMeter.mark();
    }
  }
}
