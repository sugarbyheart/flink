/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.test.streaming.api;

import org.apache.flink.api.common.functions.FlatMapFunction;
import org.apache.flink.api.common.functions.FoldFunction;
import org.apache.flink.api.common.functions.MapFunction;
import org.apache.flink.api.common.functions.RichMapFunction;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.streaming.api.collector.selector.OutputSelector;
import org.apache.flink.streaming.api.datastream.AsyncDataStream;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.datastream.SplitStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.async.AsyncFunction;
import org.apache.flink.streaming.api.functions.async.RichAsyncFunction;
import org.apache.flink.streaming.api.functions.async.collector.AsyncCollector;
import org.apache.flink.streaming.api.functions.sink.SinkFunction;
import org.apache.flink.streaming.api.functions.source.SourceFunction;
import org.apache.flink.streaming.util.StreamingMultipleProgramsTestBase;

import org.apache.flink.util.Collector;
import org.apache.flink.util.MathUtils;

import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Integration tests for streaming operators.
 */
public class StreamingOperatorsITCase extends StreamingMultipleProgramsTestBase {

	/**
	 * Tests the proper functioning of the streaming fold operator. For this purpose, a stream
	 * of {@code Tuple2<Integer, Integer>} is created. The stream is grouped according to the
	 * first tuple value. Each group is folded where the second tuple value is summed.
	 *
	 * <p>This test relies on the hash function used by the {@link DataStream#keyBy}, which is
	 * assumed to be {@link MathUtils#murmurHash}.
	 */
	@Test
	public void testGroupedFoldOperation() throws Exception {
		int numElements = 10;
		final int numKeys = 2;

		StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

		DataStream<Tuple2<Integer, Integer>> sourceStream = env.addSource(new TupleSource(numElements, numKeys));

		SplitStream<Tuple2<Integer, Integer>> splittedResult = sourceStream
			.keyBy(0)
			.fold(0, new FoldFunction<Tuple2<Integer, Integer>, Integer>() {
				private static final long serialVersionUID = 4875723041825726082L;

				@Override
				public Integer fold(Integer accumulator, Tuple2<Integer, Integer> value) throws Exception {
					return accumulator + value.f1;
				}
			}).map(new RichMapFunction<Integer, Tuple2<Integer, Integer>>() {
				private static final long serialVersionUID = 8538355101606319744L;
				int key = -1;
				@Override
				public Tuple2<Integer, Integer> map(Integer value) throws Exception {
					if (key == -1){
						key = MathUtils.murmurHash(value) % numKeys;
					}
					return new Tuple2<>(key, value);
				}
			}).split(new OutputSelector<Tuple2<Integer, Integer>>() {
				private static final long serialVersionUID = -8439325199163362470L;

				@Override
				public Iterable<String> select(Tuple2<Integer, Integer> value) {
					List<String> output = new ArrayList<>();

					output.add(value.f0 + "");
					return output;
				}
			});

		final MemorySinkFunction sinkFunction1 = new MemorySinkFunction(0);

		final List<Integer> actualResult1 = new ArrayList<>();
		MemorySinkFunction.registerCollection(0, actualResult1);

		splittedResult.select("0").map(new MapFunction<Tuple2<Integer, Integer>, Integer>() {
			private static final long serialVersionUID = 2114608668010092995L;

			@Override
			public Integer map(Tuple2<Integer, Integer> value) throws Exception {
				return value.f1;
			}
		}).addSink(sinkFunction1);

		final MemorySinkFunction sinkFunction2 = new MemorySinkFunction(1);

		final List<Integer> actualResult2 = new ArrayList<>();
		MemorySinkFunction.registerCollection(1, actualResult2);

		splittedResult.select("1").map(new MapFunction<Tuple2<Integer, Integer>, Integer>() {
			private static final long serialVersionUID = 5631104389744681308L;

			@Override
			public Integer map(Tuple2<Integer, Integer> value) throws Exception {
				return value.f1;
			}
		}).addSink(sinkFunction2);

		Collection<Integer> expected1 = new ArrayList<>(10);
		Collection<Integer> expected2 = new ArrayList<>(10);
		int counter1 = 0;
		int counter2 = 0;

		for (int i = 0; i < numElements; i++) {
			if (MathUtils.murmurHash(i) % numKeys == 0) {
				counter1 += i;
				expected1.add(counter1);
			} else {
				counter2 += i;
				expected2.add(counter2);
			}
		}

		env.execute();

		Collections.sort(actualResult1);
		Collections.sort(actualResult2);

		Assert.assertEquals(expected1, actualResult1);
		Assert.assertEquals(expected2, actualResult2);

		MemorySinkFunction.clear();
	}

	/**
	 * Tests whether the fold operation can also be called with non Java serializable types.
	 */
	@Test
	public void testFoldOperationWithNonJavaSerializableType() throws Exception {
		final int numElements = 10;

		StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

		DataStream<Tuple2<Integer, NonSerializable>> input = env.addSource(new NonSerializableTupleSource(numElements));

		final MemorySinkFunction sinkFunction = new MemorySinkFunction(0);
		final ArrayList<Integer> actualResult = new ArrayList<>();
		MemorySinkFunction.registerCollection(0, actualResult);

		input
			.keyBy(0)
			.fold(
				new NonSerializable(42),
				new FoldFunction<Tuple2<Integer, NonSerializable>, NonSerializable>() {
					private static final long serialVersionUID = 2705497830143608897L;

					@Override
					public NonSerializable fold(NonSerializable accumulator, Tuple2<Integer, NonSerializable> value) throws Exception {
						return new NonSerializable(accumulator.value + value.f1.value);
					}
			})
			.map(new MapFunction<NonSerializable, Integer>() {
				private static final long serialVersionUID = 6906984044674568945L;

				@Override
				public Integer map(NonSerializable value) throws Exception {
					return value.value;
				}
			})
			.addSink(sinkFunction);

		Collection<Integer> expected = new ArrayList<>(10);

		for (int i = 0; i < numElements; i++) {
			expected.add(42 + i);
		}

		env.execute();

		Collections.sort(actualResult);

		Assert.assertEquals(expected, actualResult);

		MemorySinkFunction.clear();
	}

	/**
	 * Tests the basic functionality of the AsyncWaitOperator: Processing a limited stream of
	 * elements by doubling their value. This is tested in for the ordered and unordered mode.
	 */
	@Test
	public void testAsyncWaitOperator() throws Exception {
		final int numElements = 5;
		final long timeout = 1000L;

		StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

		DataStream<Tuple2<Integer, NonSerializable>> input = env.addSource(new NonSerializableTupleSource(numElements));

		AsyncFunction<Tuple2<Integer, NonSerializable>, Integer> function = new RichAsyncFunction<Tuple2<Integer, NonSerializable>, Integer>() {
			private static final long serialVersionUID = 7000343199829487985L;

			transient ExecutorService executorService;

			@Override
			public void open(Configuration parameters) throws Exception {
				super.open(parameters);
				executorService = Executors.newFixedThreadPool(numElements);
			}

			@Override
			public void close() throws Exception {
				super.close();
				executorService.shutdownNow();
			}

			@Override
			public void asyncInvoke(final Tuple2<Integer, NonSerializable> input,
									final AsyncCollector<Integer> collector) throws Exception {
				executorService.submit(new Runnable() {
					@Override
					public void run() {
						collector.collect(Collections.singletonList(input.f0 + input.f0));
					}
				});
			}
		};

		DataStream<Integer> orderedResult = AsyncDataStream.orderedWait(
			input,
			function,
			timeout,
			TimeUnit.MILLISECONDS,
			2).setParallelism(1);

		// save result from ordered process
		final MemorySinkFunction sinkFunction1 = new MemorySinkFunction(0);
		final List<Integer> actualResult1 = new ArrayList<>(numElements);
		MemorySinkFunction.registerCollection(0, actualResult1);

		orderedResult.addSink(sinkFunction1).setParallelism(1);

		DataStream<Integer> unorderedResult = AsyncDataStream.unorderedWait(
			input,
			function,
			timeout,
			TimeUnit.MILLISECONDS,
			2);

		// save result from unordered process
		final MemorySinkFunction sinkFunction2 = new MemorySinkFunction(1);
		final List<Integer> actualResult2 = new ArrayList<>(numElements);
		MemorySinkFunction.registerCollection(1, actualResult2);

		unorderedResult.addSink(sinkFunction2);

		Collection<Integer> expected = new ArrayList<>(10);

		for (int i = 0; i < numElements; i++) {
			expected.add(i + i);
		}

		env.execute();

		Assert.assertEquals(expected, actualResult1);

		Collections.sort(actualResult2);
		Assert.assertEquals(expected, actualResult2);

		MemorySinkFunction.clear();
	}

	private static class NonSerializable {
		// This makes the type non-serializable
		private final Object obj = new Object();

		private final int value;

		public NonSerializable(int value) {
			this.value = value;
		}
	}

	private static class NonSerializableTupleSource implements SourceFunction<Tuple2<Integer, NonSerializable>> {
		private static final long serialVersionUID = 3949171986015451520L;
		private final int numElements;

		public NonSerializableTupleSource(int numElements) {
			this.numElements = numElements;
		}

		@Override
		public void run(SourceContext<Tuple2<Integer, NonSerializable>> ctx) throws Exception {
			for (int i = 0; i < numElements; i++) {
				ctx.collect(new Tuple2<>(i, new NonSerializable(i)));
			}
		}

		@Override
		public void cancel() {}
	}

	private static class TupleSource implements SourceFunction<Tuple2<Integer, Integer>> {

		private static final long serialVersionUID = -8110466235852024821L;
		private final int numElements;
		private final int numKeys;

		public TupleSource(int numElements, int numKeys) {
			this.numElements = numElements;
			this.numKeys = numKeys;
		}

		@Override
		public void run(SourceContext<Tuple2<Integer, Integer>> ctx) throws Exception {
			for (int i = 0; i < numElements; i++) {
				// keys '1' and '2' hash to different buckets
				Tuple2<Integer, Integer> result = new Tuple2<>(1 + (MathUtils.murmurHash(i) % numKeys), i);
				ctx.collect(result);
			}
		}

		@Override

		public void cancel() {
		}
	}

	private static class MemorySinkFunction implements SinkFunction<Integer> {
		private static Map<Integer, Collection<Integer>> collections = new ConcurrentHashMap<>();

		private static final long serialVersionUID = -8815570195074103860L;

		private final int key;

		public MemorySinkFunction(int key) {
			this.key = key;
		}

		@Override
		public void invoke(Integer value) throws Exception {
			Collection<Integer> collection = collections.get(key);

			synchronized (collection) {
				collection.add(value);
			}
		}

		public static void registerCollection(int key, Collection<Integer> collection) {
			collections.put(key, collection);
		}

		public static void clear() {
			collections.clear();
		}
	}

	@Test
	public void testOperatorChainWithObjectReuseAndNoOutputOperators() throws Exception {
		StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
		env.getConfig().enableObjectReuse();
		DataStream<Integer> input = env.fromElements(1, 2, 3);
		input.flatMap(new FlatMapFunction<Integer, Integer>() {
			@Override
			public void flatMap(Integer value, Collector<Integer> out) throws Exception {
				out.collect(value << 1);
			}
		});
		env.execute();
	}
}
