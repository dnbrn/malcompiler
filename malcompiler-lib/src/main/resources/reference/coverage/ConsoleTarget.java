package core.coverage;

import java.lang.reflect.Modifier;
import java.math.BigInteger;
import java.math.BigDecimal;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.math.RoundingMode;

import core.*;

import org.junit.jupiter.api.extension.ExtensionContext;

import core.coverage.LanguageModel;

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

	// variable to collect warnings in
	private List<String> warnings = new ArrayList<>();

	// indicator when to print warnings
	private boolean generateWarnings = false;

	// language model
	private LanguageModel languageModel;

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
	public void setup() {
		// create language model like in JSONTarget
		Set<Class<? extends Asset>> assetTypes = getAllAssetTypesFromDSL();

		languageModel = new LanguageModel();

		for (Class<? extends Asset> assetClass : assetTypes) {
			String assetName = assetClass.getSimpleName();
			LanguageModel.AssetMetadata metadata = new LanguageModel.AssetMetadata();
			metadata.assetName = assetName;

			streamAssetAttackSteps(assetClass).forEach(f -> metadata.assetAttackSteps.add(f.getName()));
			streamAssetDefense(assetClass).forEach(f -> metadata.assetDefenses.add(f.getName()));

			// copied from JSONTarget
			// extract associations
			for (Field field : assetClass.getFields()) {

				// added to filter out inherited Asset.allAssets
				if (Modifier.isStatic(field.getModifiers())) {
					continue;
				}

				Class<?> fieldType = field.getType();

				// only consider association fields
				if (Asset.class.isAssignableFrom(fieldType)) {
					// Single-valued association
					LanguageModel.UnmergedAssociation assoc = new LanguageModel.UnmergedAssociation();
					assoc.sourceAsset = assetName;
					assoc.sourceField = field.getName();
					assoc.sourceMultiplicity = "1";
					assoc.targetAsset = fieldType.getSimpleName();

					languageModel.allUnmergedAssociations.add(assoc);

				} else if (Collection.class.isAssignableFrom(fieldType)) {
					String signature = field.toGenericString();
					String typeName = "UNKNOWN";

					if (signature.contains("<") && signature.contains(">")) {
						int start = signature.indexOf('<') + 1;
						int end = signature.indexOf('>');
						typeName = signature.substring(start, end).trim();

						if (typeName.contains(".")) {
							typeName = typeName.substring(typeName.lastIndexOf('.') + 1);
						}
					}

					LanguageModel.UnmergedAssociation assoc = new LanguageModel.UnmergedAssociation();
					assoc.sourceAsset = assetName;
					assoc.sourceField = field.getName();
					assoc.sourceMultiplicity = "*";
					assoc.targetAsset = typeName;

					languageModel.allUnmergedAssociations.add(assoc);
				}
			}

			// store asset with corresponding attack steps and defences in language model
			languageModel.assets.put(assetName, metadata);
		}

		// merge allUnmergedAssociations
		languageModel.assets.values().forEach(m -> m.assetAssociations.clear());
		Set<LanguageModel.AssociationMetadata> mergedAssociations =
				mergeUnmergedAssociations(languageModel.allUnmergedAssociations);

		// add associations to asset types in language model
		for (LanguageModel.AssociationMetadata assoc : mergedAssociations) {
			languageModel.assets.get(assoc.leftAsset).assetAssociations.add(assoc);
		}
		// add to language model in general
		languageModel.mergedAssociations = mergedAssociations;
	}

	/**
	 * function to merge extracted on-sided unmerged associations
	 * @param unmerged associations
	 * @return
	 */
	private Set<LanguageModel.AssociationMetadata> mergeUnmergedAssociations(Set<LanguageModel.UnmergedAssociation> unmerged) {
		Map<String, List<LanguageModel.UnmergedAssociation>> grouped = new HashMap<>();

		// Helper to create a bidirectional key (unordered asset pair)
		for (LanguageModel.UnmergedAssociation ua : unmerged) {
			String key = ua.sourceAsset.compareTo(ua.targetAsset) < 0
					? ua.sourceAsset + "::" + ua.targetAsset
					: ua.targetAsset + "::" + ua.sourceAsset;

			grouped.computeIfAbsent(key, k -> new ArrayList<>()).add(ua);
		}

		Set<LanguageModel.AssociationMetadata> merged = new HashSet<>();

		for (Map.Entry<String, List<LanguageModel.UnmergedAssociation>> entry : grouped.entrySet()) {
			List<LanguageModel.UnmergedAssociation> pair = entry.getValue();

			if (pair.size() == 2) {
				// Found both sides of the association
				LanguageModel.UnmergedAssociation a = pair.get(0);
				LanguageModel.UnmergedAssociation b = pair.get(1);

				LanguageModel.AssociationMetadata assoc = new LanguageModel.AssociationMetadata();
				assoc.leftAsset = a.sourceAsset;
				assoc.leftField = b.sourceField;
				assoc.leftMultiplicity = b.sourceMultiplicity;
				assoc.rightMultiplicity = a.sourceMultiplicity;
				assoc.rightField = a.sourceField;
				assoc.rightAsset = a.targetAsset;

				merged.add(assoc);

			} else if (pair.size() == 1) {
				// only one side, hopefully won't happen
				LanguageModel.UnmergedAssociation a = pair.get(0);

				// debug information
				System.out.println("");
				System.out.println("Partial association: " + a.toString());
				System.out.println("Only one side of the association found! Check if everything works as supposed to!");
				System.out.println("");

				LanguageModel.AssociationMetadata assoc = new LanguageModel.AssociationMetadata();
				assoc.leftAsset = a.sourceAsset;
				assoc.leftField = "?";
				assoc.leftMultiplicity = "?";
				assoc.rightMultiplicity = a.sourceMultiplicity;
				assoc.rightField = a.sourceField;
				assoc.rightAsset = a.targetAsset;

				merged.add(assoc);
			}
		}

		return merged;
	}
	
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

		// filter out defences as hidden attack steps (".Disable")
		Map<Integer, String> compromisedStepMapWODefences = AttackStep.allAttackSteps.stream()
				.filter(s -> s.ttc != AttackStep.infinity)
				.filter(s -> !s.getClass().getSimpleName().equals("Disable"))
				.collect(Collectors.toMap(
						s -> s.hashCode(),
						s -> s.fullName()
				));
		Set<Integer> compromisedSteps = compromisedStepMapWODefences.keySet();
		Set<String> compromisedStepNames = new HashSet<>(compromisedStepMapWODefences.values());

		// Compound model compromised steps
		current.compromisedSteps.addAll(compromisedSteps);
		current.compromisedStepNames.addAll(compromisedStepNames);
	
		Set<Integer> groupKey = AttackStep.allAttackSteps.stream()
			.filter(s -> s.initiallyCompromised)
			.map(s -> s.hashCode())
			.collect(Collectors.toSet());

		Integer defenseState = Defense.allDefenses.stream()
			.filter(s -> s.isEnabled())
			.collect(Collectors.toSet()).hashCode();

		// get used asset types, steps, defences
		Set<String> usedAssetTypes = new HashSet<>();
		Set<String> usedAttackSteps = new HashSet<>();
		Set<String> usedDefenses = new HashSet<>();
		Set<String> usedAssociations = computeUsedAssociations();

		for (Asset asset : Asset.allAssets) {
			for (AttackStep step : getAttackSteps(asset)) {
				if (step.ttc != AttackStep.infinity) {
					String assetName = asset.getClass().getSimpleName();
					usedAssetTypes.add(assetName);

					if (step.getClass().getSimpleName().equals("Disable")) {
						usedDefenses.add(assetName + "." + getFieldName(asset, step));
					} else {
						usedAttackSteps.add(assetName + "." + getFieldName(asset, step));
					}
				}
			}
			for (Defense def : getDefenses(asset)) {
				if (def.disable.ttc != AttackStep.infinity) {
					String assetName = asset.getClass().getSimpleName();
					usedAssetTypes.add(assetName);
					usedDefenses.add(assetName + "." + getFieldName(asset, def));
				}
			}
		}

		// Simulation coverage = computeLocal(current, compromisedSteps);
		Simulation sim = new Simulation(String.format("%s::%s", _classname, _testname), compromisedSteps, defenseState,
				usedAssetTypes, usedAttackSteps, usedDefenses, usedAssociations);
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

				printHeading("Test Coverage");

				// For every test 
				for (Simulation sim : group) {
					sgCompromised.addAll(sim.compromisedSteps);
					sgTestNames.add(sim.name);

					// Print test-method results
					if (_printTests) {
						CoverageData cd = computeLocal(model, sim.compromisedSteps);

						System.out.println(String.format("Test: %s", sim.name));
						printCoverage(model, cd);
						printLanguageCoverage(sim);
						System.out.println("");

					}
				}

				modelTestNames.addAll(sgTestNames);

				// Print simulation group results
				if (_printGroups) {
					printHeading("Simulation Group");
					System.out.println("Tests: " + sgTestNames);

					// #defense states covered.
					int coveredDefStates = group.stream()
						.map(s -> s.defenseState)
						.distinct()
						.collect(Collectors.toList()).size();
					 
					CoverageData cd = computeLocal(model, sgCompromised);
					printCoverage(model, cd);
					printLanguageCoverage(group);
					printDefenseCoverage(model, coveredDefStates);
					System.out.println();
				}
			}

			printHeading("Model Coverage");

			// enable printing warnings for model coverage
			generateWarnings = true;

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
				printLanguageCoverage(model);
				printDefenseCoverage(model, coveredDefStates, model.groups.size());

				id++;
			}

			// revert to default of not printing warnings
			generateWarnings = false;

			if (!warnings.isEmpty()) {
				_out.println();
				printHeading("Warnings");
				for (String warning : warnings) {
					_out.println("\t⚠️ " + warning);
				}
				warnings.clear();
			}

			printLanguageCoverageSummary(model);
		}


	}

	/**
	 * function to dynamically compute the associations used in the model
	 * @param languageModel
	 * @return
	 */
	private Set<String> computeUsedAssociations() {
		Set<String> used = new HashSet<>();

		for (Asset asset : Asset.allAssets) {
			String assetTypeName = asset.getClass().getSimpleName();

			for (LanguageModel.AssociationMetadata assoc : languageModel.mergedAssociations) {

				// only evaluate if asset is the left side of the association
				if (!assoc.leftAsset.equals(assetTypeName)) continue;

				try {
					// asset-provided accessor
					Set<Asset> associated = asset.getAssociatedAssets(assoc.rightField);

					if (associated != null && !associated.isEmpty()) {
						used.add(assoc.toString());
					}
				} catch (Exception e) {
					System.out.printf("Warning: could not inspect association field '%s' in asset '%s'%n",
							assoc.rightField, assetTypeName);
				}
			}
		}

		return used;
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
	 * helper function to print headings nicely
	 * @param title of the current section
	 */
	private void printHeading(String title) {
		// total width of the line (including ##)
		int totalWidth = 54;

		int titleLength = title.length();
		// -4 for the two "##" and two spaces
		int padding = (totalWidth - 4 - titleLength) / 2;

		StringBuilder sb = new StringBuilder();
		sb.append("##");

		for (int i = 0; i < padding; i++) {
			sb.append(' ');
		}

		sb.append(title);

		while (sb.length() < totalWidth - 2) {
			sb.append(' ');
		}

		sb.append("##");

		_out.println("######################################################");
		_out.println(sb.toString());
		_out.println("######################################################");
	}

	/**
	 * compute and print information about language level coverage
	 * @param model data about collection of simulation groups
	 */
	protected void printLanguageCoverage(ModelData model) {
		// only work with context of specific test and simulation groups in this model
		int totalAssets = languageModel.assets.size();
		int totalAttackSteps = languageModel.assets.values().stream()
				.mapToInt(a -> a.assetAttackSteps.size())
				.sum();
		int totalDefenses = languageModel.assets.values().stream()
				.mapToInt(a -> a.assetDefenses.size())
				.sum();
		int totalAssociations = languageModel.mergedAssociations.size();

		int totalLanguageElements = totalAssets + totalAttackSteps + totalDefenses + totalAssociations;

		Set<String> usedAssetTypeNames = new HashSet<>();
		Set<String> usedAttackStepNames = new HashSet<>();
		Set<String> usedDefenseNames = new HashSet<>();
		Set<String> usedAssociationNames = new HashSet<>();
		for (List<Simulation> group : model.groups.values())
			for (Simulation sim : group) {
				usedAssetTypeNames.addAll(sim.usedAssetTypeNames);
				usedAttackStepNames.addAll(sim.usedAttackStepNames);
				usedDefenseNames.addAll(sim.usedDefenseNames);
				usedAssociationNames.addAll(sim.usedAssociations);
			}

		int usedAssets = usedAssetTypeNames.size();
		int usedAttackSteps = usedAttackStepNames.size();
		int usedDefenses = usedDefenseNames.size();
		int usedAssociations = usedAssociationNames.size();

		int usedLanguageElements = usedAssets + usedAttackSteps + usedDefenses + usedAssociations;

		if (totalLanguageElements > 0) {
			double fraction = (double) usedLanguageElements / totalLanguageElements;
			double percent = fraction * 100.0;

			_out.println(String.format("\t%-17s [%5d/%5d] -> %6.2f%%",
					"Language elements", usedLanguageElements, totalLanguageElements, percent));

		} else {
			// TODO evaluate this
			_out.println(String.format("\t%-17s TOTAL = 0", "Language coverage"));
		}
	}

	/**
	 * compute and print information about language level coverage
	 * @param group data about group of simulations
	 */
	protected void printLanguageCoverage(List<Simulation> group) {
		// only work with context of specific simulation group
		int totalAssets = languageModel.assets.size();
		int totalAttackSteps = languageModel.assets.values().stream()
				.mapToInt(a -> a.assetAttackSteps.size())
				.sum();
		int totalDefenses = languageModel.assets.values().stream()
				.mapToInt(a -> a.assetDefenses.size())
				.sum();
		int totalAssociations = languageModel.mergedAssociations.size();

		int totalLanguageElements = totalAssets + totalAttackSteps + totalDefenses + totalAssociations;

		Set<String> usedAssetTypeNames = new HashSet<>();
		Set<String> usedAttackStepNames = new HashSet<>();
		Set<String> usedDefenseNames = new HashSet<>();
		Set<String> usedAssociationNames = new HashSet<>();
		for (Simulation sim : group) {
			usedAssetTypeNames.addAll(sim.usedAssetTypeNames);
			usedAttackStepNames.addAll(sim.usedAttackStepNames);
			usedDefenseNames.addAll(sim.usedDefenseNames);
			usedAssociationNames.addAll(sim.usedAssociations);
		}

		int usedAssets = usedAssetTypeNames.size();
		int usedAttackSteps = usedAttackStepNames.size();
		int usedDefenses = usedDefenseNames.size();
		int usedAssociations = usedAssociationNames.size();
		int usedLanguageElements = usedAssets + usedAttackSteps + usedDefenses + usedAssociations;

		if (totalLanguageElements > 0) {
			double fraction = (double) usedLanguageElements / totalLanguageElements;
			double percent = fraction * 100.0;

			_out.println(String.format("\t%-17s [%5d/%5d] -> %6.2f%%",
					"Language elements", usedLanguageElements, totalLanguageElements, percent));

		} else {
			// TODO evaluate this
			_out.println(String.format("\t%-17s TOTAL = 0", "Language coverage"));
		}
	}

	/**
	 * compute and print information about language level coverage
	 * @param sim data about a single simulation
	 */
	protected void printLanguageCoverage(Simulation sim) {
		// only work with context of specific test
		int totalAssets = languageModel.assets.size();
		int totalAttackSteps = languageModel.assets.values().stream()
				.mapToInt(a -> a.assetAttackSteps.size())
				.sum();
		int totalDefenses = languageModel.assets.values().stream()
				.mapToInt(a -> a.assetDefenses.size())
				.sum();
		int totalAssociations = languageModel.mergedAssociations.size();

		int totalLanguageElements = totalAssets + totalAttackSteps + totalDefenses + totalAssociations;

		int usedAssets = sim.usedAssetTypeNames.size();
		int usedAttackSteps = sim.usedAttackStepNames.size();
		int usedDefenses = sim.usedDefenseNames.size();
		int usedAssociations = sim.usedAssociations.size();
		int usedLanguageElements = usedAssets + usedAttackSteps + usedDefenses + usedAssociations;

		if (totalLanguageElements > 0) {
			double fraction = (double) usedLanguageElements / totalLanguageElements;
			double percent = fraction * 100.0;

			_out.println(String.format("\t%-17s [%5d/%5d] -> %6.2f%%",
					"Language elements", usedLanguageElements, totalLanguageElements, percent));

		} else {
			// TODO evaluate this
			_out.println(String.format("\t%-17s TOTAL = 0", "Language coverage"));
		}
	}

	/**
	 * function to print a summary of all points regarding language coverage
	 */
	private void printLanguageCoverageSummary(ModelData model) {
		_out.println();
		printHeading("Language Coverage");

		int totalAssets = languageModel.assets.size();
		int totalAttackSteps = languageModel.assets.values().stream()
				.mapToInt(a -> a.assetAttackSteps.size())
				.sum();
		int totalDefenses = languageModel.assets.values().stream()
				.mapToInt(a -> a.assetDefenses.size())
				.sum();
		int totalAssociations = languageModel.mergedAssociations.size();

		int usedAssets = model.usedAssetTypes.size();
		int usedAttackSteps = model.usedAttackSteps.size();
		int usedDefenses = model.usedDefenses.size();
		int usedAssociations = model.usedAssociations.size();

		_out.println(String.format("\t%-17s [%5d/%5d] -> %6.2f%%",
				"Asset Types", usedAssets, totalAssets, (usedAssets * 100.0 / totalAssets)));

		_out.println(String.format("\t%-17s [%5d/%5d] -> %6.2f%%",
				"Attack Steps ", usedAttackSteps, totalAttackSteps, (usedAttackSteps * 100.0 / totalAttackSteps)));

		if (totalDefenses > 0) {
			_out.println(String.format("\t%-17s [%5d/%5d] -> %6.2f%%",
					"Defences", usedDefenses, totalDefenses, (usedDefenses * 100.0 / totalDefenses)));
		} else { // avoid division by zero in case of no defences
			_out.println(String.format("\t%-17s [%5d/%5d] -> %6.2f%%",
					"Defences", usedDefenses, totalDefenses, 100.0));
		}

		_out.println(String.format("\t%-17s [%5d/%5d] -> %6.2f%%",
				"Associations", usedAssociations, totalAssociations, (usedAssociations * 100.0 / totalAssociations)));


		int totalLanguageElements = totalAssets + totalAttackSteps + totalDefenses + totalAssociations;
		int usedLanguageElements = usedAssets + usedAttackSteps + usedDefenses + usedAssociations;

		_out.println(String.format("\t%-17s [%5d/%5d] -> %6.2f%%",
				"Language elements", usedLanguageElements, totalLanguageElements,
				(usedLanguageElements * 100.0 / totalLanguageElements)));
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

		// in case no defences exist
		if (m.nDefenses == 0) {
			_out.println(String.format("\t%-17s [%5d/(%d * 2^%d)] -> 100.00%%",
					"Defence states", coveredStates, t, m.nDefenses));
			return;
		}

		BigDecimal numerator = BigDecimal.valueOf(coveredStates);
		BigDecimal denominator = BigDecimal.valueOf(2).pow(m.nDefenses)
				.multiply(BigDecimal.valueOf(t));

		if (denominator.compareTo(BigDecimal.ZERO) > 0) {
			BigDecimal fraction = numerator.divide(denominator, SCALE, RoundingMode.HALF_UP);
			BigDecimal percentage = fraction.multiply(BigDecimal.valueOf(100));

			_out.println(String.format("\t%-17s [%5d/(%d * 2^%d)] -> %6.2f%%", "Defence states", coveredStates,
					t, m.nDefenses, percentage.setScale(2, RoundingMode.HALF_UP).doubleValue()));

			// generate warnings
			// TODO
			// potentially adjust percentage.doubleValue() to percentage.setScale(2, BigDecimal.ROUND_HALF_UP).doubleValue()
			if (generateWarnings && percentage.doubleValue() < 99.99) {
				warnings.add(String.format("Defence states coverage is only %.2f%%",
						percentage.doubleValue()));
			}
		} else {
			_out.println(String.format("\t%-17s TOTAL = 0", "Defence states"));
		}
	}

	/**
	 * helper function for printing
	 *
	 * @param coverageType
	 * @param nCompromised
	 * @param nTotal
	 */
	protected void print(String coverageType, int nCompromised, int nTotal) {
		if (nTotal > 0) {
			double fraction = (double) nCompromised / nTotal;
			double percent = fraction * 100.0;

			// fixed output sizes
			_out.println(String.format("\t%-17s [%5d/%5d] -> %6.2f%%", coverageType, nCompromised, nTotal, percent));

			// generate warnings
			// TODO
			//potentially adjust wording
			if (generateWarnings && percent < 99.99) {
				warnings.add(String.format("%s coverage is only %.2f%%", coverageType, percent));
			}
		} else {
			_out.println(String.format("\t%-17s TOTAL = 0", coverageType));
		}
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

		// additional variable to safe names of compromised attack steps
		public Set<String> compromisedStepNames = new HashSet<>();

		// key = [ <initially compromised steps> ].hashCode()
		// Each list represents a simulation group
		public Map<Integer, ArrayList<Simulation>> groups = new HashMap<>();

		public int nAssets = 0;
		public int nAttackSteps = 0;
		public int nEdges = 0;
		public int nDefenses = 0;

		// track specific used asset types, attack steps and defences without douplication
		public Set<String> usedAssetTypes = new HashSet<>();
		public Set<String> usedAttackSteps = new HashSet<>();
		public Set<String> usedDefenses = new HashSet<>();
		public Set<String> usedAssociations = new HashSet<>();

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
				// mark as used
				usedAssetTypes.add(asset.getClass().getSimpleName());

				List<AttackStep> steps = getAttackSteps(asset);
				nAttackSteps += steps.size();

				Set<Integer> stepIds = new HashSet<>(steps.size());

				// Add attack steps
				for (AttackStep step : steps) {
					stepIds.add(processAttackStep(step));
					// mark as used
					usedAttackSteps.add(asset.getClass().getSimpleName() + "." + step.attackStepName());
				}

				// Add defenses hidden attack steps
				List<Defense> assetDefenses = getDefenses(asset);
				nDefenses += assetDefenses.size();
				for (Defense def : assetDefenses) {
					stepIds.add(processAttackStep(def.disable));
					// mark as used
					usedDefenses.add(asset.getClass().getSimpleName() + "." + def.getClass().getSimpleName());
				}

				assetSteps.put(asset.hashCode(), stepIds);
			}

			usedAssociations = computeUsedAssociations();
		}

		/**
		 * function to dynamically compute the associations used in the model
		 * @param languageModel
		 * @return
		 */
		private Set<String> computeUsedAssociations() {
			Set<String> used = new HashSet<>();

			for (Asset asset : Asset.allAssets) {
				String assetTypeName = asset.getClass().getSimpleName();

				for (LanguageModel.AssociationMetadata assoc : languageModel.mergedAssociations) {

					// only evaluate if asset is the left side of the association
					if (!assoc.leftAsset.equals(assetTypeName)) continue;

					try {
						// asset-provided accessor
						Set<Asset> associated = asset.getAssociatedAssets(assoc.rightField);

						if (associated != null && !associated.isEmpty()) {
							used.add(assoc.toString());
						}
					} catch (Exception e) {
						System.out.printf("Warning: could not inspect association field '%s' in asset '%s'%n",
								assoc.rightField, assetTypeName);
					}
				}
			}

			return used;
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

		// added for language elements tracking
		public final Set<String> usedAssetTypeNames;
		public final Set<String> usedAttackStepNames;
		public final Set<String> usedDefenseNames;
		public final Set<String> usedAssociations;

		public Simulation(String name, Set<Integer> compSteps, Integer defState,
                      Set<String> usedAssets, Set<String> usedSteps, Set<String> usedDefs,
						  Set<String> usedAssocs) {
			this.name = name;
			this.compromisedSteps = compSteps;
			this.defenseState = defState;
			this.usedAssetTypeNames = usedAssets;
			this.usedAttackStepNames = usedSteps;
			this.usedDefenseNames = usedDefs;
			this.usedAssociations = usedAssocs;
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
