package shrey.exchange.infra.adapter;

import jakarta.persistence.EntityManager;
import shrey.exchange.infra.EntityManagerContextHolder;
import shrey.exchange.infra.adapter.entities.SnapshotEntity;
import shrey.exchange.infra.adapter.entities.SnapshotType;
import shrey.exchange.account.AccountRepository;
import shrey.exchange.domain.TradingWallet;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

@Component
@RequiredArgsConstructor
public class AccountRepositoryAdapter implements AccountRepository {

    private final SnapshotRepositoryJpa snapshotRepositoryJpa;

    @Override
    public Stream<TradingWallet> wallets() {
        EntityManager entityManager = EntityManagerContextHolder.CONTEXT.get();
        List<Object[]> walletRows = entityManager.createNativeQuery("SELECT id FROM trading_wallets").getResultList();
        
        Map<Long, TradingWallet> walletMap = new HashMap<>();
        for (Object[] row : walletRows) {
            long id = ((Number) row[0]).longValue();
            walletMap.put(id, new TradingWallet(id, new HashMap<>(), new HashMap<>()));
        }

        List<Object[]> balanceRows = entityManager.createNativeQuery("SELECT wallet_id, asset, amount FROM trading_wallet_balances").getResultList();
        for (Object[] row : balanceRows) {
            long walletId = ((Number) row[0]).longValue();
            String asset = (String) row[1];
            long amount = ((Number) row[2]).longValue();
            walletMap.get(walletId).getBalances().put(asset, amount);
        }

        List<Object[]> holdRows = entityManager.createNativeQuery("SELECT wallet_id, asset, amount FROM trading_wallet_holds").getResultList();
        for (Object[] row : holdRows) {
            long walletId = ((Number) row[0]).longValue();
            String asset = (String) row[1];
            long amount = ((Number) row[2]).longValue();
            walletMap.get(walletId).getHolds().put(asset, amount);
        }

        return walletMap.values().stream();
    }

    @Override
    public Long lastedId() {
        return snapshotRepositoryJpa.findById(SnapshotType.LAST_BALANCE_ID.getType())
            .map(SnapshotEntity::getValue)
            .map(Long::parseLong)
            .orElse(0L);
    }

    @Override
    public void persistWallets(List<TradingWallet> wallets) {
        if (wallets == null || wallets.isEmpty()) {
            return;
        }
        EntityManager entityManager = EntityManagerContextHolder.CONTEXT.get();

        entityManager.createNativeQuery("CREATE TEMPORARY TABLE temp_wallets (id BIGINT PRIMARY KEY);").executeUpdate();

        StringBuilder walletValues = new StringBuilder();
        StringBuilder balanceValues = new StringBuilder();
        StringBuilder holdValues = new StringBuilder();

        for (int i = 0; i < wallets.size(); i++) {
            TradingWallet w = wallets.get(i);
            
            if (!walletValues.isEmpty()) walletValues.append(",");
            walletValues.append(String.format("(%s)", w.getAccountId()));

            for (Map.Entry<String, Long> entry : w.getBalances().entrySet()) {
                if (!balanceValues.isEmpty()) balanceValues.append(",");
                balanceValues.append(String.format("(%d, '%s', %d)", w.getAccountId(), entry.getKey(), entry.getValue()));
            }

            for (Map.Entry<String, Long> entry : w.getHolds().entrySet()) {
                if (!holdValues.isEmpty()) holdValues.append(",");
                holdValues.append(String.format("(%d, '%s', %d)", w.getAccountId(), entry.getKey(), entry.getValue()));
            }
        }

        if (!walletValues.isEmpty()) {
            entityManager.createNativeQuery("INSERT INTO temp_wallets VALUES " + walletValues + ";").executeUpdate();
            entityManager.createNativeQuery("INSERT IGNORE INTO trading_wallets (id) SELECT id FROM temp_wallets;").executeUpdate();
        }

        if (!balanceValues.isEmpty()) {
            entityManager.createNativeQuery("CREATE TEMPORARY TABLE temp_balances (wallet_id BIGINT, asset VARCHAR(16), amount BIGINT);").executeUpdate();
            entityManager.createNativeQuery("INSERT INTO temp_balances VALUES " + balanceValues + ";").executeUpdate();
            entityManager.createNativeQuery("DELETE FROM trading_wallet_balances WHERE wallet_id IN (SELECT id FROM temp_wallets);").executeUpdate();
            entityManager.createNativeQuery("INSERT INTO trading_wallet_balances SELECT * FROM temp_balances;").executeUpdate();
            entityManager.createNativeQuery("DROP TEMPORARY TABLE temp_balances;").executeUpdate();
        } else if (!walletValues.isEmpty()) {
             entityManager.createNativeQuery("DELETE FROM trading_wallet_balances WHERE wallet_id IN (SELECT id FROM temp_wallets);").executeUpdate();
        }

        if (!holdValues.isEmpty()) {
            entityManager.createNativeQuery("CREATE TEMPORARY TABLE temp_holds (wallet_id BIGINT, asset VARCHAR(16), amount BIGINT);").executeUpdate();
            entityManager.createNativeQuery("INSERT INTO temp_holds VALUES " + holdValues + ";").executeUpdate();
            entityManager.createNativeQuery("DELETE FROM trading_wallet_holds WHERE wallet_id IN (SELECT id FROM temp_wallets);").executeUpdate();
            entityManager.createNativeQuery("INSERT INTO trading_wallet_holds SELECT * FROM temp_holds;").executeUpdate();
            entityManager.createNativeQuery("DROP TEMPORARY TABLE temp_holds;").executeUpdate();
        } else if (!walletValues.isEmpty()) {
            entityManager.createNativeQuery("DELETE FROM trading_wallet_holds WHERE wallet_id IN (SELECT id FROM temp_wallets);").executeUpdate();
        }

        entityManager.createNativeQuery("DROP TEMPORARY TABLE temp_wallets;").executeUpdate();
    }

    @Override
    public void persistLastId(Long id) {
        var entityManager = EntityManagerContextHolder.CONTEXT.get();
        entityManager.createQuery("UPDATE SnapshotEntity s SET s.value = :value WHERE s.id = :id")
            .setParameter("value", id.toString())
            .setParameter("id", SnapshotType.LAST_BALANCE_ID.getType())
            .executeUpdate();
    }
}
