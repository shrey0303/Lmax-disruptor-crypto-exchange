package shrey.exchange.infra.adapter;

import shrey.exchange.infra.config.ClusterKafkaConfig;
import shrey.exchange.CommandLogConsumerProvider;
import shrey.exchange.CommandLogKafkaProperties;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.Properties;

/**
 * @author shrey
 * @since 2024
 */
@Component
@Primary
@RequiredArgsConstructor
public class CommandLogConsumerProviderAdapter implements CommandLogConsumerProvider {

    private final ClusterKafkaConfig clusterKafkaConfig;

    @Override
    public KafkaConsumer<String, byte[]> initConsumer(CommandLogKafkaProperties properties) {
        var props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, clusterKafkaConfig.getBootstrapServers());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, properties.getGroupId());
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 1_000);
        return new KafkaConsumer<>(props);
    }
}
