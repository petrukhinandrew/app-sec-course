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

package pascal.taie.analysis.graph.callgraph;

import pascal.taie.World;
import pascal.taie.ir.proginfo.MethodRef;
import pascal.taie.ir.stmt.Invoke;
import pascal.taie.language.classes.ClassHierarchy;
import pascal.taie.language.classes.JClass;
import pascal.taie.language.classes.JMethod;
import pascal.taie.language.classes.Subsignature;
import soot.jimple.parser.node.PCaseLabel;

import java.lang.invoke.CallSite;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Queue;
import java.util.Set;

/**
 * Implementation of the CHA algorithm.
 */
class CHABuilder implements CGBuilder<Invoke, JMethod> {

    private ClassHierarchy hierarchy;

    @Override
    public CallGraph<Invoke, JMethod> build() {
        hierarchy = World.get().getClassHierarchy();
        return buildCallGraph(World.get().getMainMethod());
    }

    private static CallKind resolveCallKind(Invoke cs) {
        if (cs.isVirtual()) {
            return CallKind.VIRTUAL;
        }
        if (cs.isStatic()) {
            return CallKind.STATIC;
        }
        if (cs.isInterface()) {
            return CallKind.INTERFACE;
        }
        if (cs.isSpecial()) {
            return CallKind.SPECIAL;
        }
        if (cs.isDynamic()) {
            return CallKind.DYNAMIC;
        }
        return null;
    }

    private CallGraph<Invoke, JMethod> buildCallGraph(JMethod entry) {
        DefaultCallGraph callGraph = new DefaultCallGraph();
        callGraph.addEntryMethod(entry);
        Queue<JMethod> wl = new LinkedList<>();
        wl.add(entry);
        while (!wl.isEmpty()) {
            JMethod cur = wl.poll();
            if (!callGraph.addReachableMethod(cur)) continue;
            callGraph.getCallSitesIn(cur).forEach(cs ->
                    resolve(cs).forEach(m -> {
                        callGraph.addEdge(new Edge<>(resolveCallKind(cs), cs, m));
                        wl.add(m);
                    })
            );
        }
        return callGraph;
    }

    /**
     * Resolves call targets (callees) of a call site via CHA.
     */
    private Set<JMethod> resolve(Invoke callSite) {
        Set<JMethod> T = new HashSet<>();
        MethodRef m = callSite.getMethodRef();
        if (callSite.isStatic()) {
            T.add(m.getDeclaringClass().getDeclaredMethod(m.getSubsignature()));
            return T;
        }
        if (callSite.isSpecial()) {
            T.add(dispatch(m.getDeclaringClass(), m.getSubsignature()));
            return T;
        }
        if (callSite.isVirtual() || callSite.isInterface()) {
            Queue<JClass> q = new LinkedList<>();
            JClass c = m.getDeclaringClass();
            q.add(c);
            while (!q.isEmpty()) {
                JClass cur = q.poll();
                JMethod dm = dispatch(cur, m.getSubsignature());
                if (dm != null) {
                    T.add(dm);
                }
                if (cur.isInterface()) {
                    q.addAll(hierarchy.getDirectSubinterfacesOf(cur));
                    q.addAll(hierarchy.getDirectImplementorsOf(cur));
                } else {
                    q.addAll(hierarchy.getDirectSubclassesOf(cur));
                }
            }
        }
        return T;
    }

    /**
     * Looks up the target method based on given class and method subsignature.
     *
     * @return the dispatched target method, or null if no satisfying method
     * can be found.
     */
    private JMethod dispatch(JClass jclass, Subsignature subsignature) {
        JMethod mbMethod = jclass.getDeclaredMethod(subsignature);
        if (mbMethod != null && !mbMethod.isAbstract()) {
            return mbMethod;
        }
        JClass mbSuper = jclass.getSuperClass();
        if (mbSuper == null) return null;
        return dispatch(mbSuper, subsignature);
    }
}
