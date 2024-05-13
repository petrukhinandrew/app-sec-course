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

package pascal.taie.analysis.dataflow.solver;

import pascal.taie.analysis.dataflow.analysis.DataflowAnalysis;
import pascal.taie.analysis.dataflow.analysis.constprop.CPFact;
import pascal.taie.analysis.dataflow.fact.DataflowResult;
import pascal.taie.analysis.dataflow.fact.SetFact;
import pascal.taie.analysis.graph.cfg.CFG;
import pascal.taie.ir.exp.Var;

import java.util.LinkedList;
import java.util.Queue;

class WorkListSolver<Node, Fact> extends Solver<Node, Fact> {

    WorkListSolver(DataflowAnalysis<Node, Fact> analysis) {
        super(analysis);
    }

    @SuppressWarnings("unchecked")
    @Override
    protected void doSolveForward(CFG<Node> cfg, DataflowResult<Node, Fact> result) {
        Queue<Node> worklist = new LinkedList<>(cfg.getNodes());
        while (!worklist.isEmpty()) {
            Node bb = worklist.poll();
            CPFact out = (CPFact) result.getOutFact(bb);
            CPFact in = new CPFact();
            for (Node n : cfg.getPredsOf(bb)) {
                analysis.meetInto(result.getOutFact(n), (Fact) in);
            }
            if (analysis.transferNode(bb, (Fact) in, (Fact) out)) {
                cfg.getSuccsOf(bb).forEach(worklist::offer);
            }
            result.setInFact(bb, (Fact) in);
            result.setOutFact(bb, (Fact) out);
        }
    }

    @SuppressWarnings("unchecked")
    @Override
    protected void doSolveBackward(CFG<Node> cfg, DataflowResult<Node, Fact> result) {
        boolean go = true;
        while (go) {
            go = false;
            for (Node node : cfg) {
                SetFact<Var> out = new SetFact<>();
                SetFact<Var> in = (SetFact<Var>) result.getInFact(node);
                for (Node succ : cfg.getSuccsOf(node)) {
                    out.union((SetFact<Var>) result.getInFact(succ));
                }
                if (analysis.transferNode(node, (Fact) in, (Fact) out)) {
                    go = true;
                }
                result.setInFact(node, (Fact) in);
                result.setOutFact(node, (Fact) out);
            }
        }
    }
}
