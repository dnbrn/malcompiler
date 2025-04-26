package core.coverage;

import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.util.HashSet;

import org.junit.jupiter.api.extension.ExtensionContext;

import core.Asset;
import core.AttackStep;
import core.AttackStepMin;
import core.Defense;

/**
 * class to model the MAL-based DSL on a language level
 * models all assets with their respective attack steps and defences
 */
public class LanguageModel {
    public static class AssetMetadata {
        public String assetName;
        public Set<String> assetAttackSteps = new HashSet<>();
        public Set<String> assetDefenses = new HashSet<>();
    }

    public Map<String, AssetMetadata> assets = new HashMap<>();
}