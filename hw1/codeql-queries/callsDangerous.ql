import java
import semmle.code.java.dataflow.DataFlow

class StartCall extends Method {
  StartCall() {
    getDeclaringType().isPublic() and
    isPublic()
  }
}

class TargetCall extends Method {
  TargetCall() {
    hasName("getBZip2PipedInputStream") 
    or hasName("getBZip2PipedOutputStream")
  }
}

predicate hasCalls(Method c0, Method c1) {
  c0.polyCalls(c1) or exists(Method unsafe | c0.polyCalls(unsafe) and hasCalls(unsafe, c1))
}

from StartCall c0,  TargetCall c1 
where hasCalls(c0, c1)
select c0, c1, "recursive call to dangerous expression $@"
