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

        public Set<AssociationMetadata> assetAssociations = new HashSet<>();
    }

    public static class AssociationMetadata {
        public String leftAsset;
        public String leftField;
        public String leftMultiplicity;
        public String rightMultiplicity;
        public String rightField;
        public String rightAsset;

        // adjusted to meet "Host [host] 1 <-- Credentials --> * [passwords] Password" format without name
        @Override
        public String toString() {
            return String.format("%s [%s] %s <--> %s [%s] %s",
                    leftAsset, leftField, leftMultiplicity, rightMultiplicity, rightField, rightAsset);
        }

        @Override
        public int hashCode() {
            return (toString()).hashCode();
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof AssociationMetadata)) return false;

            AssociationMetadata other = (AssociationMetadata) obj;
            return leftAsset.equals(other.leftAsset)
                    && leftMultiplicity.equals(other.leftMultiplicity)
                    && leftField.equals(other.leftField)
                    && rightMultiplicity.equals(other.rightMultiplicity)
                    && rightField.equals(other.rightField)
                    && rightAsset.equals(other.rightAsset);
        }
    }

    public static class UnmergedAssociation {
        public String sourceAsset;
        public String sourceField;
        public String sourceMultiplicity;
        public String targetAsset;

        // meet onsided format of "Host --> * [passwords] Password"
        @Override
        public String toString() {
            return String.format("%s --> %s [%s] %s",
                    sourceAsset, sourceMultiplicity, sourceField, targetAsset);
        }

        @Override
        public int hashCode() {
            return (toString()).hashCode();
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof UnmergedAssociation)) return false;

            UnmergedAssociation other = (UnmergedAssociation) obj;
            return sourceAsset.equals(other.sourceAsset)
                    && sourceField.equals(other.sourceField)
                    && sourceMultiplicity.equals(other.sourceMultiplicity)
                    && targetAsset.equals(other.targetAsset);
        }
    }

    public Map<String, AssetMetadata> assets = new HashMap<>();
    public Set<UnmergedAssociation> allUnmergedAssociations = new HashSet<>();
    public Set<AssociationMetadata> mergedAssociations = new HashSet<>();
}