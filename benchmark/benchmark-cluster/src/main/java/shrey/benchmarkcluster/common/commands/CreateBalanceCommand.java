package shrey.benchmarkcluster.common.commands;

import lombok.Data;

import java.util.UUID;

/**
 * @author shrey
 * @since 2024
 */
@Data
public class CreateBalanceCommand implements BalanceCommand {
    private final String correlationId = UUID.randomUUID().toString();
}
