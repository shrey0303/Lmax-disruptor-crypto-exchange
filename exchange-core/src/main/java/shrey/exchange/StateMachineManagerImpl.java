package shrey.exchange;

import shrey.bank.proto.TradingProto;
import shrey.exchange.account.AccountRepository;
import shrey.exchange.domain.TradingWallets;
import shrey.exchange.offset.Offset;
import shrey.exchange.offset.SnapshotRepository;
import shrey.common.exception.ExchangeException;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.common.TopicPartition;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

@Slf4j
@RequiredArgsConstructor
public class StateMachineManagerImpl implements StateMachineManager {

    private final TransactionManager transactionManager;
    private final AccountRepository accountRepository;
    private final SnapshotRepository snapshotRepository;
    private final CommandLogConsumerProvider commandLogConsumerProvider;
    private final CommandHandler commandHandler;
    private final TradingWallets wallets;
    private final Offset offset;

    @Setter
    private CommandLogKafkaProperties commandLogKafkaProperties;

    private StateMachineStatus status = StateMachineStatus.INITIALIZING;

    @Override
    public StateMachineStatus getStatus() {
        return status;
    }

    @Override
    public void reloadSnapshot() {
        if (status != StateMachineStatus.INITIALIZING) {
            throw new ExchangeException("Cannot reload snapshot when status is not INITIALIZING");
        }
        status = StateMachineStatus.LOADING_SNAPSHOT;

        transactionManager.doInNewTransaction(() -> {
            accountRepository.wallets().forEach(wallets::putWallet);
            wallets.setLastedId(Optional.ofNullable(accountRepository.lastedId()).orElse(0L));
            offset.setOffset(Optional.ofNullable(snapshotRepository.getLastOffset()).orElse(-1L));
            commandLogKafkaProperties.setNextOffset(offset.nextOffset());
        });

        status = StateMachineStatus.LOADED_SNAPSHOT;
        log.info("Loaded snapshot with offset: {}, lastedId: {}", offset.currentLastOffset(), wallets.getLastedId());
    }

    @SneakyThrows
    @Override
    public void replayCommandLogs() {
        if (status != StateMachineStatus.LOADED_SNAPSHOT) {
            throw new ExchangeException("Cannot replay logs when status is not LOADED_SNAPSHOT");
        }
        status = StateMachineStatus.REPLAYING_LOGS;

        try (var consumer = commandLogConsumerProvider.initConsumer(commandLogKafkaProperties)) {
            var partition = new TopicPartition(commandLogKafkaProperties.getTopic(), 0);
            consumer.assign(List.of(partition));
            consumer.seek(partition, offset.nextOffset());
            for (;;) {
                var commandLogsRecords = consumer.poll(Duration.ofMillis(100));
                if (commandLogsRecords.isEmpty())
                    break;
                for (var commandLogsRecord : commandLogsRecords) {
                    TradingProto.TradingCommandLogs
                            .parseFrom(commandLogsRecord.value())
                            .getLogsList()
                            .forEach(commandLog -> commandHandler.onCommand(new BaseCommand(commandLog)));
                    offset.setOffset(commandLogsRecord.offset());
                }
            }
            consumer.commitSync();
            status = StateMachineStatus.REPLAYED_LOGS;
        }

        status = StateMachineStatus.ACTIVE;
        log.info("Replayed logs from offset: {}", offset.nextOffset());
    }

    @Override
    public void takeSnapshot() {
        transactionManager.doInNewTransaction(() -> {
            if (status != StateMachineStatus.ACTIVE) {
                throw new ExchangeException("Cannot take snapshot when status is not ACTIVE");
            }
            snapshotRepository.persistLastOffset(offset.currentLastOffset());
            accountRepository.persistWallets(wallets.getChangedWallets());
            accountRepository.persistLastId(wallets.getLastedId());
            wallets.clearChangedWallets();
            log.info("Took snapshot to offset: {}", offset.currentLastOffset());
        });
    }

    @Override
    public void active() {
        status = StateMachineStatus.ACTIVE;
    }
}
