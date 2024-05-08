import java
import libs.DangerousMethods
import libs.Source

private class DangerousExpression extends Expr {
  DangerousExpression() {
    this instanceof Call and this.(Call).getCallee() instanceof DangerousMethod
    or
    this instanceof LambdaExpr and
    this.(LambdaExpr).getExprBody().(MethodCall).getMethod() instanceof DangerousMethod
  }
}

private class CallsDangerousMethod extends Callable {
  CallsDangerousMethod() { exists(DangerousExpression de | de.getEnclosingCallable() = this) }
}

private class RecursiveCallToDangerousMethod extends Callable {
  RecursiveCallToDangerousMethod() {
    // not this instanceof Sanitizer and
    /*
     *      /* can be commented for more results
     */

    (
      getDeclaringType().getASupertype*() instanceof TypeSerializable or
      this.isStatic()
    ) and
    (
      this instanceof CallsDangerousMethod or
      exists(RecursiveCallToDangerousMethod unsafe | this.polyCalls(unsafe))
    )
  }

  /*
   *      /* linking a RecursiveCallToDangerousMethod to a DangerousExpression
   */

  DangerousExpression getDangerousExpression() {
    exists(DangerousExpression de | de.getEnclosingCallable() = this and result = de)
    or
    exists(RecursiveCallToDangerousMethod unsafe |
      this.polyCalls(unsafe) and
      result = unsafe.(RecursiveCallToDangerousMethod).getDangerousExpression()
    )
  }
}

// private class Sanitizer extends Callable {
//   Sanitizer() { hasName([""]) }
// }

query predicate edges(ControlFlowNode node1, ControlFlowNode node2) {
    (node1.(MethodCall).getMethod().getAPossibleImplementation() = node2 and node2 instanceof RecursiveCallToDangerousMethod) or 
    (node2.(MethodCall).getEnclosingCallable() = node1 and node1 instanceof RecursiveCallToDangerousMethod)
}

predicate hasCalls(RecursiveCallToDangerousMethod c0, RecursiveCallToDangerousMethod c1) {
    c0.polyCalls(c1) or exists(RecursiveCallToDangerousMethod unsafe | c0.polyCalls(unsafe) and hasCalls(unsafe, c1))
}


from RecursiveCallToDangerousMethod c0,  RecursiveCallToDangerousMethod c1, DangerousExpression de
where de.getEnclosingCallable() = c1 and
c0 instanceof Source and
hasCalls(c0, c1)
select c0, c1, "recursive call to dangerous expression $@", de, de.toString()
