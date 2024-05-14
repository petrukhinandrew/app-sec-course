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

package pascal.taie.analysis.dataflow.analysis.constprop;

import pascal.taie.analysis.dataflow.analysis.AbstractDataflowAnalysis;
import pascal.taie.analysis.graph.cfg.CFG;
import pascal.taie.config.AnalysisConfig;
import pascal.taie.ir.IR;
import pascal.taie.ir.exp.ArithmeticExp;
import pascal.taie.ir.exp.BinaryExp;
import pascal.taie.ir.exp.BitwiseExp;
import pascal.taie.ir.exp.ConditionExp;
import pascal.taie.ir.exp.Exp;
import pascal.taie.ir.exp.IntLiteral;
import pascal.taie.ir.exp.ShiftExp;
import pascal.taie.ir.exp.Var;
import pascal.taie.ir.stmt.DefinitionStmt;
import pascal.taie.ir.stmt.Stmt;
import pascal.taie.language.type.PrimitiveType;
import pascal.taie.language.type.Type;
import pascal.taie.util.AnalysisException;

import java.util.Map;

public class ConstantPropagation extends
        AbstractDataflowAnalysis<Stmt, CPFact> {

    public static final String ID = "constprop";

    public ConstantPropagation(AnalysisConfig config) {
        super(config);
    }

    @Override
    public boolean isForward() {
        return true;
    }

    @Override
    public CPFact newBoundaryFact(CFG<Stmt> cfg) {
        CPFact newFact = new CPFact();
        for (Var v : cfg.getIR().getParams()) {
            if (canHoldInt(v)) {
                newFact.update(v, Value.getNAC());
            }
        }
        return newFact;
    }

    @Override
    public CPFact newInitialFact() {
        return new CPFact();
    }

    @Override
    public void meetInto(CPFact fact, CPFact target) {
        fact.forEach((var, val) -> {
            target.update(var, meetValue(val, target.get(var)));
        });
    }

    /**
     * Meets two Values.
     */
    public Value meetValue(Value v1, Value v2) {
        if (v1.isNAC() || v2.isNAC()) {
            return Value.getNAC();
        }
        if (v1.isConstant() && v2.isConstant()) {
            if (v1.getConstant() == v2.getConstant()) {
                return Value.makeConstant(v1.getConstant());
            } else {
                return Value.getNAC();
            }
        }
        if (v1.isUndef() && v2.isConstant()) {
            return Value.makeConstant(v2.getConstant());
        }
        if (v2.isUndef() && v1.isConstant()) {
            return Value.makeConstant(v1.getConstant());
        }
        return Value.getUndef();
    }

    @Override
    public boolean transferNode(Stmt stmt, CPFact in, CPFact out) {
        CPFact inCopy = in.copy();
        if (stmt instanceof DefinitionStmt<?, ?> def) {
            if (def.getLValue() instanceof Var lvar && canHoldInt(lvar)) {
                inCopy.update(lvar, evaluate(def.getRValue(), in));
                return out.copyFrom(inCopy);
            }
        }
        return out.copyFrom(in);
    }


    /**
     * @return true if the given variable can hold integer value, otherwise false.
     */
    public static boolean canHoldInt(Var var) {
        Type type = var.getType();
        if (type instanceof PrimitiveType) {
            switch ((PrimitiveType) type) {
                case BYTE:
                case SHORT:
                case INT:
                case CHAR:
                case BOOLEAN:
                    return true;
            }
        }
        return false;
    }

    /**
     * Evaluates the {@link Value} of given expression.
     *
     * @param exp the expression to be evaluated
     * @param in  IN fact of the statement
     * @return the resulting {@link Value}
     */
    public static Value evaluate(Exp exp, CPFact in) {
        if (exp instanceof IntLiteral lit) {
            return Value.makeConstant(lit.getValue());
        }
        if (exp instanceof Var v) {
            return in.get(v).isConstant() ? Value.makeConstant(in.get(v).getConstant()) : in.get(v);
        }
        if (exp instanceof BinaryExp bexp) {
            Value lhs = evaluate(bexp.getOperand1(), in);
            Value rhs = evaluate(bexp.getOperand2(), in);
            if (zeroDivMod(bexp.getOperator(), rhs)) {
                return Value.getUndef();
            }
            if (lhs.isConstant() && rhs.isConstant()) {
                int lc = lhs.getConstant();
                int rc = rhs.getConstant();
                if (bexp.getOperator() instanceof ArithmeticExp.Op op) {
                    return evalArithm(op, lc, rc);
                }
                if (bexp.getOperator() instanceof ShiftExp.Op op) {
                    return evalShift(op, lc, rc);
                }
                if (bexp.getOperator() instanceof BitwiseExp.Op op) {
                    return evalBitw(op, lc, rc);
                }
                if (bexp.getOperator() instanceof ConditionExp.Op op) {
                    return evalCond(op, lc, rc);
                }
            }
            if (lhs.isNAC() || rhs.isNAC()) {
                return Value.getNAC();
            }
            return Value.getUndef();
        }
        return Value.getNAC();
    }

    private static Value evalArithm(ArithmeticExp.Op op, int c1, int c2) {
        return Value.makeConstant(switch (op) {
            case ADD -> c1 + c2;
            case SUB -> c1 - c2;
            case MUL -> c1 * c2;
            case REM -> c1 % c2;
            case DIV -> c1 / c2;
        });
    }

    private static Value evalShift(ShiftExp.Op op, int c1, int c2) {
        return Value.makeConstant(switch (op) {
            case SHL -> c1 << c2;
            case SHR -> c1 >> c2;
            case USHR -> c1 >>> c2;
        });
    }

    private static Value evalBitw(BitwiseExp.Op op, int c1, int c2) {
        return Value.makeConstant(switch (op) {
            case OR -> c1 | c2;
            case AND -> c1 & c2;
            case XOR -> c1 ^ c2;
        });
    }

    private static Value evalCond(ConditionExp.Op op, int c1, int c2) {
        return Value.makeConstant(switch (op) {
            case EQ -> c1 == c2 ? 1 : 0;
            case NE -> c1 != c2 ? 1 : 0;
            case LT -> c1 < c2 ? 1 : 0;
            case GT -> c1 > c2 ? 1 : 0;
            case LE -> c1 <= c2 ? 1 : 0;
            case GE -> c1 >= c2 ? 1 : 0;
        });
    }

    private static boolean zeroDivMod(BinaryExp.Op exp, Value v) {
        if (exp instanceof ArithmeticExp.Op op) {
            if (op == ArithmeticExp.Op.DIV || op == ArithmeticExp.Op.REM) {
                return zeroConst(v);
            }
            return false;
        }
        return false;
    }

    private static boolean zeroConst(Value v) {
        return v.isConstant() && v.getConstant() == 0;
    }
}
