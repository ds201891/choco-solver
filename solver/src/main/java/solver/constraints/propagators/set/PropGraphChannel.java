/**
 *  Copyright (c) 1999-2011, Ecole des Mines de Nantes
 *  All rights reserved.
 *  Redistribution and use in source and binary forms, with or without
 *  modification, are permitted provided that the following conditions are met:
 *
 *      * Redistributions of source code must retain the above copyright
 *        notice, this list of conditions and the following disclaimer.
 *      * Redistributions in binary form must reproduce the above copyright
 *        notice, this list of conditions and the following disclaimer in the
 *        documentation and/or other materials provided with the distribution.
 *      * Neither the name of the Ecole des Mines de Nantes nor the
 *        names of its contributors may be used to endorse or promote products
 *        derived from this software without specific prior written permission.
 *
 *  THIS SOFTWARE IS PROVIDED BY THE REGENTS AND CONTRIBUTORS ``AS IS'' AND ANY
 *  EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 *  WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 *  DISCLAIMED. IN NO EVENT SHALL THE REGENTS AND CONTRIBUTORS BE LIABLE FOR ANY
 *  DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 *  (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 *  LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 *  ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 *  (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 *  SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

/**
 * Created by IntelliJ IDEA.
 * User: Jean-Guillaume Fages
 * Date: 14/01/13
 * Time: 16:36
 */

package solver.constraints.propagators.set;

import choco.kernel.ESat;
import choco.kernel.common.util.procedure.IntProcedure;
import choco.kernel.common.util.procedure.PairProcedure;
import choco.kernel.common.util.tools.ArrayUtils;
import choco.kernel.memory.setDataStructures.ISet;
import solver.Solver;
import solver.constraints.Constraint;
import solver.constraints.propagators.Propagator;
import solver.constraints.propagators.PropagatorPriority;
import solver.exception.ContradictionException;
import solver.variables.EventType;
import solver.variables.SetVar;
import solver.variables.Variable;
import solver.variables.delta.IGraphDeltaMonitor;
import solver.variables.delta.monitor.SetDeltaMonitor;
import solver.variables.graph.GraphVar;

/**
 * Channeling between a graph variable and set variables
 * representing either node neighbors or node successors
 * @author Jean-Guillaume Fages
 */
public class PropGraphChannel extends Propagator<Variable>{

	//***********************************************************************************
	// VARIABLES
	//***********************************************************************************

	private int n, currentSet;
	private SetDeltaMonitor[] sdm;
	private SetVar[] sets;
	private IGraphDeltaMonitor gdm;
	private GraphVar g;
	private IntProcedure elementForced,elementRemoved;
	private PairProcedure arcForced,arcRemoved;

	//***********************************************************************************
	// CONSTRUCTORS
	//***********************************************************************************

	/**
	 * Channeling between a graph variable and set variables
	 * representing either node neighbors or node successors
	 * @param setsV
	 * @param gV
	 * @param solver
	 * @param c
	 */
	public PropGraphChannel(SetVar[] setsV, GraphVar gV, Solver solver, Constraint c) {
		super(ArrayUtils.append(setsV,new Variable[]{gV}), solver, c, PropagatorPriority.LINEAR);
		this.sets = setsV;
		this.g = gV;
		n = sets.length;
		assert (n==g.getEnvelopGraph().getNbNodes());
		sdm = new SetDeltaMonitor[n];
		for(int i=0;i<n;i++){
			sdm[i] = sets[i].monitorDelta(this);
		}
		gdm = g.monitorDelta(this);
		elementForced = new IntProcedure() {
			@Override
			public void execute(int element) throws ContradictionException {
				g.enforceArc(currentSet,element,aCause);
			}
		};
		elementRemoved = new IntProcedure() {
			@Override
			public void execute(int element) throws ContradictionException {
				g.removeArc(currentSet,element,aCause);
			}
		};
		arcForced = new PairProcedure() {
			@Override
			public void execute(int i, int j) throws ContradictionException {
				sets[i].addToKernel(j,aCause);
			}
		};
		arcRemoved = new PairProcedure() {
			@Override
			public void execute(int i, int j) throws ContradictionException {
				sets[i].removeFromEnvelope(j,aCause);
			}
		};
	}

	//***********************************************************************************
	// METHODS
	//***********************************************************************************

	@Override
	public int getPropagationConditions(int vIdx) {
		if(vIdx<n){
			return EventType.ADD_TO_KER.mask+EventType.REMOVE_FROM_ENVELOPE.mask;
		}else{
			return EventType.ENFORCEARC.mask+EventType.REMOVEARC.mask;
		}
	}

	@Override
	public void propagate(int evtmask) throws ContradictionException{
		for(int i=0;i<n;i++){
			ISet tmp = sets[i].getKernel();
			for(int j=tmp.getFirstElement();j>=0;j=tmp.getNextElement()){
				g.enforceArc(i,j,aCause);
			}
			tmp = g.getKernelGraph().getSuccessorsOf(i);
			for(int j=tmp.getFirstElement();j>=0;j=tmp.getNextElement()){
				sets[i].addToKernel(j,aCause);
			}
			tmp = sets[i].getEnvelope();
			for(int j=tmp.getFirstElement();j>=0;j=tmp.getNextElement()){
				if(!g.getEnvelopGraph().arcExists(i,j)){
					sets[i].removeFromEnvelope(j,aCause);
				}
			}
			tmp = g.getEnvelopGraph().getSuccessorsOf(i);
			for(int j=tmp.getFirstElement();j>=0;j=tmp.getNextElement()){
				if(!sets[i].getEnvelope().contain(j)){
					g.removeArc(i,j,aCause);
				}
			}
		}
		for(int i=0;i<n;i++){
			sdm[i].unfreeze();
		}
		gdm.unfreeze();
	}

	@Override
	public void propagate(int idxVarInProp, int mask) throws ContradictionException {
		if(idxVarInProp==n){
			gdm.freeze();
			gdm.forEachArc(arcForced,EventType.ENFORCEARC);
			gdm.forEachArc(arcRemoved,EventType.REMOVEARC);
			gdm.unfreeze();
		}else{
			currentSet = idxVarInProp;
			sdm[currentSet].freeze();
			sdm[currentSet].forEach(elementForced, EventType.ADD_TO_KER);
			sdm[currentSet].forEach(elementRemoved,EventType.REMOVE_FROM_ENVELOPE);
			sdm[currentSet].unfreeze();
		}
	}

	@Override
	public ESat isEntailed() {
		for(int i=0;i<n;i++){
			ISet tmp = sets[i].getKernel();
			for(int j=tmp.getFirstElement();j>=0;j=tmp.getNextElement()){
				if(!g.getEnvelopGraph().arcExists(i,j)){
					return ESat.FALSE;
				}
			}
			tmp = g.getKernelGraph().getSuccessorsOf(i);
			for(int j=tmp.getFirstElement();j>=0;j=tmp.getNextElement()){
				if(!sets[i].getEnvelope().contain(j)){
					return ESat.FALSE;
				}
			}
		}
		if(isCompletelyInstantiated()){
			return ESat.TRUE;
		}
		return ESat.UNDEFINED;
	}
}
