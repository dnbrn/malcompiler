package core.coverage;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.lang.reflect.Field;
import java.util.Arrays;

import org.junit.jupiter.api.extension.BeforeTestExecutionCallback;
import org.junit.jupiter.api.extension.AfterTestExecutionCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import core.Asset;
import core.AttackStep;
import core.Defense;

import static org.junit.jupiter.api.extension.ExtensionContext.Namespace.GLOBAL;

public class CoverageExtension implements AfterTestExecutionCallback,  BeforeTestExecutionCallback, ExtensionContext.Store.CloseableResource {
    protected ExportableTarget _export;

    private static ExportableTarget _globalTarget;
    private static boolean started;

	private boolean _initLocal;
    
    /**
     * Initialize the coverage extension to use the global export
     * target (single file).
     */
    public CoverageExtension() {
        if (_globalTarget == null) {
            // Override to change the global export target
            _globalTarget = new JSONTarget("coverage.json"); 
        }

        _export = _globalTarget;
    }

    /**
     * Initialize the coverage extension to use a local export 
     * target.
     * 
     * @param target local export taraget format.
     */
    public CoverageExtension(ExportableTarget target) {
        _export = target;
    }

    @Override
    public void beforeTestExecution(ExtensionContext context) {
        if (!started && _globalTarget != null && _export == _globalTarget) {
            started = true;

            // Adds a root context hook (ensures that close is run
            // after all tests have been executed).
            context.getRoot().getStore(GLOBAL).put("mal-coverage-root-context-hook", this);
			_globalTarget.setup();
        }

		// Initialize local targets
		if (!_initLocal && _export != _globalTarget) {
			_initLocal = true;

			// Binds the local target to the class-level context
			// including the hashscode allows multiple extensions to be registered
			// with the same context store.
			context.getParent().ifPresent(pCtx -> {
				String key = _export.getClass().getSimpleName();
				CoverageExtension current = this;

				CoverageExtension ext = (CoverageExtension) pCtx.getStore(GLOBAL)
					.getOrComputeIfAbsent(key, s -> { return current; }, CoverageExtension.class);

				_export = ext._export;

				if (ext.equals(current))
					_export.setup();
			});
		}

        _export.preprocess(context);
    }

    @Override
    public void afterTestExecution(ExtensionContext context) {
        _export.processCoverage();
    }

    /**
     * Export data from global target (when junit instance closes).
     */
    @Override 
    public void close() {
        _export.export();
    }

    /**
     * Interface for defining exportable coverage targets.
     */
    public static abstract class ExportableTarget {
		// Called when the target first is initialized
		public abstract void setup();
        // Called before every test .
        public abstract void preprocess(ExtensionContext ctx);  
        // Called after every test (compute coverage).
        public abstract void processCoverage();
        // Called when all scheduled tests have been executed.
        public abstract void export();

        /**
         * Returns a stream of all attack steps classes present 
         * in an asset class.
         * 
         * @param a asset
         * @return attack steps declared for asset a
         */
        protected Stream<Field> streamAssetAttackSteps(Class assetClass) {
            return Arrays.stream(assetClass.getFields())
                .filter(e -> AttackStep.class.isAssignableFrom(e.getType()));
        }

        /**
         * Return a stream of all defense classes present in an
         * asset class.
         * 
         * @param a asset 
         * @return defenses defined for asset a
         */
        protected Stream<Field> streamAssetDefense(Class assetClass) {
            return Arrays.stream(assetClass.getFields())
                .filter(c -> Defense.class.isAssignableFrom(c.getType()));
        }

        /**
         * Returns the attack step objects associated with a specific
         * asset.
         * 
         * @param asset asset.
         * @return attack steps associated with a.
         */
        protected List<AttackStep> getAttackSteps(Asset asset) {
            return streamAssetAttackSteps(asset.getClass())
                .map(f -> {
                    AttackStep step;

                    try {
                        step = ((AttackStep) f.get(asset));
                    } catch(Exception e) {
                        step = null;
                    }

                    return step;
                }).filter(s -> s != null)
                .collect(Collectors.toList());
        }

		/**
		 * Returns all defense objects associated with the
		 * specifed asset.
		 *		
		 * @param asset asset.
		 * @return defense object associated with asset.		
		 */
		protected List<Defense> getDefenses(Asset asset) {
			return streamAssetDefense(asset.getClass())
				.map(f -> {
						Defense def;
		
						try {
							def = (Defense) f.get(asset);
						} catch(Exception e) {
							def = null;
						}
		
						return def;
					}).filter(d -> d != null)
				.collect(Collectors.toList());
		 }
		
		/**
		 * Class used for indexing simulated models.
		 */
		protected static class ModelKey {
			public final int assetListHash;
			public final int attackListHash;
			public final int defenseListHash;

			/**
			 * Constructs a ModelKey from the hash codes of the asset, attackstep
			 * and defenses list.
			 */
			public ModelKey() {
				this(Asset.allAssets.hashCode(), AttackStep.allAttackSteps.hashCode(), Defense.allDefenses.hashCode());
			}

			/**
			 * Constructs a ModelKey from the specified hash codes.
			 *
			 * @param assetHash - hash code for all assets registered in the model.
			 * @param attackHash - hash code for all registered attack steps.
			 * @param defenseHash - hash code for all registered defenses.
			 */
			public ModelKey(int assetHash, int attackHash, int defenseHash) {
				assetListHash = assetHash;
				attackListHash = attackHash;
				defenseListHash = defenseHash;
			}

			/**
			 * Constructs a hash code from the hash fields [Automatically generated].
			 *
			 * @return hash code constructed from the hash fields.
			 */
			@Override
			public int hashCode() {
				final int prime = 31;
				int result = 1;
				result = prime * result + assetListHash;
				result = prime * result + attackListHash;
				result = prime * result + defenseListHash;
				return result;
			}

			@Override
			public boolean equals(Object obj) {
				if (this == obj)
					return true;
				if (obj == null)
					return false;
				if (getClass() != obj.getClass())
					return false;
				ModelKey other = (ModelKey) obj;
				if (assetListHash != other.assetListHash)
					return false;
				if (attackListHash != other.attackListHash)
					return false;
				if (defenseListHash != other.defenseListHash)
					return false;
				return true;
			}
		}

	}
}
