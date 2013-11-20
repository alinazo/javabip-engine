package org.bip.engine;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Enumeration;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;

import net.sf.javabdd.BDD;
import net.sf.javabdd.BDDFactory;

import org.bip.api.BIPComponent;
import org.bip.api.Behaviour;
import org.bip.behaviour.Port;
import org.bip.exceptions.BIPEngineException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Receives the current state, glue and behaviour BDDs.
 * Computes the possible maximal interactions and picks one non-deterministically.
 * Notifies the BIPCoordinator about the outcome.
 * @author mavridou
 */
public class BDDBIPEngineImpl implements BDDBIPEngine {
	
	private Logger logger = LoggerFactory.getLogger(BDDBIPEngineImpl.class);
	private Hashtable<BIPComponent, BDD> currentStateBDDs = new Hashtable<BIPComponent, BDD>();
	private ArrayList <BDD> disabledCombinationBDDs = new ArrayList<BDD>();
	private Hashtable<BIPComponent, BDD> behaviourBDDs = new Hashtable<BIPComponent, BDD>();
	/* BDD for ΛFi */
	private BDD totalBehaviour;
	/* BDD for Glue */
	private BDD totalGlue;
	
	private BDD totalBehaviourAndGlue;
	
	//
	private int noNodes=10000;
	private int cacheSize=1000;
	
	/* Use JavaBDD Bdd Manager */
	private BDDFactory bdd_mgr= BDDFactory.init("java", noNodes, cacheSize); 
	private ArrayList<Integer> positionsOfPorts = new ArrayList<Integer>();
	Hashtable<Port, Integer> portToPosition= new Hashtable<Port, Integer>();
	Hashtable<Integer, BiDirectionalPair> dVariablesToPosition = new Hashtable<Integer, BiDirectionalPair>();
	ArrayList<Integer> positionsOfDVariables = new ArrayList<Integer>();

	private BIPCoordinator wrapper;
	private BDD dataGlueBDD;

	/** 
	 * Counts the number of enabled ports in the Maximal cube chosen 
	 */
	private int countPortEnable(byte[] in_cube, ArrayList<Integer> allPorts){
		int out_count = 0;
		
		for (int i = 0; i < allPorts.size(); i++) {
			if (in_cube[allPorts.get(i)] == 1 || in_cube[allPorts.get(i)] == -1)
				out_count++;
		}
		return out_count;
	}

	/**
	 * @return 0 equal <br>
	 *         1 if cube2 in cube1, cube1 bigger <br>
	 *         2 not comparable 
	 *         3 if cube1 in cube2, cube2 bigger <br>
	 */
	private int compareCube(byte[] cube1, byte[] cube2, ArrayList<Integer> portBDDsPosition) {
		boolean cube1_big = false;
		boolean cube2_big = false;
		
		for (int i = 0; i < portBDDsPosition.size(); i++) {
			logger.debug("portBDDsPosition: "+portBDDsPosition.get(i));
			if ((cube1[portBDDsPosition.get(i)] != 0) && (cube2[portBDDsPosition.get(i)] == 0)) {
				cube1_big = true;
			} else if ((cube2[portBDDsPosition.get(i)] != 0) && (cube1[portBDDsPosition.get(i)] == 0)) {
				cube2_big = true;
			}
		}
		/* if cube1 is bigger than cube2 (cube1 contains cube2) */
		if (cube1_big && !cube2_big)
			return 1;
		/* if cubes are not comparable */
		else if (cube1_big && cube2_big)
			return 2;
		/* if cube1 is smaller than cube2 (cube2 contains cube1) */
		else if (!cube1_big && cube2_big)
			return 3;
		/* if cubes are equal */
		else
			return 0;
	}

	/** Copy maximal cube */
	private void addCube(ArrayList<byte[]> cubeMaximals, byte[] cube, int position) {
		logger.debug("Cube length: "+cube.length);
		for (int i = 0; i < cube.length; i++)
			if (cube[i] == -1)
				cube[i] = 1;
		cubeMaximals.add(position, cube);
	}

	private void findMaximals(ArrayList<byte[]> cubeMaximals, byte[] c_cube, ArrayList<Integer> portBDDsPosition) {
		int size = cubeMaximals.size();
		logger.debug("findMaximals size: "+size);
		for (int i = 0; i < size; i++) {
			int comparison = compareCube(c_cube, cubeMaximals.get(i), portBDDsPosition);
			if (comparison == 1 || comparison == 0) {
				cubeMaximals.remove(i);
				addCube(cubeMaximals, c_cube, i);
				return;
			}
			if (comparison == 3)
				return;
		}
		
		addCube(cubeMaximals, c_cube, cubeMaximals.size());
	}
	

	public final BDD totalCurrentStateBdd(Hashtable<BIPComponent, BDD> currentStateBDDs) {
		BDD totalCurrentStateBdd = bdd_mgr.one();
		BDD tmp;

		for (Enumeration<BIPComponent> componentsEnum = currentStateBDDs.keys(); componentsEnum.hasMoreElements(); ){
			BIPComponent component = componentsEnum.nextElement();
			if (currentStateBDDs.get(component)==null){
				logger.error("Current state BDD is null of component {}", component);
				//TODO: Add exception
			}
			tmp = totalCurrentStateBdd.and(currentStateBDDs.get(component));
			totalCurrentStateBdd.free();
			totalCurrentStateBdd = tmp;
		}
		return totalCurrentStateBdd;	
	}
	

	public final BDD totalDisabledCombinationsBdd(ArrayList<BDD> disabledCombinationBDDs) {
		BDD totalDisabledCombinationBdd = bdd_mgr.one();

		for (BDD disabledCombinationBDD : disabledCombinationBDDs ){
			if (disabledCombinationBDD==null){
				logger.error("Disabled Combination BDD is null");
				//TODO: Add exception
			}
			totalDisabledCombinationBdd.andWith(disabledCombinationBDD);
		}
		return totalDisabledCombinationBdd;	
	}

	public synchronized final void runOneIteration() throws BIPEngineException {

		byte[] chosenInteraction;
		ArrayList<byte[]> cubeMaximals = new ArrayList<byte[]>();
		Hashtable<BIPComponent, ArrayList<Port>> chosenPorts = new Hashtable<BIPComponent, ArrayList<Port>>();
		ArrayList<BIPComponent> chosenComponents = new ArrayList<BIPComponent>();
		byte[] cubeMaximal = new byte[wrapper.getNoPorts() + wrapper.getNoStates()+ positionsOfDVariables.size()];

		cubeMaximals.add(0, cubeMaximal);

		BDD totalCurrentStateAndDisabledCombinations;
		logger.debug("RUN ONE INTERACTION: DISABLED COMBINATIONS BDD SIZE:  "+ disabledCombinationBDDs.size());
		if (!disabledCombinationBDDs.isEmpty() || disabledCombinationBDDs != null){
			totalCurrentStateAndDisabledCombinations = totalCurrentStateBdd(currentStateBDDs).and(totalDisabledCombinationsBdd(disabledCombinationBDDs));
			if (totalCurrentStateAndDisabledCombinations==null) {
				try {
					logger.error("Total Current States BDD is null");
					throw new BIPEngineException("Total Current States BDD is null with disabled combinations");
				} catch (BIPEngineException e) {
					e.printStackTrace();
					throw e;	
				}
			}
		}
		else{
			/* Λi Ci */
			totalCurrentStateAndDisabledCombinations = totalCurrentStateBdd(currentStateBDDs);

			if (totalCurrentStateAndDisabledCombinations==null) {
				try {
					logger.error("Total Current States BDD is null");
					throw new BIPEngineException("Total Current States BDD is null");
				} catch (BIPEngineException e) {
					e.printStackTrace();
					throw e;	
				}
			}	
		}
		
		/* Compute global BDD: solns= Λi Fi Λ G Λ (Λi Ci) */
		BDD solns = totalBehaviourAndGlue.and(totalCurrentStateAndDisabledCombinations);

		if (solns==null ) {
			try {
				logger.error("Global BDD is null");
				throw new BIPEngineException("Global BDD is null");
			} catch (BIPEngineException e) {
				e.printStackTrace();
				throw e;	
			}
		}	
		totalCurrentStateAndDisabledCombinations.free();
		ArrayList<byte[]> a = new ArrayList<byte[]>();
		

		/*
		 * Re-ordering function and statistics printouts
		 */
//		bdd_mgr.reorder(BDDFactory.REORDER_SIFTITE);
//		logger.info("Reorder stats: "+bdd_mgr.getReorderStats());

		a.addAll(solns.allsat()); // TODO, can we find random maximal
								  // interaction without getting all solutions
								  // at once?

		logger.info("******************************* Engine **********************************");
		logger.info("Number of possible interactions is: {} ", a.size());
		Iterator<byte[]> it = a.iterator();

		/* for debugging */
		while (it.hasNext()) {
			byte[] value = it.next();

			StringBuilder sb = new StringBuilder();
			for (byte b : value) {
				sb.append(String.format("%02X ", b));
			}
			logger.info(sb.toString());
		}
		ArrayList<Integer> positions = new ArrayList<Integer>();
		positions.addAll(positionsOfPorts);
		positions.addAll(positionsOfDVariables);
		logger.debug("a size: "+a.size());
		for (int i = 0; i < a.size(); i++){

			logger.info("Positions of D Variables size:"+ positionsOfDVariables.size());
			findMaximals(cubeMaximals, a.get(i), positions);
		}

		/* deadlock detection */
		int size = cubeMaximals.size();
		if (size == 0) {
			try {
				throw new BIPEngineException("Deadlock. No maximal interactions.");
			} catch (BIPEngineException e) {
				e.printStackTrace();
				logger.error(e.getMessage());	
				throw e;
			} 
		} else if (size == 1) { 
			if (countPortEnable(cubeMaximals.get(0), positions) == 0) {
				try {
					throw new BIPEngineException("Deadlock. No enabled ports.");
				} catch (BIPEngineException e) {
					e.printStackTrace();
					logger.error(e.getMessage());	
					throw e;
				} 
			}
		}

		logger.info("Number of maximal interactions: " + cubeMaximals.size());
		Random rand = new Random();
		/*
		 * Pick a random maximal interaction
		 */
		int randomInt = rand.nextInt(cubeMaximals.size());
		/*
		 * Update chosen interaction
		 */
		chosenInteraction = cubeMaximals.get(randomInt); 
		
		cubeMaximals.clear();
		logger.debug("ChosenInteraction: ");
		for (int k = 0; k < chosenInteraction.length; k++)
			logger.debug("{}",chosenInteraction[k]);
		
		//TODO: Fix String to Port
		ArrayList<Port> portsExecuted = new ArrayList<Port>();
		ArrayList <Map<BIPComponent, Iterable<Port>>> allInteractions = new ArrayList<Map<BIPComponent,Iterable<Port>>>() ;
		
		logger.info("positionsOfDVariables size: "+ positionsOfDVariables.size());
		logger.info("PositionsOfPorts size: "+ positionsOfPorts.size());
		for (Integer i: positionsOfDVariables){
			Hashtable<BIPComponent, ArrayList<Port>> oneInteraction = new Hashtable<BIPComponent, ArrayList<Port>>();
			
			if (chosenInteraction[i]==1){
				logger.debug("chosenInteraction length " +chosenInteraction.length);
				BiDirectionalPair pair = dVariablesToPosition.get(i);
				BiDirectionalPair firstPair = (BiDirectionalPair) pair.getFirst();
				BiDirectionalPair secondPair = (BiDirectionalPair) pair.getSecond();
				ArrayList<Port> componentPorts = new ArrayList<Port>();	
				Port port = (Port) firstPair.getSecond();
				componentPorts.add(port);
				
				
				BIPComponent component = (BIPComponent) firstPair.getFirst();
				logger.debug("Chosen Component: {}", component.getName());
				logger.debug("Chosen Port: {}", componentPorts.get(0).id);
				ArrayList<Port> componentPorts2 = new ArrayList<Port>();
				Port port2 = (Port) secondPair.getSecond();
				componentPorts2.add(port2);
				
				BIPComponent component2 = (BIPComponent) secondPair.getFirst();
				logger.debug("Chosen Component: {}", component2.getName());
				logger.debug("Chosen Port: {}", componentPorts2.get(0).id);
							
				boolean found = false;
				Map <BIPComponent, Iterable<Port>> mergedInteractions = new Hashtable<BIPComponent, Iterable<Port>>();
				ArrayList<Integer> indexOfInteractionsToBeDeleted = new ArrayList<Integer>();
				portsExecuted.add(port);
				portsExecuted.add(port2);

			
				for (Map<BIPComponent, Iterable<Port>> interaction: allInteractions)
				{
					if(found == false){
						if (interaction.containsKey((BIPComponent) firstPair.getFirst()) && interaction.containsKey((BIPComponent) secondPair.getFirst())){
							if (interaction.get((BIPComponent) firstPair.getFirst()).iterator().next().id.equals(port.id) && interaction.get((BIPComponent) secondPair.getFirst()).iterator().next().id.equals(port2.id)){
								found = true;
								logger.info("Double match");
								logger.info("Merged interactions size: "+mergedInteractions.size());
							}
						}
						else if (interaction.containsKey((BIPComponent) firstPair.getFirst()) && !interaction.containsKey((BIPComponent) secondPair.getFirst())){
							if (interaction.get((BIPComponent) firstPair.getFirst()).iterator().next().id.equals(port.id)){
								found = true;
								indexOfInteractionsToBeDeleted.add(allInteractions.indexOf(interaction));
								mergedInteractions.putAll(interaction);
								mergedInteractions.put((BIPComponent) secondPair.getFirst(), (ArrayList<Port>) componentPorts2);
							}
						}
						else if (interaction.containsKey((BIPComponent) secondPair.getFirst()) && !interaction.containsKey((BIPComponent) firstPair.getFirst())){
							if (interaction.get((BIPComponent) secondPair.getFirst()).iterator().next().id.equals(port2.id)){
								found = true;
								indexOfInteractionsToBeDeleted.add(allInteractions.indexOf(interaction));
								mergedInteractions.putAll(interaction);
								mergedInteractions.put((BIPComponent) firstPair.getFirst(), (ArrayList<Port>) componentPorts);
							}
						}
					}
				}
				if (found == false){
					oneInteraction.put((BIPComponent) firstPair.getFirst(), (ArrayList<Port>) componentPorts);
					oneInteraction.put((BIPComponent) secondPair.getFirst(), (ArrayList<Port>) componentPorts2);
					((List) allInteractions).add(oneInteraction);
				}
				else{
					logger.debug("indexOfInteractionsToBeDeleted size: "+ indexOfInteractionsToBeDeleted.size());
					for (Integer index: indexOfInteractionsToBeDeleted){
						logger.debug("allInteractions size before removing: "+ allInteractions.size());
						allInteractions.remove(allInteractions.get(index));
						logger.debug("allInteractions size after removing: "+ allInteractions.size());
					}
					if (mergedInteractions.size()!=0){
						logger.debug("mergedInteractions size: "+ mergedInteractions.size());
						allInteractions.add(mergedInteractions);
					}
				}
			}	
		}

		for (Enumeration<BIPComponent> componentsEnum = behaviourBDDs.keys(); componentsEnum.hasMoreElements(); ){
			BIPComponent component = componentsEnum.nextElement();
			logger.debug("Component: "+component.getName());
			
			Iterable <Port> componentPorts = wrapper.getBehaviourByComponent(component).getEnforceablePorts();
			if (componentPorts == null || !componentPorts.iterator().hasNext()){
				logger.warn("Component {} does not have any enforceable ports.", component.getName());		
			} 			
			ArrayList <Port> enabledPorts = new ArrayList<Port>();

			for (Port componentPort : componentPorts){
				if(!portsExecuted.contains(componentPort) && chosenInteraction[portToPosition.get(componentPort)]==1){
					enabledPorts.add(componentPort);
				}
			}
			if (!enabledPorts.isEmpty()) {
				logger.info("Chosen Component: {}", component.getName());
				logger.info("Chosen Port: {}", enabledPorts.get(0).id);
			}
			if (enabledPorts.size()!=0){
				chosenPorts.put(component, enabledPorts);
				chosenComponents.add(component);
			}
		}
		
		logger.info("*************************************************************************");
		logger.info("chosenPorts size: "+chosenPorts.size());
		if (chosenPorts.size()!=0){
			((List) allInteractions).add(chosenPorts);
		}


		for (Map<BIPComponent, Iterable<Port>> inter: allInteractions)
		{
			for (Map.Entry <BIPComponent, Iterable<Port>> e:inter.entrySet())
			{
				System.out.println("ENGINE ENTRY: "+ e.getKey().hashCode()+ " - "+ e.getValue());
			}
		}
		wrapper.execute(allInteractions);

		portsExecuted.clear();

		solns.free();
		disabledCombinationBDDs.clear();
//		for (BDD disabledCombination: disabledCombinationBDDs){
//			disabledCombination.free();
//		}

	}
	
	public synchronized void informCurrentState(BIPComponent component, BDD componentBDD) {
		currentStateBDDs.put(component, componentBDD);
	}
	
	public synchronized void informSpecific(final BDD informSpecific) {
		disabledCombinationBDDs.add(informSpecific);
		logger.info("INFORM SPECIFIC CALL: Disabled Combinations size "+ disabledCombinationBDDs.size());
	}
	
	public synchronized void specifyDataGlue(BDD specifyDataGlue) {
		this.dataGlueBDD = specifyDataGlue;
		if (totalBehaviourAndGlue!= null){
			totalBehaviourAndGlue.andWith(dataGlueBDD);
		}
		else if(this.totalGlue!=null){
			totalGlue.andWith(dataGlueBDD);
		}
		else{
			return;
		}
		
	}
	
	public synchronized void informBehaviour(BIPComponent component, BDD componentBDD) {
		behaviourBDDs.put(component, componentBDD);
	}
	
	public synchronized final void totalBehaviourBDD() throws BIPEngineException{
		
		BDD totalBehaviourBdd = bdd_mgr.one();
		BDD tmp;
		
		for (Enumeration<BIPComponent> componentsEnum = behaviourBDDs.keys(); componentsEnum.hasMoreElements(); ){
			tmp = totalBehaviourBdd.and(behaviourBDDs.get(componentsEnum.nextElement()));
			totalBehaviourBdd.free();
			totalBehaviourBdd = tmp;
		}
		this.totalBehaviour=totalBehaviourBdd;
//		synchronized (totalBehaviourAndGlue) {
			if (totalGlue!=null){
				totalBehaviourAndGlue=this.totalBehaviour.and(totalGlue);	
				if (totalBehaviourAndGlue == null) {
					try {
						logger.error("Total Behaviour and Glue is null");
						throw new BIPEngineException("Total Behaviour and Glue is null");
					} catch (BIPEngineException e) {
						e.printStackTrace();
						throw e;	
					}
				}	
				this.totalBehaviour.free();
				totalGlue.free();
			}
//		}
	}

	public synchronized void informGlue(BDD totalGlue) throws BIPEngineException {

		this.totalGlue = totalGlue;
		//TODO: Fix synchronized
//		synchronized (totalBehaviourAndGlue) {
			if (this.totalBehaviourAndGlue==null && this.totalBehaviour!=null){
				totalBehaviourAndGlue=totalBehaviour.and(this.totalGlue);
//						.and(this.dataGlueBDD);	//
				if (this.dataGlueBDD!=null){
					totalBehaviourAndGlue.andWith(dataGlueBDD);
				}
//				bdd_mgr.reorder(BDDFactory.REORDER_SIFTITE);
//				logger.info("Reorder stats: "+bdd_mgr.getReorderStats());
				if (totalBehaviourAndGlue == null ) {
					try {
						logger.error("Total Behaviour and Glue is null");
						throw new BIPEngineException("Total Behaviour and Glue is null");
					} catch (BIPEngineException e) {
						e.printStackTrace();
						throw e;	
					}
				}	
				this.totalBehaviour.free();
				this.totalGlue.free();
			}
//		}
	}
	
	public ArrayList<Integer> getPositionsOfPorts() {
		return positionsOfPorts;
	}
	
	public Hashtable<Port, Integer> getPortToPosition() {
		return portToPosition;
	}
	
	/**
	 * @return the dVariablesToPosition
	 */
	public Hashtable<Integer, BiDirectionalPair> getdVariablesToPosition() {
		return dVariablesToPosition;
	}

	/**
	 * @param dVariablesToPosition the dVariablesToPosition to set
	 */
	public void setdVariablesToPosition(Hashtable<Integer, BiDirectionalPair> dVariablesToPosition) {
		this.dVariablesToPosition = dVariablesToPosition;
	}
	
	/**
	 * @return the positionsOfDVariables
	 */
	public ArrayList<Integer> getPositionsOfDVariables() {
		return positionsOfDVariables;
	}

	/**
	 * @param positionsOfDVariables the positionsOfDVariables to set
	 */
	public void setPositionsOfDVariables(ArrayList<Integer> positionsOfDVariables) {
		this.positionsOfDVariables = positionsOfDVariables;
	}

	public void setOSGiBIPEngine(BIPCoordinator wrapper) {
		this.wrapper = wrapper;
	}
	
	public synchronized BDDFactory getBDDManager() {
		return bdd_mgr;
	}





}
