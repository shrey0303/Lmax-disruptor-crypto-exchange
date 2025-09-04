package shrey.exchange.replay;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.springframework.stereotype.Service;
import shrey.bank.proto.TradingProto;
import shrey.exchange.CommandLogConsumerProvider;
import shrey.exchange.CommandLogKafkaProperties;
import shrey.exchange.domain.Order;
import shrey.exchange.domain.OrderBookManager;

import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Service
@RequiredArgsConstructor
public class EventReplayService {

    private final CommandLogConsumerProvider consumerProvider;
    private final CommandLogKafkaProperties kafkaProperties;

    public Map<String, Object> replay(long fromOffset, long toOffset) {
        OrderBookManager tempBookManager = new OrderBookManager();
        AtomicLong mockOrderIdGenerator = new AtomicLong(0);
        
        try (KafkaConsumer<String, byte[]> consumer = consumerProvider.initConsumer(kafkaProperties)) {
            TopicPartition partition = new TopicPartition(kafkaProperties.getTopic(), 0);
            consumer.assign(Collections.singletonList(partition));
            consumer.seek(partition, fromOffset);
            
            long currentOffset = fromOffset;
            int recordsProcessed = 0;
            
            while (currentOffset < toOffset) { // process up to toOffset
                ConsumerRecords<String, byte[]> records = consumer.poll(Duration.ofMillis(1000));
                if (records.isEmpty()) {
                    break;
                }
                
                for (ConsumerRecord<String, byte[]> record : records) {
                    if (record.offset() > toOffset) {
                        break;
                    }
                    
                    try {
                        TradingProto.TradingCommandLogs batchLogs = TradingProto.TradingCommandLogs.parseFrom(record.value());
                        for (TradingProto.TradingCommandLog logEntry : batchLogs.getLogsList()) {
                            applyLogToOrderBook(logEntry, tempBookManager, mockOrderIdGenerator);
                        }
                        recordsProcessed++;
                    } catch (Exception e) {
                        log.warn("Failed to parse or apply record at offset {}", record.offset(), e);
                    }
                    currentOffset = record.offset();
                }
            }
            
            Map<String, Object> result = new HashMap<>();
            result.put("recordsProcessed", recordsProcessed);
            result.put("lastOffset", currentOffset);
            result.put("success", true);
            return result;
        } catch (Exception e) {
            log.error("Replay failed", e);
            throw new RuntimeException("Replay failed", e);
        }
    }
    
    private void applyLogToOrderBook(TradingProto.TradingCommandLog logEntry, OrderBookManager bookManager, AtomicLong idGenerator) {
        if (logEntry.hasPlaceOrderCommand()) {
            var cmd = logEntry.getPlaceOrderCommand();
            // In replay, we reconstruct the deterministic OrderID assignment
            long orderId = idGenerator.incrementAndGet();
            
            Order order = Order.builder()
                .id(orderId)
                .accountId(cmd.getAccountId())
                .symbol(cmd.getSymbol())
                .side(cmd.getSide())
                .type(cmd.getOrderType())
                .price(cmd.getPrice())
                .quantity(cmd.getQuantity())
                .filledQuantity(0)
                .timeInForce(cmd.getTimeInForce())
                .status(TradingProto.OrderStatus.NEW)
                .timestampNanos(cmd.getTimestampNanos())
                .build();
                
            bookManager.processOrder(order);
            
        } else if (logEntry.hasCancelOrderCommand()) {
            var cmd = logEntry.getCancelOrderCommand();
            bookManager.cancelOrder(cmd.getSymbol(), cmd.getOrderId(), cmd.getAccountId());
        }
    }
}
