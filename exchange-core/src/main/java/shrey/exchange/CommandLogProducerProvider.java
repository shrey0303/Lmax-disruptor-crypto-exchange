package shrey.exchange;

import org.apache.kafka.clients.producer.KafkaProducer;

/**
 * @author shrey
 * @since 2024
 */
public interface CommandLogProducerProvider {
    KafkaProducer<String, byte[]> initProducer(CommandLogKafkaProperties properties);
}
