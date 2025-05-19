package core.coverage;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class CoverageCollector {
    private static final CoverageCollector instance = new CoverageCollector();
    private final Set<String> covered = ConcurrentHashMap.newKeySet();

    private CoverageCollector() {}

    public static CoverageCollector getInstance() {
        return instance;
    }

    public void register(String attackStepId) {
        covered.add(attackStepId);
    }

    public Set<String> getCoveredElements() {
        return covered;
    }
}
