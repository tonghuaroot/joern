package io.joern.joerncli

import flatgraph.{Accessors, Edge, GNode}
import flatgraph.formats.ExportResult
import flatgraph.formats.dot.DotExporter
import flatgraph.formats.graphml.GraphMLExporter
import flatgraph.formats.graphson.GraphSONExporter
import flatgraph.formats.neo4jcsv.Neo4jCsvExporter
import io.joern.dataflowengineoss.DefaultSemantics
import io.joern.dataflowengineoss.layers.dataflows.*
import io.joern.dataflowengineoss.semanticsloader.{NoSemantics, Semantics}
import io.joern.joerncli.CpgBasedTool.exitIfInvalid
import io.joern.x2cpg.layers.*
import io.shiftleft.codepropertygraph.generated.Cpg
import io.shiftleft.codepropertygraph.generated.NodeTypes
import io.shiftleft.semanticcpg.language.*
import io.shiftleft.semanticcpg.layers.*

import java.nio.file.{Files, Path, Paths}
import scala.collection.mutable
import scala.util.Using

object JoernExport {

  case class Config(
    cpgFileName: String = "cpg.bin",
    outDir: String = "out",
    repr: Representation.Value = Representation.Cpg14,
    format: Format.Value = Format.Dot
  )

  /** Choose from either a subset of the graph, or the entire graph (all).
    */
  object Representation extends Enumeration {
    val Ast, Cfg, Ddg, Cdg, Pdg, Cpg14, Cpg, All = Value

    lazy val byNameLowercase: Map[String, Value] =
      values.map { value =>
        value.toString.toLowerCase -> value
      }.toMap

    def withNameIgnoreCase(s: String): Value =
      byNameLowercase.getOrElse(s, throw new NoSuchElementException(s"No value found for '$s'"))

  }
  object Format extends Enumeration {
    val Dot, Neo4jCsv, Graphml, Graphson = Value

    lazy val byNameLowercase: Map[String, Value] =
      values.map { value =>
        value.toString.toLowerCase -> value
      }.toMap

    def withNameIgnoreCase(s: String): Value =
      byNameLowercase.getOrElse(s, throw new NoSuchElementException(s"No value found for '$s'"))
  }

  def main(args: Array[String]): Unit = {
    parseConfig(args).foreach { config =>
      val outDir = config.outDir
      exitIfInvalid(outDir, config.cpgFileName)
      val outDirPath = Paths.get(outDir)
      Files.createDirectory(outDirPath)

      Using.resource(CpgBasedTool.loadFromFile(config.cpgFileName)) { cpg =>
        exportCpg(cpg, config.repr, config.format, outDirPath.toAbsolutePath)
      }
    }
  }

  private def parseConfig(args: Array[String]): Option[Config] = {
    new scopt.OptionParser[Config]("joern-export") {
      head("Dump intermediate graph representations (or entire graph) of code in a given export format")
      help("help")
      arg[String]("cpg")
        .text("input CPG file name - defaults to `cpg.bin`")
        .optional()
        .action((x, c) => c.copy(cpgFileName = x))
      opt[String]('o', "out")
        .text("output directory - will be created and must not yet exist")
        .action((x, c) => c.copy(outDir = x))
      opt[String]("repr")
        .text(
          s"representation to extract: [${Representation.values.toSeq.map(_.toString.toLowerCase).sorted.mkString("|")}] - defaults to `${Representation.Cpg14}`"
        )
        .action((x, c) => c.copy(repr = Representation.withNameIgnoreCase(x)))
      opt[String]("format")
        .action((x, c) => c.copy(format = Format.withNameIgnoreCase(x)))
        .text(
          s"export format, one of [${Format.values.toSeq.map(_.toString.toLowerCase).sorted.mkString("|")}] - defaults to `${Format.Dot}`"
        )
    }.parse(args, Config())
  }

  def exportCpg(cpg: Cpg, representation: Representation.Value, format: Format.Value, outDir: Path): Unit = {
    implicit val semantics: Semantics = DefaultSemantics()
    if (semantics == NoSemantics) {
      System.err.println("Warning: semantics are empty.")
    }

    CpgBasedTool.addDataFlowOverlayIfNonExistent(cpg)
    val context = new LayerCreatorContext(cpg)

    format match {
      case Format.Dot if representation == Representation.All || representation == Representation.Cpg =>
        exportWithFlatgraphFormat(cpg, representation, outDir, DotExporter)
      case Format.Dot =>
        exportDot(representation, outDir, context)
      case Format.Neo4jCsv =>
        exportWithFlatgraphFormat(cpg, representation, outDir, Neo4jCsvExporter)
      case Format.Graphml =>
        exportWithFlatgraphFormat(cpg, representation, outDir, GraphMLExporter)
      case Format.Graphson =>
        exportWithFlatgraphFormat(cpg, representation, outDir, GraphSONExporter)
      case _ =>
        throw new NotImplementedError(s"repr=$representation not yet supported for format=$format")
    }
  }

  private def exportDot(repr: Representation.Value, outDir: Path, context: LayerCreatorContext): Unit = {
    val outDirStr = outDir.toString
    import Representation._
    repr match {
      case Ast   => new DumpAst(AstDumpOptions(outDirStr)).create(context)
      case Cfg   => new DumpCfg(CfgDumpOptions(outDirStr)).create(context)
      case Ddg   => new DumpDdg(DdgDumpOptions(outDirStr)).create(context)
      case Cdg   => new DumpCdg(CdgDumpOptions(outDirStr)).create(context)
      case Pdg   => new DumpPdg(PdgDumpOptions(outDirStr)).create(context)
      case Cpg14 => new DumpCpg14(Cpg14DumpOptions(outDirStr)).create(context)
      case _     => throw new NotImplementedError(s"repr=$repr not yet supported for this format")
    }
  }

  private def exportWithFlatgraphFormat(
    cpg: Cpg,
    repr: Representation.Value,
    outDir: Path,
    exporter: flatgraph.formats.Exporter
  ): Unit = {
    val ExportResult(nodeCount, edgeCount, _, additionalInfo) = repr match {
      case Representation.All =>
        exporter.runExport(cpg.graph, outDir)
      case Representation.Cpg if cpg.graph.nodeCount(NodeTypes.METHOD) == 0 =>
        emptyExportResult
      case Representation.Cpg =>
        val windowsFilenameDeduplicationHelper = mutable.Set.empty[String]
        splitByMethod(cpg)
          .map { case subGraph @ MethodSubGraph(methodName, methodFilename, nodes) =>
            val relativeFilename = sanitizedFileName(
              methodName,
              methodFilename,
              exporter.defaultFileExtension,
              windowsFilenameDeduplicationHelper
            )
            val outFileName = outDir.resolve(relativeFilename)
            Files.createDirectories(outFileName)
            exporter.runExport(cpg.graph.schema, nodes, subGraph.edges, outFileName)
          }
          .reduce(plus)
      case _ =>
        throw new NotImplementedError(s"repr=$repr not yet supported for this format")
    }

    println(s"exported $nodeCount nodes, $edgeCount edges into $outDir")
    additionalInfo.foreach(println)
  }

  /** for each method in the cpg: recursively traverse all AST edges to get the subgraph of nodes within this method add
    * the method and this subgraph to the export add all edges between all of these nodes to the export
    */
  private def splitByMethod(cpg: Cpg): Iterator[MethodSubGraph] = {
    cpg.method.map { method =>
      MethodSubGraph(methodName = method.name, methodFilename = method.filename, nodes = method.ast.toSet)
    }
  }

  /** @param windowsFilenameDeduplicationHelper
    *   utility map to ensure we don't override output files for identical method names
    */
  private[joerncli] def sanitizedFileName(
    methodName: String,
    methodFilename: String,
    fileExtension: String,
    windowsFilenameDeduplicationHelper: mutable.Set[String]
  ): String = {
    val sanitizedMethodName = methodName.replaceAll("[^a-zA-Z0-9-_\\.]", "_")
    val sanitizedFilename =
      if (scala.util.Properties.isWin) {
        // windows has some quirks in it's file system, e.g. we need to ensure paths aren't too long - so we're using a
        // different strategy to sanitize windows file names: first occurrence of a given method uses the method name
        // any methods with the same name afterwards get a `_` suffix
        if (windowsFilenameDeduplicationHelper.contains(sanitizedMethodName)) {
          sanitizedFileName(s"${methodName}_", methodFilename, fileExtension, windowsFilenameDeduplicationHelper)
        } else {
          windowsFilenameDeduplicationHelper.add(sanitizedMethodName)
          sanitizedMethodName
        }
      } else { // non-windows
        // handle leading `/` to ensure we're not writing outside of the output directory
        val sanitizedPath =
          if (methodFilename.isEmpty) "_root_"
          else if (methodFilename.startsWith("/")) s"_root_/$methodFilename"
          else methodFilename
        s"$sanitizedPath/$sanitizedMethodName"
      }

    s"$sanitizedFilename.$fileExtension"
  }

  private def plus(resultA: ExportResult, resultB: ExportResult): ExportResult = {
    ExportResult(
      nodeCount = resultA.nodeCount + resultB.nodeCount,
      edgeCount = resultA.edgeCount + resultB.edgeCount,
      files = resultA.files ++ resultB.files,
      additionalInfo = resultA.additionalInfo
    )
  }

  private def emptyExportResult = ExportResult(0, 0, Seq.empty, Option("Empty CPG"))

  case class MethodSubGraph(methodName: String, methodFilename: String, nodes: Set[GNode]) {
    def edges: Set[Edge] = {
      for {
        node <- nodes
        edge <- Accessors.getEdgesOut(node)
        if nodes.contains(edge.dst)
      } yield edge
    }
  }
}
