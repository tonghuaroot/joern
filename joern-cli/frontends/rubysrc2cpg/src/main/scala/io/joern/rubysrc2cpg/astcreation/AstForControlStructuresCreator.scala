package io.joern.rubysrc2cpg.astcreation

import io.joern.rubysrc2cpg.astcreation.RubyIntermediateAst.{
  BinaryExpression,
  BreakExpression,
  CaseExpression,
  ControlFlowStatement,
  DoWhileExpression,
  ElseClause,
  ForExpression,
  IfExpression,
  MemberCall,
  NextExpression,
  RescueExpression,
  ReturnExpression,
  RubyExpression,
  SimpleIdentifier,
  SingleAssignment,
  SplattingRubyNode,
  StatementList,
  UnaryExpression,
  Unknown,
  UnlessExpression,
  UntilExpression,
  WhenClause,
  WhileExpression
}
import io.joern.x2cpg.{Ast, ValidationMode}
import io.shiftleft.codepropertygraph.generated.{ControlStructureTypes, DispatchTypes, Operators}
import io.shiftleft.codepropertygraph.generated.nodes.NewBlock

trait AstForControlStructuresCreator(implicit withSchemaValidation: ValidationMode) { this: AstCreator =>

  protected def astForControlStructureExpression(node: ControlFlowStatement): Ast = node match {
    case node: WhileExpression   => astForWhileStatement(node)
    case node: DoWhileExpression => astForDoWhileStatement(node)
    case node: UntilExpression   => astForUntilStatement(node)
    case node: CaseExpression    => blockAst(NewBlock(), astsForCaseExpression(node).toList)
    case node: IfExpression      => astForIfExpression(node)
    case node: UnlessExpression  => astForUnlessStatement(node)
    case node: ForExpression     => astForForExpression(node)
    case node: RescueExpression  => astForRescueExpression(node)
    case node: NextExpression    => astForNextExpression(node)
    case node: BreakExpression   => astForBreakExpression(node)
  }

  private def astForWhileStatement(node: WhileExpression): Ast = {
    val conditionAst = astForExpression(node.condition)
    val bodyAsts     = astsForStatement(node.body)
    whileAst(Some(conditionAst), bodyAsts, Option(code(node)), line(node), column(node))
  }

  private def astForDoWhileStatement(node: DoWhileExpression): Ast = {
    val conditionAst = astForExpression(node.condition)
    val bodyAsts     = astsForStatement(node.body)
    doWhileAst(Some(conditionAst), bodyAsts, Option(code(node)), line(node), column(node))
  }

  // `until T do B` is lowered as `while !T do B`
  private def astForUntilStatement(node: UntilExpression): Ast = {
    val notCondition = astForExpression(UnaryExpression("!", node.condition)(node.condition.span))
    val bodyAsts     = astsForStatement(node.body)
    whileAst(Some(notCondition), bodyAsts, Option(code(node)), line(node), column(node))
  }

  // Recursively lowers into a ternary conditional call
  private def astForIfExpression(node: IfExpression): Ast = {
    def builder(node: IfExpression, conditionAst: Ast, thenAst: Ast, elseAsts: List[Ast]): Ast = {
      // We want to make sure there's always an «else» clause in a ternary operator.
      // The default value is a `nil` literal.
      val elseAsts_ = if (elseAsts.isEmpty) {
        List(astForNilBlock)
      } else {
        elseAsts
      }

      val call = callNode(node, code(node), Operators.conditional, Operators.conditional, DispatchTypes.STATIC_DISPATCH)
      callAst(call, conditionAst :: thenAst :: elseAsts_)
    }

    foldIfExpression(builder)(node)
  }

  // `unless T do B` is lowered as `if !T then B`
  private def astForUnlessStatement(node: UnlessExpression): Ast = {
    val notConditionAst = astForExpression(UnaryExpression("!", node.condition)(node.condition.span))
    val thenAst = node.trueBranch match
      case stmtList: StatementList => astForStatementList(stmtList)
      case _                       => astForStatementList(StatementList(List(node.trueBranch))(node.trueBranch.span))
    val elseAsts = node.falseBranch.map(astForElseClause).toList
    val ifNode   = controlStructureNode(node, ControlStructureTypes.IF, code(node))
    controlStructureAst(ifNode, Some(notConditionAst), thenAst :: elseAsts)
  }

  protected def astForElseClause(node: RubyExpression): Ast = {
    node match
      case elseNode: ElseClause =>
        elseNode.thenClause match
          case stmtList: StatementList => astForStatementList(stmtList)
          case node =>
            logger.warn(s"Expecting statement list in ${code(node)} ($relativeFileName), skipping")
            astForUnknown(node)
      case elseNode =>
        logger.warn(s"Expecting else clause in ${code(elseNode)} ($relativeFileName), skipping")
        astForUnknown(elseNode)
  }

  private def astForForExpression(node: ForExpression): Ast = {
    val forEachNode  = controlStructureNode(node, ControlStructureTypes.FOR, code(node))
    val doBodyAst    = astsForStatement(node.doBlock)
    val iteratorNode = astForExpression(node.forVariable)
    val iterableNode = astForExpression(node.iterableVariable)
    Ast(forEachNode).withChild(iteratorNode).withChild(iterableNode).withChildren(doBodyAst)
  }

  protected def astsForCaseExpression(node: CaseExpression): Seq[Ast] = {
    // TODO: Clean up the below
    def goCase(expr: Option[SimpleIdentifier]): List[RubyExpression] = {
      val elseThenClause: Option[RubyExpression] = node.elseClause.map(_.asInstanceOf[ElseClause].thenClause)
      val whenClauses                            = node.whenClauses.map(_.asInstanceOf[WhenClause])
      val ifElseChain = whenClauses.foldRight[Option[RubyExpression]](elseThenClause) {
        (whenClause: WhenClause, restClause: Option[RubyExpression]) =>
          // We translate multiple match expressions into an or expression.
          //
          // A single match expression is compared using `.===` to the case target expression if it is present
          // otherwise it is treated as a conditional.
          //
          // There may be a splat as the last match expression,
          // `case y when *x then c end` or
          // `case when *x then c end`
          // which is translated to `x.include? y` and `x.any?` conditions respectively

          val conditions = whenClause.matchExpressions.map { mExpr =>
            expr.map(e => BinaryExpression(mExpr, "===", e)(mExpr.span)).getOrElse(mExpr)
          } ++ whenClause.matchSplatExpression.iterator.flatMap {
            case splat @ SplattingRubyNode(exprList) =>
              expr
                .map { e =>
                  List(MemberCall(exprList, ".", "include?", List(e))(splat.span))
                }
                .getOrElse {
                  List(MemberCall(exprList, ".", "any?", List())(splat.span))
                }
            case e =>
              logger.warn(s"Unrecognised RubyNode (${e.getClass}) in case match splat expression")
              List(Unknown()(e.span))
          }
          // There is always at least one match expression or a splat
          // will become an unknown in condition at the end
          val condition = conditions.init.foldRight(conditions.last) { (cond, condAcc) =>
            BinaryExpression(cond, "||", condAcc)(whenClause.span)
          }
          val conditional = IfExpression(
            condition,
            whenClause.thenClause.asStatementList,
            List(),
            restClause.map { els => ElseClause(els.asStatementList)(els.span) }
          )(node.span)
          Some(conditional)
      }
      ifElseChain.iterator.toList
    }
    def generatedNode: StatementList = node.expression
      .map { e =>
        val tmp = SimpleIdentifier(None)(e.span.spanStart(this.tmpGen.fresh))
        StatementList(
          List(SingleAssignment(tmp, "=", e)(e.span)) ++
            goCase(Some(tmp))
        )(node.span)
      }
      .getOrElse(StatementList(goCase(None))(node.span))
    astsForStatement(generatedNode)
  }

}
