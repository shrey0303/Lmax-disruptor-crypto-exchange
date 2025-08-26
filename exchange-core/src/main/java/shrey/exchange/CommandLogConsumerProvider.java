package shrey.exchange;

import org.apache.kafka.clients.consumer.KafkaConsumer;

/**
 * @author shrey
 * @since 2024
 */
public interface CommandLogConsumerProvider {
    KafkaConsumer<String, byte[]> initConsumer(CommandLogKafkaProperties properties);
}
