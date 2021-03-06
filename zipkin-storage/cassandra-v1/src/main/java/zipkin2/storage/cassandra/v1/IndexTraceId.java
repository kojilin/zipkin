/*
 * Copyright 2015-2020 The OpenZipkin Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package zipkin2.storage.cassandra.v1;

import com.datastax.driver.core.BoundStatement;
import com.datastax.driver.core.PreparedStatement;
import com.datastax.driver.core.ResultSetFuture;
import com.datastax.driver.core.Session;
import com.datastax.driver.core.querybuilder.Insert;
import com.google.auto.value.AutoValue;
import java.util.Set;
import java.util.stream.IntStream;
import zipkin2.internal.DelayLimiter;
import zipkin2.storage.QueryRequest;
import zipkin2.storage.cassandra.internal.call.DeduplicatingInsert;

import static com.datastax.driver.core.querybuilder.QueryBuilder.bindMarker;
import static com.datastax.driver.core.querybuilder.QueryBuilder.insertInto;
import static com.datastax.driver.core.querybuilder.QueryBuilder.ttl;
import static java.util.concurrent.TimeUnit.SECONDS;
import static java.util.stream.Collectors.toSet;

/**
 * Inserts index rows into a Cassandra table. This skips entries that don't improve results based on
 * {@link QueryRequest#endTs()} and {@link QueryRequest#lookback()}. For example, it doesn't insert
 * rows that only vary on timestamp and exist between timestamps of existing rows.
 */
final class IndexTraceId extends DeduplicatingInsert<IndexTraceId.Input> {
  /**
   * Used to avoid hot spots when writing indexes used to query by service name or annotation.
   *
   * <p>This controls the amount of buckets, or partitions writes to {@code service_name_index}
   * and {@code annotations_index}. This must be the same for all query servers, and has
   * historically always been 10.
   *
   * <p>See https://github.com/openzipkin/zipkin/issues/623 for further explanation
   */
  static final int BUCKET_COUNT = 10;
  static final Set<Integer> BUCKETS = IntStream.range(0, BUCKET_COUNT).boxed().collect(toSet());

  @AutoValue
  abstract static class Input {
    static Input create(String partitionKey, long timestamp, long traceId) {
      return new AutoValue_IndexTraceId_Input(partitionKey, timestamp, traceId);
    }

    abstract String partitionKey(); // ends up as a partition key, ignoring bucketing

    abstract long ts(); // microseconds at millis precision

    abstract long trace_id(); // clustering key
  }

  static abstract class Factory extends DeduplicatingInsert.Factory<Input> {
    final Session session;
    final TraceIdIndexer.Factory indexerFactory;
    final PreparedStatement preparedStatement;

    Factory(CassandraStorage storage, String table, int indexTtl) {
      super(SECONDS.toMillis(storage.indexCacheTtl), storage.indexCacheMax);
      session = storage.session();
      indexerFactory = new TraceIdIndexer.Factory(table, SECONDS.toNanos(storage.indexCacheTtl),
        storage.indexCacheMax);
      Insert insertQuery = declarePartitionKey(insertInto(table)
        .value("ts", bindMarker())
        .value("trace_id", bindMarker()));
      if (indexTtl > 0) insertQuery.using(ttl(indexTtl));
      preparedStatement = session.prepare(insertQuery);
    }

    abstract Insert declarePartitionKey(Insert insert);

    abstract BoundStatement bindPartitionKey(BoundStatement bound, String partitionKey);

    @Override protected IndexTraceId newCall(Input input) {
      return new IndexTraceId(this, delayLimiter, input);
    }

    TraceIdIndexer newIndexer() {
      return indexerFactory.newIndexer();
    }

    @Override public void clear() {
      super.clear();
      indexerFactory.clear();
    }
  }

  final Factory factory;

  IndexTraceId(Factory factory, DelayLimiter<Input> delayLimiter, Input input) {
    super(delayLimiter, input);
    this.factory = factory;
  }

  @Override protected ResultSetFuture newFuture() {
    BoundStatement bound = factory.preparedStatement.bind()
      .setBytesUnsafe(0, TimestampCodec.serialize(input.ts()))
      .setLong(1, input.trace_id());
    bound = factory.bindPartitionKey(bound, input.partitionKey());
    return factory.session.executeAsync(bound);
  }

  @Override public String toString() {
    return input.toString().replace("Input", factory.getClass().getSimpleName());
  }

  @Override public IndexTraceId clone() {
    return new IndexTraceId(factory, delayLimiter, input);
  }
}
