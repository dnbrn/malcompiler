package core.coverage;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.Collection;
import java.util.Map;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.Set;
import java.util.HashSet;
import java.util.TreeSet;

import org.junit.jupiter.api.extension.ExtensionContext;

import core.Asset;
import core.AttackStep;
import core.AttackStepMin;
import core.Defense;

import core.coverage.LanguageModel;

import java.lang.reflect.Field;

public class JSONTarget  extends CoverageExtension.ExportableTarget {
	public Map<ModelKey, Model> models = new HashMap<>();
	private PrintStream out = null;

	private String classname;
	private String testname;

	public JSONTarget() {
		out = null;
	}

	public JSONTarget(String filename) {
		createFile(filename);
	}

	// storing language model
	private LanguageModel languageModel;

	// storing all attack steps and defences for the language model
	private Set<String> allAttackSteps = new HashSet<>();
	private Set<String> allDefenses = new HashSet<>();

	@Override
	public void setup() {

	}

	/**
	 * Stores metadata about the test such as the name of the current 
	 * testclass and method.
	 */	
	@Override
	public void preprocess(ExtensionContext ctx) {
		testname = ctx.getDisplayName();
		testname = testname.substring(0, testname.indexOf('('));
		classname = ctx.getParent().map(s -> s.getDisplayName()).orElse("Unknown");
	
		if (out == null) {
			createFile(classname);
		}

		// infer package name
		// done here instead of setup() because of extension context
		// still only executed the first time due to condition
		if (CoverageExtension.packageName == null) {
			CoverageExtension.packageName = ctx.getRequiredTestClass().getPackageName();
		}

		if (languageModel == null) {
			buildLanguageModel();
		}
	}

	/**
	 * helper function building the language model after package name is known
	 */
	private void buildLanguageModel() {
		// check how often this is executed
		System.out.println("Building LanguageModel...");

		// extract all asset types from the DSL
		// executed one time before all tests
		Set<Class<? extends Asset>> assetTypes = getAllAssetTypesFromDSL();

		// create language model
		languageModel = new LanguageModel();

		// build language model by iterating through asset types
		for (Class<? extends Asset> assetClass : assetTypes) {
			String assetName = assetClass.getSimpleName();
			LanguageModel.AssetMetadata metadata = new LanguageModel.AssetMetadata();
			metadata.assetName = assetName;

			// extract all attack steps for current asset
			streamAssetAttackSteps(assetClass).forEach(f -> {
				metadata.assetAttackSteps.add(f.getName());
				allAttackSteps.add(assetName + "." + f.getName());
			});

			// extract all defences for current asset
			streamAssetDefense(assetClass).forEach(f -> {
				metadata.assetDefenses.add(f.getName());
				allDefenses.add(assetName + "." + f.getName());
			});

			// store asset with corresponding attack steps and defences in language model
			languageModel.assets.put(assetName, metadata);
		}
	}
	
	@Override
	public void processCoverage() {
		ModelKey key = new ModelKey();
		Model mdl = models.computeIfAbsent(key, s -> new Model());
		mdl.storeCurrentState();
	}
	
	@Override
	public void export() {
		boolean first = true;

		out.print('[');

		for (Model mdl : models.values()) {
			if (!first) {
				out.print(',');
			}

			first = false;

			out.print(mdl);
		}

		out.print(']');
		out.flush();
		out.close();
	}
	
	/**
	 * Creates the output file. Sets the printwriter (out) to
	 * point to the newly created file.	
	 *
	 * @param filename of the output file	
	 */
	private void createFile(String filename) {
		if (!filename.endsWith(".json")) {
			filename = String.format("%s.json", filename);
		}

		try {
			File file = new File(filename);
			file.createNewFile();

			out = new PrintStream(file);
		} catch (IOException e) {
			System.err.println(String.format("Failed to create file with name %s.", filename));
			e.printStackTrace();
		}
	}

	private class Model {
		List<Asset> assets = new ArrayList<>(Asset.allAssets);
		List<AttackStep> attackSteps = new ArrayList<>(AttackStep.allAttackSteps);
		List<Defense> defenses = new ArrayList<>(Defense.allDefenses);

		Map<Integer, Integer> stepAssetMap = new HashMap<>(AttackStep.allAttackSteps.size());

		// untested asset types
		Set<String> usedAssetTypes = new HashSet<>();
		Set<String> untestedAssetTypes = new HashSet<>();
		// attacksteps
		Set<String> usedAttackSteps = new HashSet<>();
		Set<String> untestedAttackSteps = new HashSet<>();
		// defences
		Set<String> usedDefenses = new HashSet<>();
		Set<String> untestedDefenses = new HashSet<>();

		// Stores simulations
		private Set<Sim> simulations = new HashSet<>();

		public Model() {
			for (Asset asset : assets) {
				for (AttackStep step : getAttackSteps(asset)) {
					stepAssetMap.put(step.hashCode(), asset.hashCode());
				}

				for (Defense def : getDefenses(asset)) {
					stepAssetMap.put(def.disable.hashCode(), asset.hashCode());
				}
			}
		}

		/**
		* Store the results of the current simulation. Does not 
		* check whether the model is correct.		
		*/
		public void storeCurrentState() {
			simulations.add(new Sim(classname, testname));

			// reset
			usedAssetTypes = new HashSet<>();
			usedAttackSteps = new HashSet<>();
			usedDefenses = new HashSet<>();

			// extract used attack steps and defences based on used asset types
			for (Asset asset : assets) {
				String assetClass = asset.getClass().getSimpleName();
				usedAssetTypes.add(assetClass);

				// attack steps
				for (AttackStep step : getAttackSteps(asset)) {
					if (step.ttc != AttackStep.infinity) {
						String stepName = assetClass + "." + getFieldName(asset, step);
						usedAttackSteps.add(stepName);
					}
				}

				// defences
				for (Defense def : getDefenses(asset)) {
					if (def.isEnabled()) {
						String defName = assetClass + "." + getFieldName(asset, def);
						usedDefenses.add(defName);
					}
				}
			}

			// calculate untested asset types
			untestedAssetTypes = new HashSet<>(languageModel.assets.keySet());
			untestedAssetTypes.removeAll(usedAssetTypes);

			// calculate untested attack steps
			untestedAttackSteps = new HashSet<>(allAttackSteps);
			untestedAttackSteps.removeAll(usedAttackSteps);

			// calculate untested defenses
			untestedDefenses = new HashSet<>(allDefenses);
			untestedDefenses.removeAll(usedDefenses);

		}

		/**
		 * helper function to get field name
		 * @param asset
		 * @param value
		 * @return
		 */
		private String getFieldName(Asset asset, Object value) {
			for (Field field : asset.getClass().getFields()) {
				try {
					if (field.get(asset) == value) {
						return field.getName();
					}
				} catch (IllegalAccessException ignored) {}
			}
			return "unknown";
		}

		@Override
		public String toString() {
			JSONObject json = new JSONObject();
			List<JSONObject> jAssets = new ArrayList<>(assets.size());

			for (Asset a : assets) {
				JSONObject jjson = new JSONObject();
				Set<Integer> connectedParentSteps = new HashSet<>();

				jjson.add("name", a.name);
				jjson.add("class", a.assetClassName);
				jjson.add("hash", a.hashCode());

				// Note: getAllAssociatedAssets does not work for
				// transitive relations.
				List<AttackStep> steps = getAttackSteps(a);
				List<JSONObject> jSteps = new ArrayList<>(steps.size());
				for (AttackStep step : steps) {
					JSONObject jObj = stepToJSON(step, connectedParentSteps);
					jObj.add("step", step.attackStepName());

					String type = step instanceof AttackStepMin ? "|" : "&";

					jObj.add("type", type);
					jSteps.add(jObj);
				}
				jjson.add("steps", jSteps);

				// Add defenses
				List<Defense> defs = getDefenses(a);
				List<JSONObject> jDefenses = new ArrayList<>(defs.size());
				for (Defense def : defs) {
					JSONObject jObj = stepToJSON(def.disable, connectedParentSteps);
					jObj.add("name", def.getClass().getSimpleName());

					jDefenses.add(jObj);
				}
				jjson.add("defense", jDefenses);

				connectedParentSteps.remove(a.hashCode());
				jjson.add("stepConnections", connectedParentSteps);
				jjson.add("connections", a.getAllAssociatedAssets().stream()
						  .map(asset -> asset.hashCode())
						  .collect(Collectors.toSet()));

				jAssets.add(jjson);
			}

			json.add("model", jAssets);
			json.add("simulations", simulations);

			// add total amounts of assets, attack steps and defences specified in DSL
			json.add("totalAssetTypes", languageModel.assets.size());
			json.add("totalAttackSteps", allAttackSteps.size());
			json.add("totalDefenses", allDefenses.size());

			// add untested assets, attack steps and defences
			json.add("untestedAssetTypes", new TreeSet<>(untestedAssetTypes));
			json.add("untestedAttackSteps", new TreeSet<>(untestedAttackSteps));
			json.add("untestedDefenses", new TreeSet<>(untestedDefenses));

			// calculate <asset type|attack step|defence> coverage on language level
			json.add("assetTypeCoverageLanguageLevel", calculateLanguageLevelCoverage(usedAssetTypes.size(), languageModel.assets.size()));
			json.add("attackStepCoverageLanguageLevel", calculateLanguageLevelCoverage(usedAttackSteps.size(), usedAttackSteps.size() + untestedAttackSteps.size()));
			json.add("defenseCoverageLanguageLevel", calculateLanguageLevelCoverage(usedDefenses.size(), usedDefenses.size() + untestedDefenses.size()));

			return json.toString();
		}

		/**
		 * helper function to calculate the coverage in percent for language level
		 *
		 * @param tested is the amount that is currently being tested
		 * @param totalInDSL is the amount specified in the DSL specification
		 * @return coverage in percent
		 */
		private double calculateLanguageLevelCoverage(int tested, int totalInDSL) {
			// avoid division by zero
			if (totalInDSL == 0) {
				return 1.0;
			}

			return ((double) tested) / totalInDSL;
		}

		private JSONObject stepToJSON(AttackStep step, Set<Integer> cParents) {
			JSONObject jStep = new JSONObject();

			Set<Integer> parents = Stream.concat(step.expectedParents.stream(),
													step.visitedParents.stream())
				.map(s -> s.hashCode())
				.collect(Collectors.toSet());

			cParents.addAll(parents.stream()
							.map(s -> stepAssetMap.get(s))
							.collect(Collectors.toSet()));

			jStep.add("hash", step.hashCode());
			jStep.add("parents", parents);

			return jStep;
		}
		
		/**
		* Class for storing simulation results.
		*/
		public class Sim {
			final Set<Integer> initiallyCompromised = new HashSet<>();
			final Set<Integer> activeDefenses;
			final Map<Integer, Double> compromised = new HashMap<>();

			final String clsName;
			final String mName;

			public Sim(String clsName, String mName) {
				this.clsName = clsName;
				this.mName = mName;

				List<AttackStep> compromised = AttackStep.allAttackSteps.stream()
					.filter(s -> s.ttc != AttackStep.infinity)
					.collect(Collectors.toList());

				for (AttackStep step : compromised) {
					int hash = step.hashCode();

					if (step.initiallyCompromised) {
						initiallyCompromised.add(hash);
					}

					this.compromised.put(hash, step.ttc);
				}

				activeDefenses = Defense.allDefenses.stream()
					.filter(d -> d.defaultValue)
					.map(d -> d.disable.hashCode())
					.collect(Collectors.toSet());
			}

			@Override
			public int hashCode() {
				final int prime = 31;
				int result = 1;
				result = prime * result + getEnclosingInstance().hashCode();
				result = prime * result + ((activeDefenses == null) ? 0 : activeDefenses.hashCode());
				result = prime * result + ((compromised == null) ? 0 : compromised.hashCode());
				result = prime * result + ((initiallyCompromised == null) ? 0 : initiallyCompromised.hashCode());
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
				Sim other = (Sim) obj;
				if (!getEnclosingInstance().equals(other.getEnclosingInstance()))
					return false;
				if (activeDefenses == null) {
					if (other.activeDefenses != null)
						return false;
				} else if (!activeDefenses.equals(other.activeDefenses))
					return false;
				if (compromised == null) {
					if (other.compromised != null)
						return false;
				} else if (!compromised.equals(other.compromised))
					return false;
				if (initiallyCompromised == null) {
					if (other.initiallyCompromised != null)
						return false;
				} else if (!initiallyCompromised.equals(other.initiallyCompromised))
					return false;
				return true;
			}

			private Model getEnclosingInstance() {
				return Model.this;
			}

			public String toString() {
				JSONObject json = new JSONObject();

				json.add("test", mName);
				json.add("class", clsName);
				json.add("initiallyCompromised", initiallyCompromised);
				json.add("activeDefenses", activeDefenses);

				List<JSONObject> jComp = new ArrayList<>(compromised.size());
				for (var entry : compromised.entrySet()) {
					JSONObject jObj = new JSONObject();

					jObj.add("id", entry.getKey());
					jObj.add("ttc", entry.getValue());
					
					jComp.add(jObj);
				}

				json.add("compromised", jComp);
				return json.toString();
			}
		}
	}

	private class JSONObject {
		private StringBuilder sb = new StringBuilder();
		private boolean first = true;

		public JSONObject() {
			sb.append('{');
		}

		private void addKey(String key) {
			if (!first) {
				sb.append(',');
			}

			first = false;

			sb.append('"').append(key).append("\":");
		}

		public void add(String key, Number n) {
			addKey(key);
			sb.append(n);
		}

		public void add(String key, JSONObject o) {
			addKey(key);
			sb.append(o);
		}

		public void add(String key, String s) {
			addKey(key);
			sb.append('"').append(s).append('"');
		}

		public void add(String key, Collection<? extends Object> c) {
			addKey(key);

			// changed to correctly formated unsued asset names as strings
			sb.append('[');
			boolean firstItem = true;

			for (Object item : c) {
				if (!firstItem) {
					sb.append(',');
				}
				if (item instanceof String) {
					sb.append('"').append(item).append('"');
				} else if (item instanceof JSONObject) {
					sb.append(item.toString());  // will be properly enclosed
				} else {
					sb.append(item);
				}
				firstItem = false;
			}

			sb.append(']');
		}

		@Override
		public String toString() {
			StringBuilder resb = new StringBuilder(sb);

			return resb.append('}').toString();
		}
	}
}
