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
import shrey.exchange.cluster.LearnerBootstrap;
import shrey.exchange.cluster.LearnerProperties;
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

import java.time.Duration;

/**
 * @author shrey
 * @since 2024
 */
@Slf4j
@Profile("learner")
@Configuration
@RequiredArgsConstructor
public class LearnerConfiguration {

    private final SnapshotRepository snapshotRepository;
    private final AccountRepository accountRepository;
    private final ClusterKafkaConfig clusterKafkaConfig;
    private final TransactionManager transactionManager;

    private LearnerBootstrap learnerBootstrap;

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
        balances.setEnableChangedCapture(true);
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
    LearnerProperties learnerProperties(
        @Value("${learner.bufferSize}") int bufferSize,
        @Value("${learner.pollInterval}") int pollInterval,
        @Value("${learner.maxSnapshotCheckCircles}") int maxSnapshotCheckCircles,
        @Value("${learner.snapshotFragmentSize}") int snapshotFragmentSize,
        @Value("${learner.snapshotLifeTime}") int snapshotLifeTime
    ) {
        var learnerProperties = new LearnerProperties();
        learnerProperties.setBufferSize(bufferSize);
        learnerProperties.setPollingInterval(pollInterval);
        learnerProperties.setMaxSnapshotCheckCircles(maxSnapshotCheckCircles);
        learnerProperties.setSnapshotFragmentSize(snapshotFragmentSize);
        learnerProperties.setSnapshotLifeTime(Duration.ofSeconds(snapshotLifeTime));
        return learnerProperties;
    }

    @Bean
    ReplayBufferHandler replayBufferHandlerByLearner(CommandHandler commandHandler, StateMachineManager stateMachineManager, LearnerProperties learnerProperties) {
        var replayHandler = new ReplayBufferHandlerByLearner(commandHandler, stateMachineManager, learnerProperties);
        replayHandler.setEventCount(learnerProperties.getBufferSize());
        return replayHandler;
    }

    @Bean
    Disruptor<ReplayBufferEvent> replayBufferEventDisruptor(ReplayBufferHandler replayBufferHandler) {
        return new ReplayBufferDisruptorDSL(replayBufferHandler).build(1 << 5, new SleepingWaitStrategy());
    }

    @Bean
    LearnerBootstrap learnerBootstrap(
        StateMachineManager stateMachineManager,
        Disruptor<ReplayBufferEvent> replayBufferEventDisruptor,
        CommandLogConsumerProvider commandLogConsumerProvider,
        Offset offset,
        CommandLogKafkaProperties commandLogKafkaProperties,
        ReplayBufferEventDispatcher replayBufferEventDispatcher,
        LearnerProperties learnerProperties
    ) {
        learnerBootstrap = new LearnerBootstrap(
            stateMachineManager,
            replayBufferEventDisruptor,
            commandLogConsumerProvider,
            offset,
            replayBufferEventDispatcher,
            learnerProperties
        );
        learnerBootstrap.setCommandLogKafkaProperties(commandLogKafkaProperties);
        return learnerBootstrap;
    }

    @Bean
    ReplayBufferEventDispatcher replayBufferEventDispatcher(Disruptor<ReplayBufferEvent> replayBufferEventDisruptor) {
        return new ReplayBufferEventDispatcherImpl(replayBufferEventDisruptor);
    }

    @EventListener(ApplicationReadyEvent.class)
    void startLearner() {
        log.info("Bootstrapping learner");
        learnerBootstrap.onStart();
    }

    @PreDestroy
    void stopLearner() {
        log.info("Destroying learner");
        learnerBootstrap.onStop();
    }

}
