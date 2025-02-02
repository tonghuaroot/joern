package io.joern.dataflowengineoss.dotgenerator

import io.joern.dataflowengineoss.DefaultSemantics
import io.shiftleft.codepropertygraph.generated.nodes.Method
import io.joern.dataflowengineoss.semanticsloader.Semantics
import io.shiftleft.semanticcpg.dotgenerator.DotSerializer
import overflowdb.traversal._

object DotDdgGenerator {

  def toDotDdg(traversal: Traversal[Method])(implicit semantics: Semantics = DefaultSemantics()): Traversal[String] =
    traversal.map(dotGraphForMethod)

  private def dotGraphForMethod(method: Method)(implicit semantics: Semantics): String = {
    val ddgGenerator = new DdgGenerator()
    val ddg          = ddgGenerator.generate(method)
    DotSerializer.dotGraph(Option(method), ddg)
  }

}
