/**
 * @id synacktiv/java/qlinspectorself
 * @description find Java gadget chains
 * @name QLInspectorSelf
 * @kind problem
 * @precision high
 * 
 */

import java
import libs.DangerousMethods
import libs.Source

// I dont' know how to merge this with QLinspector.ql
// find case where source == sink 
// like JDBC DataSource.getConnection()

from Callable c0,  Callable c1
where c0 instanceof Source and 
c0 instanceof DangerousMethod and
c0 = c1
select c0, "source == sink"