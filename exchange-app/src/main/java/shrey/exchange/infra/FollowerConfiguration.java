package shrey.exchange.infra;

import com.lmax.disruptor.SleepingWaitStrategy;
import com.lmax.disruptor.dsl.Disruptor;
import shrey.exchange.infra.config.ClusterKafkaConfig;
import shrey.exchange.*;
import shrey.exchange.account.AccountRepository;
import shrey.exchange.domain.TradingWallets;
import shrey.exchange.domain.OrderBookManager;
import shrey.exchange.domain.CircuitBreaker;
import shrey.exchange.domain.LatencyTracker;
import shrey.exchange.domain.RiskEngine;
import shrey.exchange.cluster.FollowerBootstrap;
import shrey.exchange.cluster.FollowerProperties;
import shrey.exchange.offset.Offset;
import shrey.exchange.offset.SnapshotRepository;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
@Slf4j
@Profile("follower")
@Configuration
@RequiredArgsConstructor
public class FollowerConfiguration {

    private final SnapshotRepository snapshotRepository;
    private final AccountRepository accountRepository;
    private final TransactionManager transactionManager;
    private final ClusterKafkaConfig clusterKafkaConfig;

    private FollowerBootstrap followerBootstrap;

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
    StateMachineManager stateMachineManager(CommandLogConsumerProvider commandLogConsumerProvider, CommandHandler commandHandler, TradingWallets tradingWallets, Offset offset, CommandLogKafkaProperties commandLogKafkaProperties) {
        var stateMachine = new StateMachineManagerImpl(transactionManager, accountRepository, snapshotRepository, commandLogConsumerProvider, commandHandler, tradingWallets, offset);
        stateMachine.setCommandLogKafkaProperties(commandLogKafkaProperties);
        return stateMachine;
    }

    @Bean
    FollowerProperties followerProperties(
        @Value("${follower.bufferSize}") int bufferSize,
        @Value("${follower.pollInterval}") int pollInterval
    ) {
        var learnerProperties = new FollowerProperties();
        learnerProperties.setBufferSize(bufferSize);
        learnerProperties.setPollingInterval(pollInterval);
        return learnerProperties;
    }

    @Bean
    ReplayBufferEventDispatcher replayBufferEventDispatcher(Disruptor<ReplayBufferEvent> replayBufferEventDisruptor) {
        return new ReplayBufferEventDispatcherImpl(replayBufferEventDisruptor);
    }

    @Bean
    ReplayBufferHandler replayBufferHandlerByFollower(CommandHandler commandHandler) {
        return new ReplayBufferHandlerByFollower(commandHandler);
    }

    @Bean
    Disruptor<ReplayBufferEvent> replayBufferEventDisruptor(ReplayBufferHandler replayBufferHandler, FollowerProperties followerProperties) {
        return new ReplayBufferDisruptorDSL(replayBufferHandler).build(followerProperties.getBufferSize(), new SleepingWaitStrategy());
    }

    @Bean
    FollowerBootstrap followerBootstrap(
        StateMachineManager stateMachineManager,
        Disruptor<ReplayBufferEvent> replayBufferEventDisruptor,
        CommandLogConsumerProvider commandLogConsumerProvider,
        Offset offset,
        ReplayBufferEventDispatcher replayBufferEventDispatcher,
        FollowerProperties followerProperties,
        CommandLogKafkaProperties commandLogKafkaProperties
    ) {
        followerBootstrap = new FollowerBootstrap(
            stateMachineManager,
            replayBufferEventDisruptor,
            commandLogConsumerProvider,
            offset,
            replayBufferEventDispatcher,
            followerProperties,
            commandLogKafkaProperties
        );
        return followerBootstrap;
    }

    @EventListener(ApplicationReadyEvent.class)
    void startFollower() {
        log.info("Bootstrapping Follower");
        followerBootstrap.onStart();
    }

    @PreDestroy
    void stopFollower() {
        log.info("Destroying Follower");
        followerBootstrap.onStop();
    }

}
