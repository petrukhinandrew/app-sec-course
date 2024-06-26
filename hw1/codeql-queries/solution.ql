import java
import semmle.code.java.dataflow.DataFlow
import semmle.code.java.dataflow.TaintTracking

class StartCall extends MethodCall {
  StartCall() {
    getCallee().getDeclaringType() instanceof TypeSystem and
    (getCallee().hasName("getProperty") or getCallee().hasName("getenv")) and
    getType() instanceof TypeString
  }
}

class TargetCall extends Argument {
  TargetCall() {
    (
      getCall().getCallee().getDeclaringType() instanceof TypeProcessBuilder and
      getCall().getCallee().hasName("command")
      or
      getCall().getCallee().getDeclaringType() instanceof TypeRuntime and
      getCall().getCallee().hasName("exec")
    ) and
    getCall().getEnclosingCallable().isPublic()
  }
}

module MyFlowConfig implements DataFlow::ConfigSig {
  predicate isSource(DataFlow::Node node) { node.asExpr() instanceof StartCall }

  predicate isSink(DataFlow::Node node) { node.asExpr() instanceof TargetCall }
}

module Flow = TaintTracking::Global<MyFlowConfig>;

from Flow::PathNode source, Flow::PathNode sink
where Flow::flowPath(source, sink)
select source, sink, sink.getNode().getEnclosingCallable()
