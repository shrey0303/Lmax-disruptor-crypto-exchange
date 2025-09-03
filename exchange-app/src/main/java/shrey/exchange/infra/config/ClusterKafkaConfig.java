package shrey.exchange.infra.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * @author shrey
 * @since 2024
 */
@Data
@Component
@ConfigurationProperties(prefix = "cluster.kafka")
public class ClusterKafkaConfig {
    private String bootstrapServers;
    private String topic;
    private String groupId;
}
