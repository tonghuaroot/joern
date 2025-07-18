package io.joern.c2cpg.astcreation

import io.joern.c2cpg.Config
import io.joern.c2cpg.parser.HeaderFileFinder
import io.joern.x2cpg.Ast
import io.joern.x2cpg.AstCreatorBase
import io.joern.x2cpg.ValidationMode
import io.joern.x2cpg.datastructures.Stack.*
import io.joern.x2cpg.datastructures.VariableScopeManager
import io.shiftleft.codepropertygraph.generated.NodeTypes
import io.shiftleft.codepropertygraph.generated.nodes.*
import io.shiftleft.codepropertygraph.generated.DiffGraphBuilder
import io.shiftleft.semanticcpg.language.types.structure.NamespaceTraversal
import org.eclipse.cdt.core.dom.ast.IASTNode
import org.eclipse.cdt.core.dom.ast.IASTTranslationUnit
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import scala.collection.mutable

/** Translates the Eclipse CDT AST into a CPG AST. */
class AstCreator(
  val filename: String,
  val global: CGlobal,
  val config: Config,
  val cdtAst: IASTTranslationUnit,
  val headerFileFinder: HeaderFileFinder
) extends AstCreatorBase[IASTNode, AstCreator](filename)(config.schemaValidation)
    with AstForTypesCreator
    with AstForFunctionsCreator
    with AstForPrimitivesCreator
    with AstForInitializersCreator
    with AstForStatementsCreator
    with AstForExpressionsCreator
    with AstCreatorHelper
    with FullNameProvider
    with TypeNameProvider
    with MacroHandler {

  protected implicit val schemaValidation: ValidationMode = config.schemaValidation

  protected val logger: Logger = LoggerFactory.getLogger(classOf[AstCreator])

  protected val scope: VariableScopeManager = new CVariableScopeManager()

  // TypeDecls with their bindings (with their refs) for lambdas and methods are not put in the AST
  // where the respective nodes are defined. Instead, we put them under the parent TYPE_DECL in which they are defined.
  // To achieve this we need this extra stack.
  protected val methodAstParentStack: Stack[NewNode] = new Stack()
  protected val typeRefIdStack: Stack[NewTypeRef]    = new Stack()

  def createAst(): DiffGraphBuilder = {
    val fileContent = if (!config.disableFileContent) Option(cdtAst.getRawSignature) else None
    val fileNode    = NewFile().name(fileName(cdtAst)).order(0)
    fileContent.foreach(fileNode.content(_))
    val ast = Ast(fileNode).withChild(astForTranslationUnit(cdtAst))
    Ast.storeInDiffGraph(ast, diffGraph)
    scope.createVariableReferenceLinks(diffGraph, filename)
    diffGraph
  }

  private def astForTranslationUnit(iASTTranslationUnit: IASTTranslationUnit): Ast = {
    val namespaceBlock = globalNamespaceBlock()
    methodAstParentStack.push(namespaceBlock)
    val translationUnitAst =
      astInFakeMethod(namespaceBlock.fullName, fileName(iASTTranslationUnit), iASTTranslationUnit)
    val depsAndImportsAsts = astsForDependenciesAndImports(iASTTranslationUnit)
    val commentsAsts       = astsForComments(iASTTranslationUnit)
    val childrenAsts       = depsAndImportsAsts ++ Seq(translationUnitAst) ++ commentsAsts
    Ast(namespaceBlock).withChildren(childrenAsts)
  }

  /** Creates an AST of all declarations found in the translation unit - wrapped in a fake method.
    */
  private def astInFakeMethod(fullName: String, path: String, iASTTranslationUnit: IASTTranslationUnit): Ast = {
    val includeInactive = config.compilationDatabaseFilename.isEmpty && config.defines.isEmpty
    val allDecls        = iASTTranslationUnit.getDeclarations(includeInactive).toList.filterNot(isIncludedNode)
    val name            = NamespaceTraversal.globalNamespaceName

    val fakeGlobalTypeDecl =
      typeDeclNode(iASTTranslationUnit, name, fullName, filename, name, NodeTypes.NAMESPACE_BLOCK, fullName)
    methodAstParentStack.push(fakeGlobalTypeDecl)

    val fakeGlobalMethod =
      methodNode(iASTTranslationUnit, name, name, fullName, None, path, Option(NodeTypes.TYPE_DECL), Option(fullName))
    methodAstParentStack.push(fakeGlobalMethod)

    val blockNode_ = blockNode(iASTTranslationUnit)
    scope.pushNewMethodScope(fakeGlobalMethod.fullName, fakeGlobalMethod.name, blockNode_, None)
    val declsAsts = allDecls.flatMap(astsForDeclaration)

    val methodReturn = methodReturnNode(iASTTranslationUnit, Defines.Any)
    Ast(fakeGlobalTypeDecl).withChild(
      methodAst(fakeGlobalMethod, Seq.empty, blockAst(blockNode_, declsAsts), methodReturn)
    )
  }

  override protected def code(node: IASTNode): String = {
    shortenCode(nodeSignature(node))
  }

  override protected def line(node: IASTNode): Option[Int] = {
    nullSafeFileLocation(node).map(_.getStartingLineNumber)
  }

  override protected def lineEnd(node: IASTNode): Option[Int] = {
    nullSafeFileLocationLast(node).map(_.getEndingLineNumber)
  }

  protected def column(node: IASTNode): Option[Int] = {
    nodeOffsets(node).map { case (startOffset, _) =>
      offsetToColumn(startOffset)
    }
  }

  private def nodeOffsets(node: IASTNode): Option[(Int, Int)] = {
    for {
      startOffset <- nullSafeFileLocation(node).map(l => l.getNodeOffset)
      endOffset   <- nullSafeFileLocation(node).map(l => l.getNodeOffset + l.getNodeLength)
    } yield (startOffset, endOffset)
  }

  protected def columnEnd(node: IASTNode): Option[Int] = {
    nodeOffsets(node).map { case (_, endOffset) =>
      offsetToColumn(endOffset - 1)
    }
  }

  override protected def offset(node: IASTNode): Option[(Int, Int)] = {
    Option.when(!config.disableFileContent) { nodeOffsets(node) }.flatten
  }

}
