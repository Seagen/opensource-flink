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

package org.apache.flink.connector.datagen.source;

import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.api.common.functions.RichMapFunction;
import org.apache.flink.api.common.state.CheckpointListener;
import org.apache.flink.api.common.typeinfo.Types;
import org.apache.flink.api.connector.source.SourceReaderContext;
import org.apache.flink.api.connector.source.util.ratelimit.RateLimiterStrategy;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.runtime.testutils.MiniClusterResourceConfiguration;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.datastream.DataStreamSource;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.test.junit5.MiniClusterExtension;
import org.apache.flink.util.TestLogger;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import static java.util.stream.Collectors.summingInt;
import static org.apache.flink.core.testutils.FlinkAssertions.anyCauseMatches;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;

/** An integration test for {@code DataGeneratorSource}. */
class DataGeneratorSourceITCase extends TestLogger {

    private static final int PARALLELISM = 4;

    @RegisterExtension
    private static final MiniClusterExtension miniClusterExtension =
            new MiniClusterExtension(
                    new MiniClusterResourceConfiguration.Builder()
                            .setNumberTaskManagers(1)
                            .setNumberSlotsPerTaskManager(PARALLELISM)
                            .build());

    // ------------------------------------------------------------------------

    @Test
    @DisplayName("Combined results of parallel source readers produce the expected sequence.")
    void testParallelSourceExecution() throws Exception {
        final StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setParallelism(PARALLELISM);

        final DataStream<Long> stream = getGeneratorSourceStream(index -> index, env, 1_000L);

        final List<Long> result = stream.executeAndCollect(10000);

        assertThat(result).containsExactlyInAnyOrderElementsOf(range(0, 999));
    }

    @Test
    @DisplayName("Generator function can be instantiated as an anonymous class.")
    void testParallelSourceExecutionWithAnonymousClass() throws Exception {
        final StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setParallelism(PARALLELISM);

        GeneratorFunction<Long, Long> generatorFunction =
                new GeneratorFunction<Long, Long>() {

                    @Override
                    public Long map(Long value) {
                        return value;
                    }
                };

        final DataStream<Long> stream = getGeneratorSourceStream(generatorFunction, env, 1_000L);

        final List<Long> result = stream.executeAndCollect(10000);

        assertThat(result).containsExactlyInAnyOrderElementsOf(range(0, 999));
    }

    @Test
    @DisplayName("Exceptions from the generator function are not 'swallowed'.")
    void testFailingGeneratorFunction() throws Exception {
        final StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setParallelism(PARALLELISM);

        GeneratorFunction<Long, Long> generatorFunction =
                value -> {
                    throw new Exception("boom");
                };

        final DataStream<Long> stream = getGeneratorSourceStream(generatorFunction, env, 1_000L);

        assertThatThrownBy(
                        () -> {
                            stream.executeAndCollect(10000);
                        })
                .satisfies(anyCauseMatches("exception on this input:"))
                .satisfies(anyCauseMatches("boom"));
    }

    @Test
    @DisplayName("Exceptions from the generator function initialization are not 'swallowed'.")
    // FIX_ME: failure details are swallowed by Flink
    // Full details are still available at this line:
    // https://github.com/apache/flink/blob/bccecc23067eb7f18e20bade814be73393401be5/flink-runtime/src/main/java/org/apache/flink/runtime/taskmanager/Task.java#L758
    // But the execution falls through to the line below and discards the root cause of
    // cancelling the source invokable without recording it:
    // https://github.com/apache/flink/blob/bccecc23067eb7f18e20bade814be73393401be5/flink-runtime/src/main/java/org/apache/flink/runtime/taskmanager/Task.java#L780
    @Disabled
    void testFailingGeneratorFunctionInitialization() throws Exception {
        final StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setParallelism(PARALLELISM);

        GeneratorFunction<Long, Long> generatorFunctionFailingInit =
                new GeneratorFunction<Long, Long>() {
                    @Override
                    public void open(SourceReaderContext readerContext) throws Exception {
                        throw new Exception("boom");
                    }

                    @Override
                    public Long map(Long value) {
                        return value;
                    }
                };

        final DataStream<Long> stream =
                getGeneratorSourceStream(generatorFunctionFailingInit, env, 1_000L);

        assertThatThrownBy(
                        () -> {
                            stream.executeAndCollect(10000);
                        })
                .satisfies(anyCauseMatches("Failed to open"))
                .satisfies(anyCauseMatches("boom"));
    }

    @Test
    @DisplayName(
            "Result is correct when less elements are expected than the number of parallel source readers")
    void testLessSplitsThanParallelism() throws Exception {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setParallelism(PARALLELISM);
        int n = PARALLELISM - 2;
        DataStream<Long> stream = getGeneratorSourceStream(index -> index, env, n).map(l -> l);

        List<Long> result = stream.executeAndCollect(100);

        assertThat(result).containsExactlyInAnyOrderElementsOf(range(0, n - 1));
    }

    @Test
    @DisplayName("Test GatedRateLimiter")
    void testGatedRateLimiter() throws Exception {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.enableCheckpointing(100);

        env.setParallelism(PARALLELISM);

        int capacityPerSubtaskPerCycle = 2;
        int capacityPerCycle = // avoid rounding errors when spreading records among subtasks
                PARALLELISM * capacityPerSubtaskPerCycle;

        final GeneratorFunction<Long, Long> generatorFunction = index -> 1L;

        // Allow each subtask to produce at least 3 cycles, gated by checkpoints
        int count = capacityPerCycle * 3;
        final DataGeneratorSource<Long> generatorSource =
                new DataGeneratorSource<>(
                        generatorFunction,
                        count,
                        RateLimiterStrategy.perCheckpoint(capacityPerCycle),
                        Types.LONG);

        final DataStreamSource<Long> streamSource =
                env.fromSource(generatorSource, WatermarkStrategy.noWatermarks(), "Data Generator");
        final DataStream<Tuple2<Integer, Long>> map =
                streamSource.map(new SubtaskAndCheckpointMapper());
        final List<Tuple2<Integer, Long>> results = map.executeAndCollect(1000);

        final Map<Tuple2<Integer, Long>, Integer> collect =
                results.stream()
                        .collect(
                                Collectors.groupingBy(
                                        x -> (new Tuple2<>(x.f0, x.f1)), summingInt(x -> 1)));
        for (Map.Entry<Tuple2<Integer, Long>, Integer> entry : collect.entrySet()) {
            assertThat(entry.getValue()).isEqualTo(capacityPerSubtaskPerCycle);
        }
    }

    private static class SubtaskAndCheckpointMapper
            extends RichMapFunction<Long, Tuple2<Integer, Long>> implements CheckpointListener {

        private long checkpointId = 0;
        private int subtaskIndex;

        @Override
        public void open(Configuration parameters) {
            subtaskIndex = getRuntimeContext().getIndexOfThisSubtask();
        }

        @Override
        public Tuple2<Integer, Long> map(Long value) {
            return new Tuple2<>(subtaskIndex, checkpointId);
        }

        @Override
        public void notifyCheckpointComplete(long checkpointId) {
            this.checkpointId = checkpointId;
        }
    }

    private DataStream<Long> getGeneratorSourceStream(
            GeneratorFunction<Long, Long> generatorFunction,
            StreamExecutionEnvironment env,
            long count) {
        DataGeneratorSource<Long> dataGeneratorSource =
                new DataGeneratorSource<>(generatorFunction, count, Types.LONG);

        return env.fromSource(
                dataGeneratorSource, WatermarkStrategy.noWatermarks(), "generator source");
    }

    private List<Long> range(int startInclusive, int endInclusive) {
        return LongStream.rangeClosed(startInclusive, endInclusive)
                .boxed()
                .collect(Collectors.toList());
    }
}
