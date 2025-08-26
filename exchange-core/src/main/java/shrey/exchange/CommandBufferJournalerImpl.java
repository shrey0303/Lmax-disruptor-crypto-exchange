package shrey.exchange;

import shrey.bank.proto.TradingProto;
import shrey.exchange.cluster.LeaderProperties;
import lombok.SneakyThrows;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

public class CommandBufferJournalerImpl implements CommandBufferJournaler {

    private final KafkaProducer<String, byte[]> producer;
    private final CommandLogKafkaProperties commandLogKafkaProperties;
    private final LeaderProperties leaderProperties;

    private final Deque<List<TradingProto.TradingCommandLog>> buffers = new ArrayDeque<>();

    public CommandBufferJournalerImpl(
            KafkaProducer<String, byte[]> producer,
            CommandLogKafkaProperties commandLogKafkaProperties,
            LeaderProperties leaderProperties) {
        this.producer = producer;
        this.commandLogKafkaProperties = commandLogKafkaProperties;
        this.leaderProperties = leaderProperties;

        buffers.add(new ArrayList<>(leaderProperties.getLogsChunkSize()));
    }

    @Override
    public void onEvent(CommandBufferEvent event, long sequence, boolean endOfBatch) throws Exception {
        if (event == null || event.getCommand() == null)
            return;
        pushToBuffers(event);
        if (endOfBatch) {
            journalCommandLogs();
        }
    }

    private String extractPartitionKey(TradingProto.TradingCommandLog log) {
        return switch (log.getTypeCase()) {
            case PLACEORDERCOMMAND -> log.getPlaceOrderCommand().getSymbol();
            case CANCELORDERCOMMAND -> log.getCancelOrderCommand().getSymbol();
            default -> "__global__";
        };
    }

    private void pushToBuffers(CommandBufferEvent event) {
        if (buffers.getLast().size() == leaderProperties.getLogsChunkSize()) {
            buffers.addLast(new ArrayList<>(leaderProperties.getLogsChunkSize()));
        }
        buffers.getLast().add(event.getCommand().getCommandLog());
    }

    @SneakyThrows
    private void journalCommandLogs() {
        for (List<TradingProto.TradingCommandLog> commandLogs : buffers) {
            if (commandLogs.isEmpty())
                continue;

            var bySymbol = new java.util.HashMap<String, List<TradingProto.TradingCommandLog>>();
            for (var log : commandLogs) {
                bySymbol.computeIfAbsent(extractPartitionKey(log), k -> new ArrayList<>()).add(log);
            }

            for (var entry : bySymbol.entrySet()) {
                var commandLogsMessage = TradingProto.TradingCommandLogs.newBuilder()
                        .addAllLogs(entry.getValue())
                        .build();
                // Key = symbol → Kafka's DefaultPartitioner hashes this to a consistent
                // partition
                producer.send(new ProducerRecord<>(commandLogKafkaProperties.getTopic(), entry.getKey(),
                        commandLogsMessage.toByteArray())).get();
            }
        }
        buffers.clear();
        buffers.add(new ArrayList<>(leaderProperties.getLogsChunkSize()));
    }
}
