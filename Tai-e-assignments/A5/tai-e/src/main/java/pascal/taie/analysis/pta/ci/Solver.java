/*
 * Tai-e: A Static Analysis Framework for Java
 *
 * Copyright (C) 2022 Tian Tan <tiantan@nju.edu.cn>
 * Copyright (C) 2022 Yue Li <yueli@nju.edu.cn>
 *
 * This file is part of Tai-e.
 *
 * Tai-e is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License
 * as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * Tai-e is distributed in the hope that it will be useful,but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser General
 * Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with Tai-e. If not, see <https://www.gnu.org/licenses/>.
 */

package pascal.taie.analysis.pta.ci;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import pascal.taie.World;
import pascal.taie.analysis.graph.callgraph.CallGraphs;
import pascal.taie.analysis.graph.callgraph.CallKind;
import pascal.taie.analysis.graph.callgraph.DefaultCallGraph;
import pascal.taie.analysis.graph.callgraph.Edge;
import pascal.taie.analysis.pta.core.heap.HeapModel;
import pascal.taie.analysis.pta.core.heap.Obj;
import pascal.taie.ir.exp.InvokeExp;
import pascal.taie.ir.exp.Var;
import pascal.taie.ir.proginfo.MethodRef;
import pascal.taie.ir.stmt.*;
import pascal.taie.language.classes.ClassHierarchy;
import pascal.taie.language.classes.JField;
import pascal.taie.language.classes.JMethod;
import pascal.taie.util.AnalysisException;
import pascal.taie.language.type.Type;

import java.util.List;

class Solver {

    private static final Logger logger = LogManager.getLogger(Solver.class);

    private final HeapModel heapModel;

    private DefaultCallGraph callGraph;

    private PointerFlowGraph pointerFlowGraph;

    private WorkList workList;

    private StmtProcessor stmtProcessor;

    private ClassHierarchy hierarchy;

    Solver(HeapModel heapModel) {
        this.heapModel = heapModel;
    }

    /**
     * Runs pointer analysis algorithm.
     */
    void solve() {
        initialize();
        analyze();
    }

    /**
     * Initializes pointer analysis.
     */
    private void initialize() {
        workList = new WorkList();
        pointerFlowGraph = new PointerFlowGraph();
        callGraph = new DefaultCallGraph();
        stmtProcessor = new StmtProcessor();
        hierarchy = World.get().getClassHierarchy();
        // initialize main method
        JMethod main = World.get().getMainMethod();
        callGraph.addEntryMethod(main);
        addReachable(main);
    }

    /**
     * Processes new reachable method.
     */
    private void addReachable(JMethod method) {
        if (callGraph.addReachableMethod(method)) {
            method.getIR().getStmts().forEach(s -> s.accept(stmtProcessor));
        }
    }

    /**
     * Processes statements in new reachable methods.
     */
    private class StmtProcessor implements StmtVisitor<Void> {
        @Override
        public Void visit(New stmt) {
            Pointer lhs = pointerFlowGraph.getVarPtr(stmt.getLValue());
            PointsToSet target = new PointsToSet(heapModel.getObj(stmt));
            workList.addEntry(lhs, target);
            return null;
        }

        @Override
        public Void visit(Copy stmt) {
            Pointer lhs = pointerFlowGraph.getVarPtr(stmt.getLValue());
            Pointer rhs = pointerFlowGraph.getVarPtr(stmt.getRValue());
            addPFGEdge(rhs, lhs);
            return null;
        }

        @Override
        public Void visit(LoadField stmt) {
            if (!stmt.isStatic()) return null;
            Pointer lhs = pointerFlowGraph.getVarPtr(stmt.getLValue());
            Pointer rhsf = pointerFlowGraph.getStaticField(stmt.getFieldRef().resolve());
            addPFGEdge(rhsf, lhs);
            return null;
        }

        @Override
        public Void visit(StoreField stmt) {
            if (!stmt.isStatic()) return null;
            Pointer rhs = pointerFlowGraph.getVarPtr(stmt.getRValue());
            Pointer lhsf = pointerFlowGraph.getStaticField(stmt.getFieldRef().resolve());
            addPFGEdge(lhsf, rhs);
            return null;
        }

        @Override
        public Void visit(Invoke stmt) {
            if (!stmt.isStatic()) return null;

            JMethod m = stmt.getMethodRef().resolve();
            if (callGraph.addEdge(new Edge<>(CallKind.STATIC, stmt, m))) {
                addReachable(m);

                for (int i = 0; i < stmt.getInvokeExp().getArgCount(); ++i) {
                    addPFGEdge(pointerFlowGraph.getVarPtr(stmt.getInvokeExp().getArg(i)), pointerFlowGraph.getVarPtr(m.getIR().getParam(i)));
                }

                Var res = stmt.getResult();

                if (res == null) return null;
                for (Var retvar : m.getIR().getReturnVars()) {
                    addPFGEdge(pointerFlowGraph.getVarPtr(retvar), pointerFlowGraph.getVarPtr(res));
                }
            }
            return null;
        }
    }

    /**
     * Adds an edge "source -> target" to the PFG.
     */
    private void addPFGEdge(Pointer source, Pointer target) {
        if (pointerFlowGraph.addEdge(source, target)) {
            PointsToSet pts = source.getPointsToSet();
            if (!pts.isEmpty()) {
                workList.addEntry(target, pts);
            }
        }
    }

    /**
     * Processes work-list entries until the work-list is empty.
     */
    private void analyze() {
        while (!workList.isEmpty()) {
            WorkList.Entry e = workList.pollEntry();
            PointsToSet pts = e.pointsToSet();
            Pointer ptn = e.pointer();
            PointsToSet delta = propagate(ptn, pts);
            if (ptn instanceof VarPtr vp) {
                Var v = vp.getVar();
                for (Obj o : delta) {
                    for (StoreField sf : v.getStoreFields()) {
                        addPFGEdge(pointerFlowGraph.getVarPtr(sf.getRValue()), pointerFlowGraph.getStaticField(sf.getFieldRef().resolve()));
                    }
                    for (LoadField lf : v.getLoadFields()) {
                        addPFGEdge(pointerFlowGraph.getStaticField(lf.getFieldRef().resolve()), pointerFlowGraph.getVarPtr(lf.getLValue()));
                    }

                    for (StoreArray sa : v.getStoreArrays()) {
                        addPFGEdge(pointerFlowGraph.getVarPtr(sa.getRValue()), pointerFlowGraph.getArrayIndex(o));
                    }
                    for (LoadArray la : v.getLoadArrays()) {
                        addPFGEdge(pointerFlowGraph.getArrayIndex(o), pointerFlowGraph.getVarPtr(la.getLValue()));
                    }
                    processCall(v, o);
                }
            }
        }
    }

    /**
     * Propagates pointsToSet to pt(pointer) and its PFG successors,
     * returns the difference set of pointsToSet and pt(pointer).
     */
    private PointsToSet propagate(Pointer pointer, PointsToSet pointsToSet) {
        PointsToSet diff = new PointsToSet();
        pointsToSet.objects().filter(obj -> !pointer.getPointsToSet().contains(obj)).forEach(diff::addObject);
        if (diff.isEmpty()) {
            diff.forEach(o -> pointer.getPointsToSet().addObject(o));
            pointerFlowGraph.getSuccsOf(pointer).forEach(succ -> workList.addEntry(succ, diff));
        }
        return diff;
    }

    /**
     * Processes instance calls when points-to set of the receiver variable changes.
     *
     * @param var  the variable that holds receiver objects
     * @param recv a new discovered object pointed by the variable.
     */
    private void processCall(Var var, Obj recv) {
        for (Invoke cs : var.getInvokes()) {
            JMethod callee = resolveCallee(recv, cs);
            workList.addEntry(pointerFlowGraph.getVarPtr(callee.getIR().getThis()), new PointsToSet(recv));
            if (callGraph.addEdge(new Edge<>(CallGraphs.getCallKind(cs), cs, callee))) {
                addReachable(callee);

                for (int i = 0; i < cs.getInvokeExp().getArgCount(); ++i) {
                    addPFGEdge(pointerFlowGraph.getVarPtr(cs.getInvokeExp().getArg(i)), pointerFlowGraph.getVarPtr(callee.getIR().getParam(i)));
                }

                Var res = cs.getResult();

                if (res == null) continue;
                for (Var retvar : callee.getIR().getReturnVars()) {
                    addPFGEdge(pointerFlowGraph.getVarPtr(retvar), pointerFlowGraph.getVarPtr(res));
                }
            }
        }
    }

    /**
     * Resolves the callee of a call site with the receiver object.
     *
     * @param recv     the receiver object of the method call. If the callSite
     *                 is static, this parameter is ignored (i.e., can be null).
     * @param callSite the call site to be resolved.
     * @return the resolved callee.
     */
    private JMethod resolveCallee(Obj recv, Invoke callSite) {
        Type type = recv != null ? recv.getType() : null;
        return CallGraphs.resolveCallee(type, callSite);
    }

    CIPTAResult getResult() {
        return new CIPTAResult(pointerFlowGraph, callGraph);
    }
}
