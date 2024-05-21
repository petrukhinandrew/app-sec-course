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

package pascal.taie.analysis.dataflow.analysis;

import pascal.taie.analysis.MethodAnalysis;
import pascal.taie.analysis.dataflow.analysis.constprop.CPFact;
import pascal.taie.analysis.dataflow.analysis.constprop.ConstantPropagation;
import pascal.taie.analysis.dataflow.analysis.constprop.Value;
import pascal.taie.analysis.dataflow.fact.DataflowResult;
import pascal.taie.analysis.dataflow.fact.SetFact;
import pascal.taie.analysis.graph.cfg.CFG;
import pascal.taie.analysis.graph.cfg.CFGBuilder;
import pascal.taie.analysis.graph.cfg.Edge;
import pascal.taie.config.AnalysisConfig;
import pascal.taie.ir.IR;
import pascal.taie.ir.exp.ArithmeticExp;
import pascal.taie.ir.exp.ArrayAccess;
import pascal.taie.ir.exp.CastExp;
import pascal.taie.ir.exp.FieldAccess;
import pascal.taie.ir.exp.NewExp;
import pascal.taie.ir.exp.RValue;
import pascal.taie.ir.exp.Var;
import pascal.taie.ir.stmt.AssignStmt;
import pascal.taie.ir.stmt.If;
import pascal.taie.ir.stmt.Stmt;
import pascal.taie.ir.stmt.SwitchStmt;
import pascal.taie.util.collection.Pair;

import java.util.*;

public class DeadCodeDetection extends MethodAnalysis {

    public static final String ID = "deadcode";

    public DeadCodeDetection(AnalysisConfig config) {
        super(config);
    }

    @Override
    public Set<Stmt> analyze(IR ir) {
        // obtain CFG
        CFG<Stmt> cfg = ir.getResult(CFGBuilder.ID);
        // obtain result of constant propagation
        DataflowResult<Stmt, CPFact> constants = ir.getResult(ConstantPropagation.ID);
        // obtain result of live variable analysis
        DataflowResult<Stmt, SetFact<Var>> liveVars = ir.getResult(LiveVariableAnalysis.ID);
        // keep statements (dead code) sorted in the resulting set
        Set<Stmt> deadCode = new TreeSet<>(Comparator.comparing(Stmt::getIndex));
        Set<Stmt> liveCode = new TreeSet<>(Comparator.comparing(Stmt::getIndex));
        Queue<Stmt> q = new LinkedList<>();
        q.add(cfg.getEntry());
        while (!q.isEmpty()) {
            Stmt stmt = q.poll();
            if (stmt instanceof AssignStmt<?, ?> assn) {
                q.addAll(cfg.getSuccsOf(assn));
                if (analyzeAssign(assn, liveVars)) {
                    continue;
                }
            }
            if (!liveCode.add(stmt)) continue;
            if (stmt instanceof If condSt) {
                analyzeIf(condSt, q, cfg, constants);
            } else if (stmt instanceof SwitchStmt switchSt) {
                analyzeSwitch(switchSt, q, cfg, constants);
            } else {
                q.addAll(cfg.getSuccsOf(stmt));
            }
        }
        deadCode.addAll(cfg.getNodes());
        deadCode.removeAll(liveCode);
        deadCode.remove(cfg.getExit());
        return deadCode;
    }

    /**
     * @return true if it's dead assignment
     */
    private static boolean analyzeAssign(AssignStmt<?, ?> assn, DataflowResult<Stmt, SetFact<Var>> liveVars) {
        boolean res = false;
        if (assn.getLValue() instanceof Var v) {
            if (!liveVars.getOutFact(assn).contains(v) && hasNoSideEffect(assn.getRValue())) {
                res = true;
            }
        }
        return res;
    }

    private static void analyzeIf(If stmt, Queue<Stmt> q, CFG<Stmt> cfg, DataflowResult<Stmt, CPFact> constants) {
        Value cond = ConstantPropagation.evaluate(stmt.getCondition(), constants.getInFact(stmt));
        if (!cond.isConstant()) {
            q.addAll(cfg.getSuccsOf(stmt));
            return;
        }
        for (Edge<Stmt> edge : cfg.getOutEdgesOf(stmt)) {
            if ((cond.getConstant() == 1 && edge.getKind() == Edge.Kind.IF_TRUE) || (cond.getConstant() == 0 && edge.getKind() == Edge.Kind.IF_FALSE)) {
                q.add(edge.getTarget());
            }
        }
    }

    private static void analyzeSwitch(SwitchStmt switchSt, Queue<Stmt> q, CFG<Stmt> cfg, DataflowResult<Stmt, CPFact> constants) {
        Value val = ConstantPropagation.evaluate(switchSt.getVar(), constants.getInFact(switchSt));
        if (!val.isConstant()) {
            q.addAll(cfg.getSuccsOf(switchSt));
            return;
        }
        boolean nonDefaultReachable = false;
        for (Pair<Integer, Stmt> pair : switchSt.getCaseTargets()) {
            Integer branch = pair.first();
            Stmt target = pair.second();
            if (branch == val.getConstant()) {
                nonDefaultReachable = true;
                q.add(target);
            }
        }
        if (!nonDefaultReachable) {
            q.add(switchSt.getDefaultTarget());
        }
    }

    /**
     * @return true if given RValue has no side effect, otherwise false.
     */
    private static boolean hasNoSideEffect(RValue rvalue) {
        // new expression modifies the heap
        if (rvalue instanceof NewExp ||
                // cast may trigger ClassCastException
                rvalue instanceof CastExp ||
                // static field access may trigger class initialization
                // instance field access may trigger NPE
                rvalue instanceof FieldAccess ||
                // array access may trigger NPE
                rvalue instanceof ArrayAccess) {
            return false;
        }
        if (rvalue instanceof ArithmeticExp) {
            ArithmeticExp.Op op = ((ArithmeticExp) rvalue).getOperator();
            // may trigger DivideByZeroException
            return op != ArithmeticExp.Op.DIV && op != ArithmeticExp.Op.REM;
        }
        return true;
    }
}
