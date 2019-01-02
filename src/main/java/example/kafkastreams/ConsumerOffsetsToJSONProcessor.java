package example.kafkastreams;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import kafka.common.OffsetAndMetadata;
import kafka.coordinator.group.BaseKey;
import kafka.coordinator.group.GroupMetadataManager;
import kafka.coordinator.group.GroupTopicPartition;
import kafka.coordinator.group.OffsetKey;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.kstream.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;

/**
 * Translated from - https://github.com/sderosiaux/kafka-streams-consumer-offsets-to-json
 */
public class ConsumerOffsetsToJSONProcessor {

    // Required values
    private static final String ENV_BOOTSTRAP_SERVERS = "BOOTSTRAP_SERVERS";
    private static final String ENV_STREAMS_OUTPUT = "STREAMS_OUTPUT";

    // Values with defaults
    private static final String ENV_APPLICATION_ID = "APPLICATION_ID";
    private static final String ENV_CLIENT_ID = "CLIENT_ID";
    private static final String ENV_OFFSET_RESET = "AUTO_OFFSET_RESET";

    private static final Logger LOGGER = LoggerFactory.getLogger(ConsumerOffsetsToJSONProcessor.class);

    private static ObjectMapper mapper = new ObjectMapper();

    private static KeyValueMapper<OffsetKey, ByteBuffer, KeyValue<byte[], String>> toJson = (OffsetKey k, ByteBuffer v) -> {
        Optional<OffsetAndMetadata> value = Optional.of(v).map(GroupMetadataManager::readOffsetMessageValue);
        try {
            return KeyValue.pair(null,
                    mapper.writerFor(ConsumerOffsetDetails.class)
                            .writeValueAsString(ConsumerOffsetDetails.from(k, value)));
        } catch (JsonProcessingException e) {
            LOGGER.error("error generating JSON", e);
            return null;
        }
    };

    private static KeyValueMapper<ByteBuffer, ByteBuffer, KeyValue<BaseKey, ByteBuffer>> baseKey = (k, v) ->
            KeyValue.pair(GroupMetadataManager.readMessageKey(k), v);

    private static KeyValueMapper<BaseKey, ByteBuffer, KeyValue<OffsetKey, ByteBuffer>> offsetKey = (k, v) ->
            KeyValue.pair((OffsetKey) k, v);

    private static Predicate<BaseKey, ByteBuffer> otherTopicsOnly = (BaseKey k, ByteBuffer buf) -> {
        if (k instanceof OffsetKey) return ((OffsetKey) k).key()
                .topicPartition().topic().equals(System.getenv(ENV_STREAMS_OUTPUT));
        else return false;
    };

    public static void main(String[] args) throws Exception {
        final String inputTopic = "__consumer_offsets";
        final String outputTopic = System.getenv(ENV_STREAMS_OUTPUT);

        LOGGER.info("input topic = {}", inputTopic);
        LOGGER.info("output topic = {}", outputTopic);
        if (isBlank(outputTopic)) {
            LOGGER.error("Output topic variable '{}' is empty.", ENV_STREAMS_OUTPUT);
            System.exit(1);
        } else if (inputTopic.equals(outputTopic)) {
            LOGGER.error("Input and output topics are the same. Exiting to disallow Kafka consumption loops.");
            System.exit(1);
        }

        final String defaultApplicationId = "upper-app";
        final String defaultClientId = "upper-client";
        Properties props = getProperties(defaultApplicationId, defaultClientId);

        KafkaStreams streams = createStreams(props, inputTopic, outputTopic);
        startStream(streams);
    }

    private static KafkaStreams createStreams(Properties streamConfiguration, String input, String output) {
        final Serde<ByteBuffer> byteBufferSerde = Serdes.ByteBuffer();
        final Serde<String> stringSerde = Serdes.String();
        final StreamsBuilder builder = new StreamsBuilder();

        builder.stream(input, Consumed.with(byteBufferSerde, byteBufferSerde))
                .map(baseKey)
                .filter(otherTopicsOnly)
                .map(offsetKey)
                .map(toJson)
                .to(output, Produced.with(Serdes.ByteArray(), stringSerde));

        return new KafkaStreams(builder.build(), streamConfiguration);
    }

    private static void startStream(KafkaStreams stream) {
        final CountDownLatch latch = new CountDownLatch(1);

        // attach shutdown handler to catch control-c
        Runtime.getRuntime().addShutdownHook(new Thread("stream-shutdown-hook") {
            @Override
            public void run() {
                LOGGER.info("Closing Stream...");
                stream.close();
                latch.countDown();
            }
        });

        try {
            LOGGER.info("Starting Stream...");
            stream.start();
            latch.await();
        } catch (Throwable e) {
            System.exit(1);
        }
        System.exit(0);
    }

    private static Properties getProperties(String defaultApplicationId, String defaultClientId) {
        String bootstrapServers = System.getenv(ENV_BOOTSTRAP_SERVERS);
        if (isBlank(bootstrapServers)) {
            LOGGER.error("Undefined environment variable '{}'. Will be unable to communicate with Kafka.",
                    ENV_BOOTSTRAP_SERVERS);
            System.exit(1);
        }

        String appId = System.getenv(ENV_APPLICATION_ID);
        if (isBlank(appId)) {
            LOGGER.warn("Undefined environment variable '{}'. Using default '{}'",
                    ENV_APPLICATION_ID, defaultApplicationId);
            appId = defaultApplicationId;
        }

        String clientId = System.getenv(ENV_CLIENT_ID);
        if (isBlank(clientId)) {
            LOGGER.warn("Undefined environment variable '{}'. Using default '{}'",
                    ENV_CLIENT_ID, defaultApplicationId);
            clientId = defaultClientId;
        }

        String offsetReset = System.getenv(ENV_OFFSET_RESET);
        if (isBlank(offsetReset)) {
            final String defaultOffset = "latest";
            LOGGER.warn("Undefined environment variable '{}'. Using default '{}'",
                    ENV_OFFSET_RESET, defaultOffset);
            offsetReset = defaultOffset;
        }

        Properties props = new Properties();
        props.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, String.valueOf(bootstrapServers));
        props.put(StreamsConfig.APPLICATION_ID_CONFIG, appId);
        props.put(StreamsConfig.CLIENT_ID_CONFIG, clientId);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, offsetReset);
        props.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.ByteArraySerde.class);
        props.put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, Serdes.ByteArraySerde.class);
        props.put(ConsumerConfig.EXCLUDE_INTERNAL_TOPICS_CONFIG, "false");
        props.put(StreamsConfig.PROCESSING_GUARANTEE_CONFIG, "exactly_once");
        return props;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isEmpty();
    }

    private static class ConsumerOffsetDetails {
        @JsonProperty
        private final String topic;
        @JsonProperty
        private final int partition;
        @JsonProperty
        private final String group;
        @JsonProperty
        private final int version;
        @JsonProperty
        private final Optional<Long> offset;
        @JsonProperty
        private final Optional<String> metadata;
        @JsonProperty
        private final Optional<Long> commitTimestamp;
        @JsonProperty
        private final Optional<Long> expireTimestamp;

        static ConsumerOffsetDetails from(OffsetKey k, Optional<OffsetAndMetadata> value) {
            GroupTopicPartition key = k.key();
            TopicPartition topicPartition = key.topicPartition();

            Optional<Long> offset = value.map(OffsetAndMetadata::offset);
            Optional<String> metadata = value.map(OffsetAndMetadata::metadata);
            Optional<Long> commitTimestamp = value.map(OffsetAndMetadata::commitTimestamp);

            Optional<Long> expireTimestamp =
                    value.map(offsetAndMetadata -> offsetAndMetadata.expireTimestamp().getOrElse(null));

            return new ConsumerOffsetDetails(
                    topicPartition.topic(),
                    topicPartition.partition(),
                    key.group(),
                    k.version(),
                    offset,
                    metadata,
                    commitTimestamp,
                    expireTimestamp
            );
        }

        private ConsumerOffsetDetails(
                String topic,
                int partition,
                String group,
                int version,
                Optional<Long> offset,
                Optional<String> metadata,
                Optional<Long> commitTimestamp,
                Optional<Long> expireTimestamp) {
            this.topic = topic;
            this.partition = partition;
            this.group = group;
            this.version = version;
            this.offset = offset;
            this.metadata = metadata;
            this.commitTimestamp = commitTimestamp;
            this.expireTimestamp = expireTimestamp;
        }

    }
}
