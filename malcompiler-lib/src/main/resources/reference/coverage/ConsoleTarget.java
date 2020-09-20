package core.coverage;

import java.math.BigInteger;
import java.math.BigDecimal;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedList;
import java.util.HashSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import core.*;

import org.junit.jupiter.api.extension.ExtensionContext;

/**
 *
 */
public class ConsoleTarget extends CoverageExtension.ExportableTarget {
	protected final PrintStream _out;
	protected String _testname;
	protected String _classname;

	protected final boolean _printTests;
	protected final boolean _printGroups;
	protected final boolean _printModel;

	private Map<ModelKey, ModelData> models = new HashMap<>();
	
	public ConsoleTarget() {
		this(true, true, true);
	}

	/**
	 * @param pTests print individual test results.
	 * @param pGropus print simulation group results.
	 * @param pModel print model results.
	 */	
	public ConsoleTarget(boolean pTests, boolean pGroups, boolean pModel) {
		this(pTests, pGroups, pModel, System.out);
	}
	
	/**
	 * @param pTests print individual test results.
	 * @param pGropus print simulation group results.
	 * @param pModel print model results.
	 * @param os PrintStream to output to.
	 */	
	public ConsoleTarget(boolean pTests, boolean pGroups, boolean pModel, PrintStream os) {
		_out = os;
		_printTests = pTests;
		_printGroups = pGroups;
		_printModel = pModel;
	}
	
	@Override
	public void setup() {}
	
	@Override
	public void preprocess(ExtensionContext ctx) {
		_testname = ctx.getDisplayName();
		_classname = ctx.getParent().map(ExtensionContext::getDisplayName).orElse("UNKNOWN");
	
		// _out.println(String.format("Test: %s::%s", ctx.getParent().get().getDisplayName(), ctx.getDisplayName()));
	}
	
	@Override
	public void processCoverage() {
		ModelKey modelKey = new ModelKey();
		ModelData current = models.computeIfAbsent(modelKey, s -> new ModelData());
		Set<Integer> compromisedSteps = AttackStep.allAttackSteps.stream()
			.filter(s -> s.ttc != AttackStep.infinity)
			.map(s -> s.hashCode()).collect(Collectors.toSet());
	
		// Compound model compromised steps
		current.compromisedSteps.addAll(compromisedSteps);
	
		Set<Integer> groupKey = AttackStep.allAttackSteps.stream()
			.filter(s -> s.initiallyCompromised)
			.map(s -> s.hashCode())
			.collect(Collectors.toSet());

		Integer defenseState = Defense.allDefenses.stream()
			.filter(s -> s.isEnabled())
			.collect(Collectors.toSet()).hashCode();
	
		// Simulation coverage = computeLocal(current, compromisedSteps);
		Simulation sim = new Simulation(String.format("%s::%s", _classname, _testname), compromisedSteps, defenseState);
		List<Simulation> group = current.groups.computeIfAbsent(groupKey.hashCode(), s -> new ArrayList<Simulation>());

		group.add(sim);

	}
	
	@Override
	public void export() {
		int id = 1;

		// Don't print anything
		if (!(_printModel && _printTests && _printGroups))
			return;
	
		// For every model
		for (ModelData model : models.values()) {
			List<String> modelTestNames = new ArrayList<>();

			// For every simulation group
			for (List<Simulation> group : model.groups.values()) {
				Set<Integer> sgCompromised = new HashSet<>(model.nAttackSteps);
				List<String> sgTestNames = new ArrayList<>();

				System.out.println("###################################");
				System.out.println("##        Test Coverage          ##");
				System.out.println("###################################");

				// For every test 
				for (Simulation sim : group) {
					sgCompromised.addAll(sim.compromisedSteps);
					sgTestNames.add(sim.name);

					// Print test-method results
					if (_printTests) {
						CoverageData cd = computeLocal(model, sim.compromisedSteps);

						System.out.println(String.format("Test: %s", sim.name));
						printCoverage(model, cd);
						System.out.println("");

					}
				}

				modelTestNames.addAll(sgTestNames);

				// Print simulation group results
				if (_printGroups) {
					System.out.println("###################################");
					System.out.println("##      Simulation Group         ##");
					System.out.println("###################################");
					System.out.println("Tests: " + sgTestNames);

					// #defense states covered.
					int coveredDefStates = group.stream()
						.map(s -> s.defenseState)
						.distinct()
						.collect(Collectors.toList()).size();
					 
					CoverageData cd = computeLocal(model, sgCompromised);
					printCoverage(model, cd);
					printDefenseCoverage(model, coveredDefStates);
					System.out.println();
				}
			}

			System.out.println("###################################");
			System.out.println("##       Model Coverage          ##");
			System.out.println("###################################");

			// Print model results
			if (_printModel) {
				System.out.println("Model " + id);
				System.out.println("Tests: " + modelTestNames);

				CoverageData cd = computeLocal(model, model.compromisedSteps);
				int coveredDefStates = model.groups.values().stream()
					.flatMap(List::stream)
					.map(s -> s.defenseState)
					.distinct()
					.collect(Collectors.toList()).size();

				printCoverage(model, cd);
				printDefenseCoverage(model, coveredDefStates, model.groups.size());

				id++;
			}
		}
	}
	
	/**
	 * Computes coverage data based on the model represented by data and
	 * compromisedSteps. 
	 *
	 * @param data model data
	 * @param compromisedSteps compromised attack steps
	 * @return coverage data for model 
	 */
	protected CoverageData computeLocal(ModelData data, Set<Integer> compromisedSteps) {
		int partCompAssets = 0;
		int fullyCompAssets = 0;
		int compSteps = 0;
		int compEdges = 0;

		compSteps = compromisedSteps.size();

		for (int assetHash : data.assetIds) {
			boolean fullyComp = true;
			boolean partialComp = false;

			for (int stepHash : data.assetSteps.get(assetHash)) {
				boolean compromised = compromisedSteps.contains(stepHash);

				fullyComp = fullyComp && compromised;
				partialComp = partialComp || compromised;

				for (int parentHash : data.stepParents.get(stepHash)) {
					if (compromisedSteps.contains(parentHash))
						compEdges++;
				}
			}

			if (fullyComp)
				fullyCompAssets++;
			if (partialComp)
				partCompAssets++;
		}

		return new CoverageData(String.format("%s::%s", _classname, _testname),
								partCompAssets,
								fullyCompAssets,
								compSteps,
								compEdges);
	}

	/**
	 * Prints coverage results to stdout.
	 *
	 * @param md threat model
	 * @param c coverage data
	 */
	protected void printCoverage(ModelData md, CoverageData c) {
		print("Partial Asset", c.nPartCompAssets, md.nAssets);
		print("Full Asset", c.nFullyCompAssets, md.nAssets);
		print("Attack Steps", c.nCompSteps, md.nAttackSteps);
		print("Edges", c.nCompEdges, md.nEdges);
	}

	/**
	 * Prints the defense coverage (t = 1).
	 *
	 * @param m threat model.
	 * @param coveredState number of covered states.
	 */
	protected void printDefenseCoverage(ModelData m, int covered) {
		printDefenseCoverage(m, covered, 1);
	}

	/**
	 * Prints the defense coverage.
	 *
	 * @param m threat model.
	 * @param coveredState number of covered states.
	 */	
	protected void printDefenseCoverage(ModelData m, int coveredStates, int t) {
		int SCALE = 6;
		BigDecimal numerator = new BigDecimal(BigInteger.valueOf(coveredStates), SCALE);
		BigDecimal denominator = new BigDecimal(BigInteger.valueOf(2), SCALE).pow(m.nDefenses).multiply(BigDecimal.valueOf(t));

		_out.println(String.format("\t%15s [%d/(%d * 2^%d)] %s", "Defense states", coveredStates, t, m.nDefenses, numerator.divide(denominator).toString()));
	}
	
	protected void print(String coverageType, int nCompromised, int nTotal) {
		if (nTotal > 0) {
			_out.println(String.format("\t%15s [%d/%d] %f", coverageType, nCompromised, nTotal,
					(double) nCompromised / nTotal));
		} else {
			_out.println(coverageType + " TOTAL = 0");
		}
	}
	
	/**
	 * Class for storing coverage information about a model. Each simulation 
	 * result is stored inside a simulation group (see the groups field).	
	 */
	private class ModelData {
		public List<Integer> assetIds = new ArrayList<>(Asset.allAssets.size());
		// Map of assetID -> asset.{steps id} (including defense steps)
		public Map<Integer, Set<Integer>> assetSteps = new HashMap<>(Asset.allAssets.size());
		// Map of attack step -> parent steps
		public Map<Integer, Set<Integer>> stepParents = new HashMap<>(AttackStep.allAttackSteps.size());

		public Set<Integer> compromisedSteps = new HashSet<>(AttackStep.allAttackSteps.size());

		// key = [ <initially compromised steps> ].hashCode()
		// Each list represents a simulation group
		public Map<Integer, ArrayList<Simulation>> groups = new HashMap<>();

		public int nAssets = 0;
		public int nAttackSteps = 0;
		public int nEdges = 0;
		public int nDefenses = 0;

		public List<String> tests = new LinkedList<>();

		/**
		 * Class for storing the relationship between assets and attack
		 * steps from the simulation.
		 */
		public ModelData() {
			nAssets = Asset.allAssets.size();
			// nAttackSteps = AttackStep.allAttackSteps.size();
			nAttackSteps = 0;
			//nDefenses = Defense.allDefenses.size();
			nDefenses = 0;
			
			// Generate model
			for (Asset asset : Asset.allAssets) {
				assetIds.add(asset.hashCode());
				List<AttackStep> steps = getAttackSteps(asset);
				nAttackSteps += steps.size();

				Set<Integer> stepIds = new HashSet<>(steps.size());

				// Add attack steps
				for (AttackStep step : steps) {
					stepIds.add(processAttackStep(step));
				}

				// Add defenses hidden attack steps
				List<Defense> assetDefenses = getDefenses(asset);
				nDefenses += assetDefenses.size();
				for (Defense def : assetDefenses) {
					stepIds.add(processAttackStep(def.disable));
				}

				assetSteps.put(asset.hashCode(), stepIds);
			}
		}

		/**
		* Processes the specified attack steps by registering its parents in
		* the step parents hash map and increasing the edge count of the model
		* if no step with the same hash has been registered in the stepParent
		* map.
		*
		* @param step attack step to process.
		* @return hash of the processed attack step.
		*/
		protected int processAttackStep(AttackStep step) {
			int hash = step.hashCode();

			if (!stepParents.containsKey(hash)) {
				Set<Integer> parents = Stream.concat(step.expectedParents.stream(), step.visitedParents.stream())
					.map(s -> s.hashCode())
					.collect(Collectors.toSet());

				stepParents.put(hash, parents);
				nEdges += parents.size();
			}

			return hash;
		}
	}

	/**
	 * Class for storing the model state after a simulation.
	 */	
	private class Simulation {
		public final String name;
		public final Set<Integer> compromisedSteps;
		public final Integer defenseState;
	
		public Simulation(String name, Set<Integer> compSteps, Integer defState) {
			this.name = name;
			this.compromisedSteps = compSteps;
			this.defenseState = defState;
		}
	}
	
	/**
	 * Class for storing simulation coverage data.
	 */
	private class CoverageData {
		public final String name;
		public int nPartCompAssets;
		public int nFullyCompAssets;
		public int nCompSteps;
		public int nCompEdges;

		public CoverageData(String name, int nPartCompAssets, int nFullyCompAssets, int nCompSteps, int nCompEdges) {
			this.name = name;
			this.nPartCompAssets = nPartCompAssets;
			this.nFullyCompAssets = nFullyCompAssets;
			this.nCompSteps = nCompSteps;
			this.nCompEdges = nCompEdges;
		}
	}
}
