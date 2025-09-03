package shrey.exchange.infra;

import jakarta.persistence.EntityManager;

public class EntityManagerContextHolder {
    public static ThreadLocal<EntityManager> CONTEXT = new ThreadLocal<>();
}
