import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import org.ggp.base.apps.player.Player;
import org.ggp.base.player.gamer.exception.GamePreviewException;
import org.ggp.base.player.gamer.statemachine.StateMachineGamer;
import org.ggp.base.util.game.Game;
import org.ggp.base.util.statemachine.MachineState;
import org.ggp.base.util.statemachine.Move;
import org.ggp.base.util.statemachine.Role;
import org.ggp.base.util.statemachine.StateMachine;
import org.ggp.base.util.statemachine.cache.CachedStateMachine;
import org.ggp.base.util.statemachine.exceptions.GoalDefinitionException;
import org.ggp.base.util.statemachine.exceptions.MoveDefinitionException;
import org.ggp.base.util.statemachine.exceptions.TransitionDefinitionException;
import org.ggp.base.util.statemachine.implementation.prover.ProverStateMachine;

// Makes a fixed depth without heuristics move

public class DeepThinker extends StateMachineGamer {

	Player p;
	int limit = 1;
	double w1 = .6;
	double w2 = .4;
	double last_player_score = 0;
	boolean restrict = true;
	int timeoutPadding = 1000;
	int mcsCount = 7;
	long returnBy;
	Move bestSoFar = null;
	StateMachine machine = null;
	Role myRole = null;
	int depthchargeCount;
	MachineState origin = null;
	Set<MachineState> allNodes = new HashSet<MachineState>();

	@Override
	public StateMachine getInitialStateMachine() {
		return new CachedStateMachine(new ProverStateMachine());

		//return new PropnetStateMachine(); // changed to propnet machine
	}


	// This is where the pre-game calculations are done.
	@Override
	public void stateMachineMetaGame(long timeout)
			throws TransitionDefinitionException, MoveDefinitionException, GoalDefinitionException {
		depthchargeCount = 0;
		machine = getStateMachine();
		machine.initialize(getMatch().getGame().getRules());
		myRole = getRole();
		machine.getInitialState();
		//		ProverStateMachine machine2 = new ProverStateMachine();
		//		machine2.initialize(getMatch().getGame().getRules());
		//
		//
		//		StateMachineVerifier.checkMachineConsistency(machine2, machine, 20000);
	}


	public Move chooseMove(List<Move> possibleMoves) throws MoveDefinitionException, GoalDefinitionException, TransitionDefinitionException {

		List<Integer> possibleScores = new ArrayList<Integer>();
		for (int i = 0; i < possibleMoves.size(); i++) {
			Move m = possibleMoves.get(i);
			List<List<Move>> jointmoves = machine.getLegalJointMoves(origin, myRole, m);
			int total = 0;
			for (List<Move> p : jointmoves) {
				MachineState childState = machine.getNextState(origin, p);
				total += selectfn(childState, origin);
			}
			possibleScores.add((int) ((double)total / jointmoves.size()));
		}

		// Choosing the move that correlates to the highest montecarlo score
		Move bestChoice = null;
		int highestScore = 0;
		int numMoves = possibleMoves.size();
		System.out.println("We had " + numMoves + " moves available.");
		System.out.println(possibleScores.toString());
		System.out.println();
		for (int i = 0; i < numMoves; i++) {
			if (possibleScores.get(i) >= highestScore) {
				highestScore = possibleScores.get(i);
				bestChoice = possibleMoves.get(i);
			}
		}

		System.out.println("Depth charge count: " + depthchargeCount);
		depthchargeCount = 0;
		for (MachineState node : allNodes) {
			node.clearAll();
		}
		allNodes.clear();
		return bestChoice;
	}

	private boolean timeLeft(int calculationTime){
		if (returnBy - calculationTime > System.currentTimeMillis()) {
			return true;
		}
		return false;
	}

	@Override
	public Move stateMachineSelectMove(long timeout)
			throws TransitionDefinitionException, MoveDefinitionException, GoalDefinitionException {
		depthchargeCount = 0;
//		System.out.println(limit);

		int calculationTime = 1000;
		returnBy = timeout - timeoutPadding;


		origin = getCurrentState();
		long start = System.currentTimeMillis();
		List<Move> possibleMoves = machine.getLegalMoves(origin, myRole);
		// Creates a list of the same size as possibleMoves, all populated with 0s
		List<Integer> possibleScores = new ArrayList<Integer>((Collections.nCopies(possibleMoves.size(), 0)));
		int numMoves = possibleMoves.size();
		if (numMoves == 1) {
			System.out.println("There was only one move to make!");
			return possibleMoves.get(0);

		}

		while (timeLeft(calculationTime)) {
			MachineState state = select(origin);
			if (state == null) {
				return chooseMove(possibleMoves);
			}
			if (!expand(state)) {
				backpropogate(state, machine.getGoal(state, myRole));
				continue;
			}
			for (int j = 0; j < state.getChildren().size(); j++) {
				MachineState child = state.getChildren().get(j);
				int score = montecarlo(myRole, child, machine);
				backpropogate(state, score);
			}
		}

		System.out.println("Depth charge count: " + depthchargeCount);
		return chooseMove(possibleMoves);

	}

	public int oppoProximity(List<Role> opponents, MachineState state, StateMachine machine) throws GoalDefinitionException {
		double score = 0.0;
		for (Role opponent: opponents) {
			score += machine.getGoal(state, opponent);
		}
		return 100 - (int)(score / opponents.size());
	}


	public List<Role> findOpponents(Role role, StateMachine machine) {
		List<Role> allRoles = machine.getRoles();
		List<Role> opponents = new ArrayList<Role>();
		for (Role testRole: allRoles) {
			if (testRole != role) opponents.add(testRole);
		}
		return opponents;
	}

	public int evalfn(Role role, MachineState state, StateMachine machine) throws MoveDefinitionException, GoalDefinitionException {
		//return 0; // for non heuristics
		double player = (double)machine.getGoal(state, role); // for simple goal proximity
		double opp = (double)oppoProximity(findOpponents(role, machine), state, machine);
		double f1 = (double)mobility(role, state, machine); // for mobility heuristic
		double f2 = (double)focus(role, state, machine); // for focus heuristic

		//update strategy based on whether your score is going up or down from last move
		//if the opponent's score is higher than our player's score
		if (opp > player) {
			//if our score went down from the last round
			if (player < last_player_score) {
//				System.out.println("Switching strategies!");
				restrict = !restrict;
				if (restrict) {
					w2 = w1; // Not sure why we kept cutting this factor in half
					w1 = 1.0 - w2;
				} else {
					w1 = w2;
					w2 = 1.0 - w1;
				}
			}
			last_player_score = player;
		}

		// If we are really close to a goal state, emphasize that over the mobility/focus heuristics
		if (player > (w1*f1 + w2*f2)) {
			System.out.println("We are just gonna go for the goal.");
		}
		return (int)Math.max(player, (w1*f1 + w2*f2)); //(int)(w1*f1 + w2*f2);
	}

	public int selectfn(MachineState node, MachineState parent) throws MoveDefinitionException, GoalDefinitionException {
		if (machine.isTerminal(node)) return machine.getGoal(node, myRole);
		return (int) ((double)node.getUtility()/(double)node.getVisits() + Math.sqrt(2*Math.log(parent.getVisits())/node.getVisits()));
	}

	public MachineState select(MachineState node) throws MoveDefinitionException, TransitionDefinitionException, GoalDefinitionException {
//		System.out.println("select executed");
		if (node.getVisits() == 0) {;
			return node;
		}
		if (machine.isTerminal(node)) return node;
		if (!(timeLeft(1000))) {
			return null;
		}
		List<MachineState> myChildren = node.getChildren();

		// Return first child that has not been visited yet
		for (MachineState child : myChildren) {
			if (child.getVisits() == 0) {
				return child;
			}
		}
		int score = Integer.MIN_VALUE;
		MachineState result = node;
		for (MachineState child: myChildren) {
			int newscore = selectfn(child, node);
			if (newscore>score) {
				score = newscore;
				result=child;
			}
		}
//		System.out.println("result is: " + result);
		return select(result);
	}

		public boolean expand (MachineState node) throws MoveDefinitionException, TransitionDefinitionException {
//			System.out.println("expand executed");
			List<List<Move>> moves = machine.getLegalJointMoves(node);
			for (List<Move> scenario : moves) {
				MachineState newstate = machine.getNextState(node, scenario);
				newstate.setVisits(0);
				newstate.setUtility(0);
				newstate.setParent(node);
				if (!machine.isTerminal(node)) {
					if (node.getChildren() == null) {
						node.setChildren(new ArrayList<MachineState>());
					}
					List<MachineState> children = node.getChildren();
					children.add(newstate);
					node.setChildren(children);
				} else {
					return false;
				}
			}
			return true;
		}

	public boolean backpropogate(MachineState node,  int score) {
		allNodes.add(node);
		node.setVisits(node.getVisits() + 1);
		node.setUtility(node.getUtility() + score);
		if (node.getParent() != null && node.getParent().getChildren() != null) {
			if (node.getParent().getChildren().contains(node)) return true;
		}
		if (node.getParent() != null) {
			backpropogate(node.getParent(), score);
		}
		return true;
}

public int montecarlo(Role role, MachineState state, StateMachine machine) throws GoalDefinitionException, MoveDefinitionException, TransitionDefinitionException {
	int total = 0;
	for (int i = 0; i < mcsCount; i++) {
		total = total + depthcharge(role, state, machine, new ArrayList<MachineState>());
	};
	return (int) ((double)total/mcsCount);
}

public int depthcharge (Role role, MachineState state, StateMachine machine, List<MachineState> visited) throws GoalDefinitionException, MoveDefinitionException, TransitionDefinitionException {
	if (machine.findTerminalp(state)) {
		depthchargeCount++;
		return machine.findReward(role, state);
	};
	if (!timeLeft(0)) {
		return machine.getGoal(state, role);
	}
	List<List<Move>>possibles = machine.getLegalJointMoves(state);
	Random randomizer = new Random();
	int random = randomizer.nextInt(possibles.size());
	MachineState newstate = machine.getNextState(state, possibles.get(random));
	return depthcharge(role,newstate, machine, visited);
}


public int mobility(Role role, MachineState state, StateMachine machine) throws MoveDefinitionException {
	List<Move> actions = machine.getLegalMoves(state, role);

	List<Move> feasibles = machine.findActions(role);
	int mobility = (int)((double)actions.size()/(double)feasibles.size() * 100);
	//		System.out.println(mobility);
	return mobility;
}

public int focus(Role role, MachineState state, StateMachine machine) throws MoveDefinitionException {
	return 100 - mobility(role, state, machine);
}


@Override
public void stateMachineStop() {
	// TODO Auto-generated method stub

}

@Override
public void stateMachineAbort() {
	// TODO Auto-generated method stub

}

@Override
public void preview(Game g, long timeout) throws GamePreviewException {
	// TODO Auto-generated method stub

}

@Override
public String getName() {
	// TODO Auto-generated method stub
	return "DeepThinker";
}

//
//public GdlRule prunesubgoals(GdlRule rule) throws SymbolFormatException {
//	List<GdlLiteral> vl = new ArrayList<GdlLiteral>();
//	vl.add(rule.get(0));
//	List<GdlLiteral> newrule = new ArrayList<GdlLiteral>();
//	newrule.add(rule.get(0));
//	for (int i = 2; i < rule.arity(); i++) {
//		List<GdlLiteral> sl = newrule;
//		for (int x = i + 1; x < rule.arity(); x++) {
//			sl.add(rule.get(x));
//		}
//		GdlRule arg1 = GdlPool.getRule(GdlFactory.createTerm("rule").toSentence(), sl);
//		GdlLiteral arg2 = rule.get(i);
//		GdlRule arg3 = GdlPool.getRule(GdlFactory.createTerm("rule").toSentence(), vl);
//	    if (!pruneworthyp(arg1, arg2, arg3)) {
//	    	newrule.add(rule.get(i));
//		}
//	};
//	GdlRule result = GdlPool.getRule(GdlFactory.createTerm("rule").toSentence(), newrule);
//	return result;
//	}
//
//public boolean pruneworthyp (GdlRule sl, GdlLiteral p, GdlRule vl) {
//	vl = varsexp(sl, vl.getBody());
//	HashMap<GdlLiteral, String> al = new HashMap<GdlLiteral, String>();
//	for (int i=0; i < vl.arity(); i++) {
//		Integer x = i;
//		al.put(vl.get(i), "x" + x.toString());
//		// but vl is just one variable long.. see prunesubgoals
//	}
//	GdlRule facts = sublis(sl,al);
//	GdlRule goal = sublis(p,al); // how are we putting p in as well when p and sl have different types?
//	return compfindp(goal,facts);
//}
//
//public boolean compfindp(GdlRule goal, GdlRule facts) {
//	for (int i = 0; i < facts.arity(); i++) {
//		if (goal.get(0) == facts.get(i)) return true;
//	}
//	return false;
//}
//
//public GdlRule sublis(GdlRule a, GdlRule b) throws SymbolFormatException {
//	List<GdlLiteral> c = new ArrayList<GdlLiteral>();
//	for (int i = 0; i < a.arity(); i++) {
//		// detect first variable in a
//		// save the rule structure of a[i]
//		// and variable of b[i]
//		// at c[i]
//	}
//	GdlRule result = GdlPool.getRule(GdlFactory.createTerm("rule").toSentence(), c);
//	return result;
//}
//
//
//
//
//
//
//public List<GdlRule> prunerules (List<Gdl> list) {
//	List<GdlRule> rules = new ArrayList<GdlRule>();
//
//
//	for (Gdl g : list) {
//		if (g.toString().indexOf("( <= (") == 0) {
//			rules.add((GdlRule) g);
//		}
//	}
//
//
//	List<GdlRule> newRules = new ArrayList<GdlRule>();
//	for (int i = 0; i < rules.size(); i++) {
//
//		if (!subsumedp(rules.get(i), newRules) && (!(subsumedp(rules.get(i), rules.subList(i+1, rules.size()))))) {
//			newRules.add(rules.get(i));
//		}
//	}
//	return newRules;
//}
//
//public boolean subsumedp (GdlRule rule, List<GdlRule> rules) {
//	for (int i = 0; i < rules.size(); i++) {
//		if (subsumesp(rules.get(i), rule)) {
//			return true;
//		}
//	}
//	return false;
//}
//
//public boolean subsumesp (GdlRule pRule, GdlRule qRule) {
//
//	List<GdlLiteral> p = pRule.getBody();
//	List<GdlLiteral> q = qRule.getBody();
//	System.out.println(q.toString());
//	System.out.println(p.toString());
//	System.out.println();
//
//	if (p.equals(q)) {
//		return true;
//	}
//
//	// if (symbolp(p) || symbolp(q)) {
//	//	return false;
//	// }
//
//	for (GdlLiteral pLit : p) {
//		for (GdlLiteral qLit : q) {
//			Map<String, String> al = matcher(pLit, qLit);
//			if (al != null && subsumesexp(p.subList(1, p.size()), q.subList(1, q.size()), al)) {
//				return true;
//			}
//		}
//	}
//
//	return false;
//}
//
//
//public boolean subsumesexp (List<GdlLiteral> pl, List<GdlLiteral> QL, Map<String, String> AL) {
//	if (pl.size() == 0) {
//		return true;
//	}
//	for (int i = 0; i < QL.size(); i++) {
//		Map<String, String> bl = matcher(pl.get(0), QL.get(i)/*, AL*/);
//		if (bl != null && subsumesexp(pl.subList(1, pl.size()), QL, bl)) {
//			return true;
//		}
//	}
//	return false;
//}
//
//public Map<String, String> matcher (GdlLiteral p, GdlLiteral q) {
//	System.out.println("Now in matcher");
//
//	Map<String, String> toReturn = new HashMap<String, String>();
//	String[] pString = p.toString().split(" ");
//	String[] qString = q.toString().split(" ");
//	System.out.println("Literal string for p: ");
//	for (String s : pString) {
//		if (s.contains("?")) {
//			System.out.println(s);
//		}
//	}
//	System.out.println("Literal string for q: ");
//	for (String s : qString) {
//		if (s.contains("?")) {
//			System.out.println(s);
//		}
//	}
//	System.out.println();
//
//
//	if (toReturn.size() == 0) {
//		return null;
//	}
//	return toReturn;
//}

}