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

package pascal.taie.analysis.pta.cs;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import pascal.taie.World;
import pascal.taie.analysis.graph.callgraph.CallGraphs;
import pascal.taie.analysis.graph.callgraph.CallKind;
import pascal.taie.analysis.graph.callgraph.Edge;
import pascal.taie.analysis.pta.PointerAnalysisResult;
import pascal.taie.analysis.pta.PointerAnalysisResultImpl;
import pascal.taie.analysis.pta.core.cs.CSCallGraph;
import pascal.taie.analysis.pta.core.cs.context.Context;
import pascal.taie.analysis.pta.core.cs.element.CSCallSite;
import pascal.taie.analysis.pta.core.cs.element.CSManager;
import pascal.taie.analysis.pta.core.cs.element.CSMethod;
import pascal.taie.analysis.pta.core.cs.element.CSObj;
import pascal.taie.analysis.pta.core.cs.element.CSVar;
import pascal.taie.analysis.pta.core.cs.element.MapBasedCSManager;
import pascal.taie.analysis.pta.core.cs.element.Pointer;
import pascal.taie.analysis.pta.core.cs.element.StaticField;
import pascal.taie.analysis.pta.core.cs.selector.ContextSelector;
import pascal.taie.analysis.pta.core.heap.HeapModel;
import pascal.taie.analysis.pta.core.heap.Obj;
import pascal.taie.analysis.pta.pts.PointsToSet;
import pascal.taie.analysis.pta.pts.PointsToSetFactory;
import pascal.taie.config.AnalysisOptions;
import pascal.taie.ir.exp.Var;
import pascal.taie.ir.stmt.Copy;
import pascal.taie.ir.stmt.Invoke;
import pascal.taie.ir.stmt.LoadArray;
import pascal.taie.ir.stmt.LoadField;
import pascal.taie.ir.stmt.New;
import pascal.taie.ir.stmt.StmtVisitor;
import pascal.taie.ir.stmt.StoreArray;
import pascal.taie.ir.stmt.StoreField;
import pascal.taie.language.classes.JMethod;
import pascal.taie.language.type.Type;

class Solver {

    private static final Logger logger = LogManager.getLogger(Solver.class);

    private final AnalysisOptions options;

    private final HeapModel heapModel;

    private final ContextSelector contextSelector;

    private CSManager csManager;

    private CSCallGraph callGraph;

    private PointerFlowGraph pointerFlowGraph;

    private WorkList workList;

    private PointerAnalysisResult result;

    Solver(AnalysisOptions options, HeapModel heapModel,
           ContextSelector contextSelector) {
        this.options = options;
        this.heapModel = heapModel;
        this.contextSelector = contextSelector;
    }

    void solve() {
        initialize();
        analyze();
    }

    private void initialize() {
        csManager = new MapBasedCSManager();
        callGraph = new CSCallGraph(csManager);
        pointerFlowGraph = new PointerFlowGraph();
        workList = new WorkList();
        // process program entry, i.e., main method
        Context defContext = contextSelector.getEmptyContext();
        JMethod main = World.get().getMainMethod();
        CSMethod csMethod = csManager.getCSMethod(defContext, main);
        callGraph.addEntryMethod(csMethod);
        addReachable(csMethod);
    }

    /**
     * Processes new reachable context-sensitive method.
     */
    private void addReachable(CSMethod csMethod) {
        if (callGraph.addReachableMethod(csMethod)) {
            StmtProcessor proc = new StmtProcessor(csMethod);
            csMethod.getMethod().getIR().getStmts().forEach(s -> s.accept(proc));
        }
    }

    /**
     * Processes the statements in context-sensitive new reachable methods.
     */
    private class StmtProcessor implements StmtVisitor<Void> {

        private final CSMethod csMethod;

        private final Context context;

        private StmtProcessor(CSMethod csMethod) {
            this.csMethod = csMethod;
            this.context = csMethod.getContext();
        }

        @Override
        public Void visit(New stmt) {
            Obj obj = heapModel.getObj(stmt);
            CSObj csObj = csManager.getCSObj(contextSelector.selectHeapContext(csMethod, obj), obj);
            CSVar src = csManager.getCSVar(context, stmt.getLValue());
            workList.addEntry(src, PointsToSetFactory.make(csObj));
            return null;
        }

        @Override
        public Void visit(Copy stmt) { // x = y
            CSVar src = csManager.getCSVar(context, stmt.getRValue());
            CSVar target = csManager.getCSVar(context, stmt.getLValue());
            addPFGEdge(src, target);
            return null;
        }

        @Override
        public Void visit(LoadField stmt) {
            if (!stmt.isStatic()) return null;
            CSVar csY = csManager.getCSVar(context, stmt.getLValue());
            StaticField src = csManager.getStaticField(stmt.getFieldRef().resolve());
            addPFGEdge(src, csY);
            return null;
        }

        @Override
        public Void visit(StoreField stmt) {
            if (!stmt.isStatic()) return null;
            CSVar csY = csManager.getCSVar(context, stmt.getRValue());
            StaticField target = csManager.getStaticField(stmt.getFieldRef().resolve());
            addPFGEdge(csY, target);
            return null;
        }

        @Override
        public Void visit(Invoke stmt) {
            if (!stmt.isStatic()) return null;
            JMethod callee = stmt.getMethodRef().resolve();
            CSCallSite csCallSite = csManager.getCSCallSite(context, stmt);
            Context ctx = contextSelector.selectContext(csCallSite, callee);
            CSMethod csMethod = csManager.getCSMethod(ctx, callee);

            if (!callGraph.addEdge(new Edge<>(CallKind.STATIC, csCallSite, csMethod))) return null;
            addReachable(csMethod);

            for (int i = 0; i < stmt.getInvokeExp().getArgCount(); ++i) {
                addPFGEdge(csManager.getCSVar(context, stmt.getInvokeExp().getArg(i)), csManager.getCSVar(ctx, callee.getIR().getParam(i)));
            }

            Var res = stmt.getResult();
            if (res == null) return null;

            for (Var retvar : callee.getIR().getReturnVars()) {
                addPFGEdge(csManager.getCSVar(ctx, retvar), csManager.getCSVar(context, res));
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
            if (ptn instanceof CSVar csVar) {
                Var v = csVar.getVar();
                for (CSObj o : delta) {
                    for (StoreField sf : v.getStoreFields()) {
                        addPFGEdge(csManager.getCSVar(csVar.getContext(), sf.getRValue()), csManager.getInstanceField(o, sf.getFieldRef().resolve()));
                    }
                    for (LoadField lf : v.getLoadFields()) {
                        addPFGEdge(csManager.getInstanceField(o, lf.getFieldRef().resolve()), csManager.getCSVar(csVar.getContext(), lf.getLValue()));
                    }

                    for (StoreArray sa : v.getStoreArrays()) {
                        addPFGEdge(csManager.getCSVar(csVar.getContext(), sa.getRValue()), csManager.getArrayIndex(o));
                    }
                    for (LoadArray la : v.getLoadArrays()) {
                        addPFGEdge(csManager.getArrayIndex(o), csManager.getCSVar(csVar.getContext(), la.getLValue()));
                    }
                    processCall(csVar, o);
                }
            }
        }
    }

    /**
     * Propagates pointsToSet to pt(pointer) and its PFG successors,
     * returns the difference set of pointsToSet and pt(pointer).
     */
    private PointsToSet propagate(Pointer pointer, PointsToSet pointsToSet) {
        PointsToSet diff = PointsToSetFactory.make();
        pointsToSet.objects().filter(obj -> !pointer.getPointsToSet().contains(obj)).forEach(diff::addObject);
        if (!diff.isEmpty()) {
            diff.forEach(o -> pointer.getPointsToSet().addObject(o));
            pointerFlowGraph.getSuccsOf(pointer).forEach(succ -> workList.addEntry(succ, diff));
        }
        return diff;
    }

    /**
     * Processes instance calls when points-to set of the receiver variable changes.
     *
     * @param recv    the receiver variable
     * @param recvObj set of new discovered objects pointed by the variable.
     */
    private void processCall(CSVar recv, CSObj recvObj) {
        for (Invoke callSite : recv.getVar().getInvokes()) {
            JMethod callee = resolveCallee(recvObj, callSite);
            CSCallSite csCallSite = csManager.getCSCallSite(recv.getContext(), callSite);
            Context ctx = contextSelector.selectContext(csCallSite, recvObj, callee);
            CSMethod csMethod = csManager.getCSMethod(ctx, callee);
            Pointer ths = csManager.getCSVar(ctx, callee.getIR().getThis());

            workList.addEntry(ths, PointsToSetFactory.make(recvObj));

            if (!callGraph.addEdge(new Edge<>(CallGraphs.getCallKind(callSite), csCallSite, csMethod))) continue;
            addReachable(csMethod);

            for (int i = 0; i < callSite.getInvokeExp().getArgCount(); ++i) {
                addPFGEdge(csManager.getCSVar(recv.getContext(), callSite.getInvokeExp().getArg(i)), csManager.getCSVar(ctx, callee.getIR().getParam(i)));
            }

            Var res = callSite.getResult();
            if (res == null) continue;

            for (Var retvar : callee.getIR().getReturnVars()) {
                addPFGEdge(csManager.getCSVar(ctx, retvar), csManager.getCSVar(recv.getContext(), res));
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
    private JMethod resolveCallee(CSObj recv, Invoke callSite) {
        Type type = recv != null ? recv.getObject().getType() : null;
        return CallGraphs.resolveCallee(type, callSite);
    }

    PointerAnalysisResult getResult() {
        if (result == null) {
            result = new PointerAnalysisResultImpl(csManager, callGraph);
        }
        return result;
    }
}
