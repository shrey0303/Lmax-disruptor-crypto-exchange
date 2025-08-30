package shrey.exchange;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @author shrey
 * @since 2024
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ReplyBufferEvent implements BufferEvent {
    private String replyChannel;
    private String correlationId;
    private BaseResult result;
}
