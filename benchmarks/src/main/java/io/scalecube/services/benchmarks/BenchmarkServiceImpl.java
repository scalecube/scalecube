package io.scalecube.services.benchmarks;

import java.util.stream.IntStream;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public class BenchmarkServiceImpl implements BenchmarkService {

  @Override
  public Mono<Void> oneWay(String request) {
    return Mono.empty();
  }

  @Override
  public Mono<String> requestOne(String request) {
    return Mono.just(request);
  }

  @Override
  public Flux<String> requestMany(int count) {
    return Flux.fromStream(IntStream.range(0, count).mapToObj(i -> "response-" + i));
  }

  @Override
  public Flux<String> requestBidirectionalEcho(Flux<String> counts) {
    return counts;
  }
}
