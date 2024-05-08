import java

class StartCall extends Method {
  StartCall() {
    getDeclaringType().isPublic() and
    isPublic()
  }
}

class TargetCall extends Method {
  TargetCall() {
    hasName("BZip2PipedOutputStream") or
    hasName("getBZip2PipedInputStream")
  }
}

from StartCall enclosing
where exists( TargetCall target | enclosing.polyCalls(target) )
select enclosing.getDeclaringType(), enclosing