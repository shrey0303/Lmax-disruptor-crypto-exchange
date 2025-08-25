package shrey.exchange;

import lombok.Data;

/**
 * @author shrey
 * @since 2024
 */
@Data
public class CommandLogKafkaProperties {
    private String topic;
    private String groupId;
    private long nextOffset;
}
