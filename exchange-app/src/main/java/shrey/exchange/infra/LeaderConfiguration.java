package shrey.exchange.infra;

import com.lmax.disruptor.YieldingWaitStrategy;
import com.lmax.disruptor.dsl.Disruptor;
import shrey.exchange.infra.adapter.CommandLogConsumerProviderAdapter;
import shrey.exchange.infra.config.ClusterKafkaConfig;
import shrey.exchange.*;
import shrey.exchange.account.AccountRepository;
import shrey.exchange.domain.TradingWallets;
import shrey.exchange.domain.OrderBookManager;
import shrey.exchange.domain.CircuitBreaker;
import shrey.exchange.domain.LatencyTracker;
import shrey.exchange.domain.RiskEngine;
import shrey.exchange.cluster.LeaderBootstrap;
import shrey.exchange.cluster.LeaderProperties;
import shrey.exchange.offset.Offset;
import shrey.exchange.offset.SnapshotRepository;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;

/**
 * @author shrey
 * @since 2024
 */
@Profile("leader")
@Configuration
@RequiredArgsConstructor
public class LeaderConfiguration {

    private static final Logger log = LoggerFactory.getLogger(LeaderConfiguration.class);
    private final ClusterKafkaConfig clusterKafkaConfig;
    private final TransactionManager transactionManager;
    private final SnapshotRepository snapshotRepository;
    private final AccountRepository accountRepository;
    private final CommandLogProducerProvider commandLogProducerProvider;

    private LeaderBootstrap leaderBootstrap;

    @Bean
    LeaderProperties leaderProperties(
        @Value("${leader.commandBufferPow}") int commandBufferPow,
        @Value("${leader.replyBufferPow}") int replyBufferPow,
        @Value("${leader.logsChunkSize}") int logsChunkSize
    ) {
        return new LeaderProperties(1 << commandBufferPow, 1 << replyBufferPow, logsChunkSize);
    }

    @Bean
    CommandLogKafkaProperties commandLogKafkaProperties() {
        var properties = new CommandLogKafkaProperties();
        properties.setTopic(clusterKafkaConfig.getTopic());
        properties.setGroupId(clusterKafkaConfig.getGroupId());
        return properties;
    }

    @Bean
    public TradingWallets tradingWallets() {
        var balances = new TradingWallets();
        balances.setLastedId(accountRepository.lastedId());
        return balances;
    }
    
    @Bean
    public OrderBookManager orderBookManager() {
        return new OrderBookManager();
    }
    
    @Bean
    public CircuitBreaker circuitBreaker() {
        return new CircuitBreaker();
    }

    @Bean
    public LatencyTracker latencyTracker() {
        return new LatencyTracker();
    }

    @Bean
    public RiskEngine riskEngine(CircuitBreaker cb) {
        return new RiskEngine(cb);
    }

    @Bean
    public Offset offset() {
        var offset = new Offset();
        offset.setOffset(snapshotRepository.getLastOffset());
        return offset;
    }

    @Bean
    KafkaProducer<String, byte[]> producer() {
        return commandLogProducerProvider.initProducer(null);
    }

    @Bean
    CommandLogConsumerProvider commandLogConsumerProvider() {
        return new CommandLogConsumerProviderAdapter(clusterKafkaConfig);
    }

    @Bean
    RiskValidationHandler riskValidationHandler(TradingWallets tradingWallets, RiskEngine riskEngine) {
        return new RiskValidationHandler(tradingWallets, riskEngine);
    }

    @Bean
    MatchingHandler matchingHandler(OrderBookManager orderBookManager) {
        return new MatchingHandler(orderBookManager);
    }

    @Bean
    SettlementHandler settlementHandler(TradingWallets tradingWallets, RiskEngine riskEngine, CircuitBreaker cb, LatencyTracker latencyTracker) {
        return new SettlementHandler(tradingWallets, riskEngine, cb, latencyTracker);
    }

    @Bean
    CommandHandler commandHandler(RiskValidationHandler riskHandler, MatchingHandler matchHandler, SettlementHandler settleHandler) {
        return new CommandHandlerImpl(riskHandler, matchHandler, settleHandler);
    }

    @Bean
    CommandBufferJournaler commandBufferJournaler(KafkaProducer<String, byte[]> producer, CommandLogKafkaProperties commandLogKafkaProperties, LeaderProperties leaderProperties) {
        return new CommandBufferJournalerImpl(producer, commandLogKafkaProperties, leaderProperties);
    }

    @Bean
    CommandBufferHandler commandBufferHandler(CommandHandler commandHandler) {
        return new CommandBufferHandlerImpl(commandHandler);
    }

    @Bean
    ReplyBufferEventDispatcher replyBufferEventDispatcher(Disruptor<ReplyBufferEvent> replyBufferEventDisruptor) {
        return new ReplyBufferEventDispatcherImpl(replyBufferEventDisruptor);
    }

    @Bean
    CommandBufferReply commandBufferReply(ReplyBufferEventDispatcher replyBufferEventDispatcher) {
        return new CommandBufferReplyImpl(replyBufferEventDispatcher);
    }

    @Bean
    Disruptor<CommandBufferEvent> commandBufferDisruptor(RiskValidationHandler riskHandler, CommandBufferJournaler journaler, MatchingHandler matchHandler, SettlementHandler settleHandler, CommandBufferReply reply, LeaderProperties leaderProperties) {
        return new TradingCommandBufferDisruptorDSL(riskHandler, journaler, matchHandler, settleHandler, reply).build(leaderProperties.getCommandBufferSize(), new YieldingWaitStrategy());
    }

    @Bean
    CommandBufferEventDispatcher commandBufferEventDispatcher(Disruptor<CommandBufferEvent> commandBufferEventDisruptor) {
        return new CommandBufferEventDispatcherImpl(commandBufferEventDisruptor);
    }

    @Bean
    Disruptor<ReplyBufferEvent> replyBufferEventDisruptor(ReplyBufferHandler replyBufferHandler, LeaderProperties leaderProperties) {
        return new ReplyBufferDisruptorDSL(replyBufferHandler).build(leaderProperties.getReplyBufferSize(), new YieldingWaitStrategy());
    }

    @Bean
    StateMachineManager stateMachineManager(CommandLogConsumerProvider commandLogConsumerProvider, CommandHandler commandHandler, TradingWallets tradingWallets, Offset offset, CommandLogKafkaProperties commandLogKafkaProperties) {
        var stateMachine = new StateMachineManagerImpl(transactionManager, accountRepository, snapshotRepository, commandLogConsumerProvider, commandHandler, tradingWallets, offset);
        stateMachine.setCommandLogKafkaProperties(commandLogKafkaProperties);
        return stateMachine;
    }

    @Bean
    LeaderBootstrap leaderBootstrap(StateMachineManager stateMachineManager, Disruptor<CommandBufferEvent> commandBufferDisruptor, Disruptor<ReplyBufferEvent> replyBufferEventDisruptor) {
        leaderBootstrap = new LeaderBootstrap(stateMachineManager, commandBufferDisruptor, replyBufferEventDisruptor);
        return leaderBootstrap;
    }

    @EventListener(ApplicationReadyEvent.class)
    void startLeader() {
        log.info("Bootstrapping leader");
        leaderBootstrap.onStart();
    }

    @PreDestroy
    void stopLeader() {
        log.info("Destroying leader");
        leaderBootstrap.onStop();
    }
}
