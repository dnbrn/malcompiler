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

import org.junit.jupiter.api.extension.ExtensionContext;

import core.Asset;
import core.AttackStep;
import core.AttackStepMin;
import core.Defense;

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

	@Override
	public void setup() {}

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

			return json.toString();
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
			sb.append(c.toString());
		}

		@Override
		public String toString() {
			StringBuilder resb = new StringBuilder(sb);

			return resb.append('}').toString();
		}
	}
}
