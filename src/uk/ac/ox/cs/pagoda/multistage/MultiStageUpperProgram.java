package uk.ac.ox.cs.pagoda.multistage;

import java.io.BufferedWriter;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Map;
import java.util.Set;

import org.semanticweb.HermiT.model.AnnotatedEquality;
import org.semanticweb.HermiT.model.AtLeast;
import org.semanticweb.HermiT.model.AtLeastConcept;
import org.semanticweb.HermiT.model.AtLeastDataRange;
import org.semanticweb.HermiT.model.Atom;
import org.semanticweb.HermiT.model.AtomicConcept;
import org.semanticweb.HermiT.model.AtomicNegationConcept;
import org.semanticweb.HermiT.model.AtomicRole;
import org.semanticweb.HermiT.model.DLClause;
import org.semanticweb.HermiT.model.DLPredicate;
import org.semanticweb.HermiT.model.Equality;
import org.semanticweb.HermiT.model.Inequality;
import org.semanticweb.HermiT.model.InverseRole;
import org.semanticweb.HermiT.model.Variable;
import org.semanticweb.more.util.Logger_MORe;

import uk.ac.ox.cs.JRDFox.JRDFStoreException;
import uk.ac.ox.cs.JRDFox.store.TupleIterator;
import uk.ac.ox.cs.pagoda.constraints.BottomStrategy;
import uk.ac.ox.cs.pagoda.hermit.RuleHelper;
import uk.ac.ox.cs.pagoda.reasoner.light.RDFoxTripleManager;
import uk.ac.ox.cs.pagoda.rules.Approximator;
import uk.ac.ox.cs.pagoda.rules.OverApproxExist;
import uk.ac.ox.cs.pagoda.rules.Program;
import uk.ac.ox.cs.pagoda.util.MyPrefixes;
import uk.ac.ox.cs.pagoda.util.Namespace;
import uk.ac.ox.cs.pagoda.util.SparqlHelper;
import uk.ac.ox.cs.pagoda.util.Timer;
import uk.ac.ox.cs.pagoda.util.Utility_PAGOdA;

public abstract class MultiStageUpperProgram {
	
	protected Set<DLClause> constraints = new HashSet<DLClause>(); 
	protected Set<DLClause> rules = new HashSet<DLClause>();
	protected Collection<DLClause> clauses; 

	protected BottomStrategy m_bottom = null; 
	protected Approximator m_approxExist;

	protected static final Variable X = Variable.create("X"); 
	
	protected MyPrefixes prefixes = MyPrefixes.PAGOdAPrefixes;
	protected Map<DLClause, DLClause> map = new HashMap<DLClause, DLClause>(); 
	
	public MultiStageUpperProgram(Program program, BottomStrategy upperBottom) {
		m_bottom = upperBottom; 
		clauses = getInitialClauses(program);
		m_approxExist = initApproximator();
		Collection<DLClause> introducedConstraints = new LinkedList<DLClause>();
		for (DLClause clause: m_bottom.process(clauses)) 
			preprocessClause(clause, introducedConstraints);
		for (DLClause clause: m_bottom.process(introducedConstraints))
			addDatalogRule(clause);
	}
	
	protected Approximator initApproximator(){
		return new OverApproxExist();
	}
	
	protected void preprocessClause(DLClause clause, Collection<DLClause> introducedConstraints){
		if (m_bottom.isBottomRule(clause) || clause.getHeadLength() == 1 && !(clause.getHeadAtom(0).getDLPredicate() instanceof AtLeast)) 
			addDatalogRule(clause);				
		else {
			LinkedList<Atom> newHeadAtoms = new LinkedList<Atom>(); 
			boolean changed = false; 
			for (Atom atom: clause.getHeadAtoms()) {
				if (atom.getDLPredicate() instanceof AtLeastConcept) {
					AtLeastConcept atLeast = (AtLeastConcept) atom.getDLPredicate(); 
					if (atLeast.getToConcept() instanceof AtomicNegationConcept) {
						AtomicConcept positive = ((AtomicNegationConcept) atLeast.getToConcept()).getNegatedAtomicConcept(); 
						AtomicConcept negative = OverApproxExist.getNegationConcept(positive); 
						Atom atom1 = Atom.create(positive, X);
						Atom atom2 = Atom.create(negative, X);
						introducedConstraints.add(DLClause.create(new Atom[0], new Atom[] {atom1, atom2})); 
						newHeadAtoms.add( 
								Atom.create(
										AtLeastConcept.create(atLeast.getArity(), atLeast.getOnRole(), negative), 
										atom.getArgument(0)));
						changed = true; 
						continue; 
					}
				}
				else if (atom.getDLPredicate() instanceof AtLeastDataRange)
					changed = true; 
				else 
					newHeadAtoms.add(atom);
				
			}
			if (!changed) constraints.add(clause);
			else if (!newHeadAtoms.isEmpty()) {
				DLClause newClause = DLClause.create(newHeadAtoms.toArray(new Atom[0]), clause.getBodyAtoms()); 
				map.put(newClause, clause); 
				constraints.add(newClause); 
			}
		}
	}
	
	protected void addDatalogRule(DLClause clause) {
		rules.add(clause); 
		datalogRuleText.append(RuleHelper.getText(clause)).append(Utility_PAGOdA.LINE_SEPARATOR);
	}
	
	public static Atom getNegativeAtom(Atom atom) {
		if (atom.getDLPredicate() instanceof AtomicConcept) 
			return Atom.create(OverApproxExist.getNegationConcept(atom.getDLPredicate()), atom.getArgument(0));
		
		if (atom.getDLPredicate() instanceof Inequality)
			return Atom.create(Equality.INSTANCE, atom.getArgument(0), atom.getArgument(1)); 
		
		if (atom.getDLPredicate() instanceof Equality || atom.getDLPredicate() instanceof AnnotatedEquality)
			return Atom.create(Inequality.INSTANCE, atom.getArgument(0), atom.getArgument(1));  
					
		return null;
	}
	
	private StringBuilder datalogRuleText = new StringBuilder(); 
	
	public String getDatalogRuleText() {
		StringBuilder program = new StringBuilder(); 
		program.append(prefixes.prefixesText());
		program.append(datalogRuleText.toString()); 
		return program.toString();
	}

	protected void addDerivedPredicate(MultiStageQueryEngine engine) {
		TupleIterator derivedTuples = null; 
		try {
			derivedTuples = engine.internal_evaluateAgainstIDBs("select distinct ?z where { ?x <" + Namespace.RDF_TYPE + "> ?z . }"); 
			for (long multi = derivedTuples.open(); multi != 0; multi = derivedTuples.getNext()) {
				String p = prefixes.expandIRI(RDFoxTripleManager.getRawTerm(derivedTuples.getResource(0)));
				updatedPredicates.add(AtomicConcept.create(p)); 
			}
		} catch (JRDFStoreException e) {
			e.printStackTrace();
		} finally {
			if (derivedTuples != null) derivedTuples.dispose(); 
		}
		
		derivedTuples = null; 
		try {
			derivedTuples = engine.internal_evaluateAgainstIDBs("select distinct ?y where { ?x ?y ?z . }"); 
			for (long multi = derivedTuples.open(); multi != 0; multi = derivedTuples.getNext()) {
				String p = RDFoxTripleManager.getRawTerm(derivedTuples.getResource(0)); 
				if (p.equals(Namespace.RDF_TYPE_ABBR) || p.equals(Namespace.RDF_TYPE_QUOTED)) ;
				else if (p.equals(Namespace.EQUALITY_ABBR) || p.equals(Namespace.EQUALITY_QUOTED));
				else updatedPredicates.add(AtomicRole.create(prefixes.expandIRI(p))); 
			}
		} catch (JRDFStoreException e) {
			e.printStackTrace();
		} finally {
			if (derivedTuples != null) derivedTuples.dispose(); 
		}

	}

	public abstract Collection<Violation> isIntegrated(MultiStageQueryEngine engine, boolean incrementally);
	
	protected Violation violate(MultiStageQueryEngine engine, DLClause clause, boolean incrementally) {
		Logger_MORe.logTrace("checking constraint: " + clause);
			
		if (incrementally) {
			boolean affected = false; 
			for (Atom bodyAtom: clause.getBodyAtoms())
				if (updatedPredicates.contains(bodyAtom.getDLPredicate())) {
					affected = true; 
					break; 
				}
			
			for (Atom headAtom: clause.getHeadAtoms())
				if (headAtom.getDLPredicate() instanceof AtLeastConcept && 
						((AtomicConcept) ((AtLeastConcept) headAtom.getDLPredicate()).getToConcept()).getIRI().endsWith("_neg"))
					if (updatedPredicates.contains(OverApproxExist.getNegationConcept(((AtomicConcept) ((AtLeastConcept) headAtom.getDLPredicate()).getToConcept())))) {
						affected = true; 
						break; 
					}
			
			if (!affected) return null;
		}
		
		String[] vars = getCommonVars(clause);
		
		LinkedList<AnswerTupleID> bodyAnswers = getBodyAnswers(engine, clause, vars); 
		
		if (bodyAnswers.isEmpty()) return null;
		
		String headQuery; 
		String[] subVars; 
		Set<AnswerTupleID> headAnswers = new HashSet<AnswerTupleID>();
		
		t.reset();
		
		for (Atom headAtom: clause.getHeadAtoms()) {
//			Timer subTimer = new Timer(); 
			subVars = getVarSubset(vars, headAtom); 
			
			// TODO: conditions check negative existential restrictions
//			if (false) {
			if (headAtom.getDLPredicate() instanceof AtLeastConcept && 
					((AtomicConcept) ((AtLeastConcept) headAtom.getDLPredicate()).getToConcept()).getIRI().endsWith("_neg")) {
				AtLeastConcept alc = (AtLeastConcept) headAtom.getDLPredicate(); 
				String x = null, y = "Y"; 
				for (String var: subVars)
					if (var != null) x = var;
				if (x == "Y") y = "X"; 
				Atom[] atoms = new Atom[2];
				if (alc.getOnRole() instanceof AtomicRole) 
					atoms[0] = Atom.create((AtomicRole) alc.getOnRole(), Variable.create(x), Variable.create(y));
				else 
					atoms[0] = Atom.create(((InverseRole) alc.getOnRole()).getInverseOf(), Variable.create(y), Variable.create(x));
				
				atoms[1] = Atom.create(OverApproxExist.getNegationConcept((AtomicConcept) ((AtLeastConcept) headAtom.getDLPredicate()).getToConcept()), Variable.create(y));
				Set<AnswerTupleID> addAnswers = new HashSet<AnswerTupleID>(); 
				TupleIterator tuples = null; 
				try {
					tuples = engine.internal_evaluateNotExpanded(SparqlHelper.getSPARQLQuery(new Atom[] {atoms[0]}, x, y)); 
					for (long multi = tuples.open(); multi != 0; multi = tuples.getNext()) 
						addAnswers.add(new AnswerTupleID(tuples));
				} catch (JRDFStoreException e) {
					e.printStackTrace();
				} finally {
					if (tuples != null) tuples.dispose();
				}
				
				try {
					tuples = engine.internal_evaluateNotExpanded(SparqlHelper.getSPARQLQuery(atoms, x, y)); 
					for (long multi = tuples.open(); multi != 0; multi = tuples.getNext()) 
						addAnswers.remove(new AnswerTupleID(tuples));
				} catch (JRDFStoreException e) {
					e.printStackTrace();
				} finally {
					if (tuples != null) tuples.dispose();
				}

				for (AnswerTupleID tuple: addAnswers)
					headAnswers.add(project(tuple, new String[] {x, y}, new String[] {x, null}));
				addAnswers.clear();
			}
			
			headQuery = SparqlHelper.getSPARQLQuery(new Atom[] { headAtom }, subVars);
			TupleIterator tuples = null; 
			try {
				tuples = engine.internal_evaluateNotExpanded(headQuery); 
				for (; tuples.isValid(); tuples.getNext()) 
					headAnswers.add(new AnswerTupleID(tuples));
			} catch (JRDFStoreException e) {
				e.printStackTrace();
			} finally {
				if (tuples != null) tuples.dispose();
			}
			for (Iterator<AnswerTupleID> iter = bodyAnswers.iterator(); iter.hasNext(); ) 
				if (headAnswers.contains(project(iter.next(), vars, subVars)))
					iter.remove();
			headAnswers.clear();
		}
		
		Logger_MORe.logTrace("Time to rule out all head answers: " + t.duration() + " rest number: " + bodyAnswers.size());

		if (bodyAnswers.isEmpty()) return null;
		
		return new Violation(clause, bodyAnswers, vars); 
	}

	private Timer t = new Timer();
	
	protected LinkedList<AnswerTupleID> getBodyAnswers(MultiStageQueryEngine engine, DLClause clause, String[] vars) { //, boolean incrementally) {
		Collection<int[]> validIndexes = new LinkedList<int[]>();
		
		String[] subVars; 
		for (Atom headAtom: clause.getHeadAtoms()) {
			if (headAtom.getDLPredicate() instanceof Equality &&  headAtom.getArgument(0) instanceof Variable && headAtom.getArgument(1) instanceof Variable) {
				int[] validIndex = new int[2]; 
				subVars = getVarSubset(vars, headAtom); 
				for (int i = 0, j = 0; i < subVars.length; ++i)
					if (subVars[i] != null)
						validIndex[j++] = i; 
				validIndexes.add(validIndex); 
			}
		}
		
		LinkedList<AnswerTupleID> bodyAnswers = new LinkedList<AnswerTupleID>();
		t.reset();

		String bodyQuery = SparqlHelper.getSPARQLQuery(clause.getBodyAtoms(), vars);
		TupleIterator bodyTuples = null;
		boolean filtered; 
		try {
			bodyTuples = engine.internal_evaluateNotExpanded(bodyQuery);			
			for (long multi = bodyTuples.open(); multi != 0; multi = bodyTuples.getNext()) {
				filtered = false; 
				for (int[] validIndex: validIndexes)
					if (bodyTuples.getResourceID(validIndex[0]) == bodyTuples.getResourceID(validIndex[1])) {
						filtered = true; 
						break; 
					}
				if (!filtered)
					bodyAnswers.add(new AnswerTupleID(bodyTuples)); 
			}
		} catch (JRDFStoreException e) {
			e.printStackTrace();
		} finally {
			if (bodyTuples != null) bodyTuples.dispose();
		}
			
		validIndexes.clear();
		Logger_MORe.logTrace("Time to get all body answers: " + t.duration() + " number: " + bodyAnswers.size()); 
		return bodyAnswers; 
	}

	public static AnswerTupleID project(AnswerTupleID tuple, String[] vars, String[] subVars) {
		int subArity = 0; 
		for (int i = 0; i < subVars.length; ++i)
			if (subVars[i] != null) ++subArity; 
		
		if (tuple.getArity() == subArity) 
			return tuple;
		
		AnswerTupleID newTuple = new AnswerTupleID(subArity);
		for (int i = 0, j = 0; i < vars.length; ++i)
			if (subVars[i] != null && !subVars[i].isEmpty()) {
				newTuple.setTerm(j++, tuple.getTerm(i)); 
			}
		
		return newTuple; 
	}

	public static String[] getVarSubset(String[] vars, Atom... atoms) {
		String[] newVars = new String[vars.length];
		Set<Variable> allVars = new HashSet<Variable>();
		int arity; 
		for (Atom atom: atoms) {
			arity = atom.getArity();
			if (atom.getDLPredicate() instanceof AnnotatedEquality) arity = 2; 
			for (int j = 0; j < arity; ++j)
				if (atom.getArgument(j) instanceof Variable) { 
					allVars.add(atom.getArgumentVariable(j)); 
				}
		}
		 
		for (int i = 0; i < vars.length; ++i) {
			newVars[i] = allVars.contains(Variable.create(vars[i])) ? vars[i] : null; 
		}
		
		return newVars;
	}

	public static String[] getCommonVars(DLClause clause) {
		Set<Variable> headVars = getVariables(clause.getHeadAtoms()); 
		Set<Variable> bodyVars = getVariables(clause.getBodyAtoms()); 

		Collection<String> common = new LinkedList<String>(); 
		for (Variable v: headVars)
			if (bodyVars.contains(v)) common.add(v.getName()); 
			
		return common.toArray(new String[0]);
	}

	public static Set<Variable> getVariables(Atom[] atoms) {
		Set<Variable> v = new HashSet<Variable>(); 
		for (Atom atom: atoms) atom.getVariables(v);
		return v;
	}

	public Collection<DLClause> convertExist(DLClause clause, DLClause originalDLClause) {
		return m_bottom.process(m_approxExist.convert(clause, originalDLClause)); 
	}

	public void saveDatalogRules(String filename){
		try {
			BufferedWriter ruleWriter = new BufferedWriter(new OutputStreamWriter(
					new FileOutputStream(filename)));
			ruleWriter.write(getDatalogRuleText());
			ruleWriter.close();
		} catch (IOException e) {
			e.printStackTrace();
		}
		Logger_MORe.logDebug("The rules are saved in " + filename + "."); 
	}
	
	public void save(String filename) {
		try {
			BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(filename)));
			writer.write(getDatalogRuleText());
			for (DLClause clause: constraints) {
				writer.write(clause.toString(prefixes.getHermiTPrefixes()));
				writer.write(" .");
				writer.newLine();
			}
			writer.close();
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		} 
	}
	
	Set<DLPredicate> updatedPredicates = new HashSet<DLPredicate>(); 
	
//	public void addUpdatedPredicates(Set<DLPredicate> predicates) {
//		for (Iterator<DLPredicate> iter = predicates.iterator(); iter.hasNext(); ) {
//			updatedPredicate.add(iter.next());
//		}
//	}

	public void addUpdatedPredicate(DLPredicate predicate) {
		updatedPredicates.add(predicate);
	}
	
	protected abstract Collection<DLClause> getInitialClauses(Program program); 

	public Collection<DLClause> getClauses() {
		return clauses;
	}
	
	protected DLClause getOriginalClause(DLClause clause) {
		DLClause originalClause = map.get(clause);
		if (originalClause == null) return clause;
		return originalClause; 
	}
}