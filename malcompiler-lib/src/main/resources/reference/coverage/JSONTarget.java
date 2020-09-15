package core.coverage;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.Map;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Optional;

import org.junit.jupiter.api.extension.ExtensionContext;

import core.Asset;
import core.AttackStep;
import core.AttackStepMin;
import core.Defense;

public class JSONTarget extends CoverageExtension.ExportableTarget {
	private String _testname;
	private String _testClassName;
	private PrintStream _out;
	private Map<ModelKey, Pair<List<JSONObject>, List<JSONObject>>> models = new HashMap<>();

	private Optional<String> _filename = Optional.empty();

	public JSONTarget() {
		_out = null;
	}

	public JSONTarget(String filename) {
		_filename = Optional.ofNullable(filename);
	}

	public JSONTarget(PrintStream stream) {
		_out = stream;
		_out.print('[');
	}

	@Override
	public void setup() {
		_filename.ifPresent(filename -> {
			try {
				File file = new File(filename);
				file.createNewFile();

				_out = new PrintStream(file);
				_out.print('[');
			} catch (IOException e) {
				_out = null;
			}
		});
	}

	@Override
	public void preprocess(ExtensionContext ctx) {
		if (_out == null) {
			if (ctx.getParent().isPresent()) {
				try {
					String filename = ctx.getParent().get().getDisplayName() + ".json";
					File file = new File(filename);
					_out = new PrintStream(file);

				} catch (IOException e) {
					_out = System.out;
				}
			} else {
				_out = System.out;
			}

			_out.print('[');
		}

		_testname = ctx.getDisplayName();
		_testClassName = ctx.getParent().map(ExtensionContext::getDisplayName).orElse("Unknown");
		_testname = _testname.substring(0, _testname.indexOf('('));
	}

	@Override
	public void processCoverage() {
		Pair<List<JSONObject>, List<JSONObject>> mdl = getModel();
		JSONObject simResults = getSimulationResults();

		mdl.second.add(simResults);
	}

	@Override
	public void export() {
		boolean first = true;

		for (Pair<List<JSONObject>, List<JSONObject>> mdl : models.values()) {
			JSONObject jMdl = new JSONObject();

			jMdl.addList("model", mdl.first);
			jMdl.addList("simulations", mdl.second);

			if (!first) {
				_out.print(",");
			}
			first = false;

			_out.print(jMdl);

		}

		_out.print(']');
		_out.close();
	}

	/**
	 * Returns a json object containing the hashes of all compromised attack steps
	 * in the current simulation.
	 * 
	 * @return A json object representation of the simulation results.
	 */
	protected JSONObject getSimulationResults() {
		JSONObject obj = new JSONObject();

		int defenseState = Defense.allDefenses.stream()
			.filter(d -> d.isEnabled())
			.collect(Collectors.toSet()).hashCode();

		obj.add("test", _testname);
		obj.add("class", _testClassName);
		obj.add("defenseState", defenseState);
		obj.addList("compromised",
				AttackStep.allAttackSteps.stream().filter(s -> s.ttc != AttackStep.infinity).map(s -> {
					JSONObject o = new JSONObject();

					o.add("id", s.hashCode());
					o.add("ttc", s.ttc);

					return o;
				}).collect(Collectors.toList()));

		obj.addList("activeDefenses", Defense.allDefenses.stream().filter(d -> d.isEnabled())
				.map(d -> d.disable.hashCode()).collect(Collectors.toList()));

		return obj;
	}

	/**
	 * Returns a pair of json object containing the model description and
	 * simulations. The first entry contains a json description of the threat model,
	 * while the second entry contains a list of json descriptions of all attack
	 * simulations run upon the model. Entries will be automatically generated for
	 * new models.
	 * 
	 * @return A pair containing the threat model and simulation data.
	 */
	protected Pair<List<JSONObject>, List<JSONObject>> getModel() {
		ModelKey key = new ModelKey(Asset.allAssets.hashCode(), AttackStep.allAttackSteps.hashCode(),
				Defense.allDefenses.hashCode());

		if (models.containsKey(key)) {
			return models.get(key);
		}

		Pair<List<JSONObject>, List<JSONObject>> mdl = new Pair<>(exportModel(), new LinkedList<JSONObject>());
		models.put(key, mdl);

		return mdl;
	}

	/**
	 * Returns a list of json descriptions of all currently registered assets.
	 * 
	 * @return a list of json representation of all assets.
	 */
	private List<JSONObject> exportModel() {
		return Asset.allAssets.parallelStream().map(asset -> new JSONObject(asset)).collect(Collectors.toList());
	}

	private class Pair<X, Y> {
		public final X first;
		public final Y second;

		public Pair(X first, Y second) {
			this.first = first;
			this.second = second;
		}
	}

	/**
	 * Class for exporting model and coverage data to JSON.
	 */
	private class JSONObject {
		private StringBuilder sb;

		public JSONObject() {
			sb = new StringBuilder("{");
		}

		/**
		 * Constructs a json representaiton of a mal asset inculding class name, asset
		 * type, connected assets, asset object id and attack simualtion coverage.
		 * 
		 * @param a mal asset.
		 */
		public JSONObject(Asset a) {
			this();

			add("name", a.name);
			add("class", a.assetClassName);
			add("hash", a.hashCode());

			List<Integer> connections = a.getAllAssociatedAssets().parallelStream().map(e -> e.hashCode())
					.collect(Collectors.toList());

			addList("connections", connections);

			// Compute coverage data
			// Finds compromised fields, ttc
			List<JSONObject> attackSteps = streamAssetAttackSteps(a.getClass()).map(f -> {
				AttackStep step;
				JSONObject obj = new JSONObject();

				try {
					step = ((AttackStep) f.get(a));
				} catch (Exception e) {
					step = new AttackStep("[ERR] invalid step");
					step.ttc = 0;
					System.err.println("Failed to fetch attack step!");
				}

				obj.add("step", f.getName());
				obj.add("type", AttackStepMin.class.isAssignableFrom(f.getType()) ? "|" : "&");

				obj.add("hash", step.hashCode());

				obj.addList("parents", Stream.concat(step.visitedParents.stream(), step.expectedParents.stream())
						.map(p -> p.hashCode()).collect(Collectors.toList()));

				return obj;
			}).collect(Collectors.toList());

			addList("steps", attackSteps);
			addList("defense", streamAssetDefense(a.getClass()).map(f -> {
				JSONObject obj = new JSONObject();
				Defense def;

				try {
					def = ((Defense) f.get(a));
				} catch (Exception e) {
					def = new Defense("[ERR] Invalid defense");
					def.disable = new AttackStep("INVALID (DUMMY)");
					System.err.println("Failed to fetch defense.");
				}

				obj.add("name", f.getName());
				obj.add("hash", def.disable.hashCode());
				obj.addList("parents", Stream.concat(def.disable.expectedParents.stream(), def.disable.visitedParents.stream())
						.map(p -> p.hashCode())
						.collect(Collectors.toList()));

				return obj;
			}).collect(Collectors.toList()));
		}

		public void add(String key, String val) {
			addKey(key);

			sb.append('\"').append(val).append('\"');
		}

		public void add(String key, double val) {
			addKey(key);

			sb.append(val);
		}

		public void add(String key, int val) {
			addKey(key);

			sb.append(val);
		}

		public void add(String key, boolean val) {
			addKey(key);

			sb.append(val ? "true" : "false");
		}

		public void add(String key, JSONObject obj) {
			addKey(key);

			sb.append(obj);
		}

		/**
		 * Prints the content of the list directly (using toString)
		 * 
		 * @param key JSON key
		 * @param lst list/JSON array data
		 */
		public <T> void addList(String key, List<T> lst) {
			addKey(key);
			sb.append(lst.toString());
		}

		/**
		 * Prints the content of a list and adds citations around each element. Should
		 * be used when the list contain strings.
		 * 
		 * @param key JSON key
		 * @param lst list/JSON array data
		 */
		public void addStrList(String key, List<String> lst) {
			addKey(key);
			boolean first = true;

			sb.append('[');
			for (String s : lst) {
				if (!first) {
					sb.append(',');
				}
				first = false;

				sb.append('\"').append(s).append('\"');
			}

			sb.append(']');
		}

		private void addKey(String key) {
			if (sb.length() != 1)
				sb.append(',');
			sb.append("\"").append(key).append("\":");

		}

		@Override
		public String toString() {
			return sb.append('}').toString();
		}
	}
}
