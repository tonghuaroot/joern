package io.joern.swiftsrc2cpg.astcreation

import io.joern.swiftsrc2cpg.parser.SwiftNodeSyntax.*
import io.joern.swiftsrc2cpg.passes.GlobalBuiltins
import io.joern.x2cpg.datastructures.Stack.*
import io.joern.x2cpg.frontendspecific.swiftsrc2cpg.Defines
import io.joern.x2cpg.{Ast, ValidationMode}
import io.shiftleft.codepropertygraph.generated.*
import io.shiftleft.codepropertygraph.generated.nodes.{NewCall, NewNode}

import scala.annotation.unused

trait AstForExprSyntaxCreator(implicit withSchemaValidation: ValidationMode) {
  this: AstCreator =>

  private val MaxInitializers = 1000

  private def astForEmptyListLikeExpr(node: SwiftNode): Ast = {
    val op           = Operators.arrayInitializer
    val initCallNode = callNode(node, code(node), op, DispatchTypes.STATIC_DISPATCH)
    callAst(initCallNode, List.empty)
  }

  private def astForListLikeExpr(node: SwiftNode, elements: Seq[SwiftNode]): Ast = {
    if (elements.isEmpty) { astForEmptyListLikeExpr(node) }
    else {
      node match {
        case _: (ArrayExprSyntax | TupleExprSyntax) =>
          val op           = Operators.arrayInitializer
          val initCallNode = callNode(node, code(node), op, DispatchTypes.STATIC_DISPATCH)

          val clauses = elements.slice(0, MaxInitializers)

          val args = clauses.map(astForNode)

          val ast = callAst(initCallNode, args)
          if (elements.sizeIs > MaxInitializers) {
            val placeholder =
              literalNode(node, "<too-many-initializers>", Defines.Any).argumentIndex(MaxInitializers)
            ast.withChild(Ast(placeholder)).withArgEdge(initCallNode, placeholder)
          } else {
            ast
          }
        case other =>
          val blockNode_ = blockNode(node, code(node), Defines.Any)

          scope.pushNewBlockScope(blockNode_)
          localAstParentStack.push(blockNode_)

          val tmpName      = scopeLocalUniqueName("tmp")
          val localTmpNode = localNode(node, tmpName, tmpName, Defines.Any).order(0)
          diffGraph.addEdge(localAstParentStack.head, localTmpNode, EdgeTypes.AST)

          val slicedElements = elements.slice(0, MaxInitializers).toList

          val propertiesAsts = slicedElements.map {
            case dictElement: DictionaryElementSyntax =>
              val lhsAst = astForNode(dictElement.key)
              val rhsAst = astForNode(dictElement.value)

              val lhsTmpNode = Ast(identifierNode(dictElement, tmpName))
              val lhsIndexAccessCallAst =
                createIndexAccessCallAst(lhsTmpNode, lhsAst, line(dictElement), column(dictElement))

              createAssignmentCallAst(
                lhsIndexAccessCallAst,
                rhsAst,
                s"${codeOf(lhsIndexAccessCallAst.nodes.head)} = ${codeOf(rhsAst.nodes.head)}",
                line(dictElement),
                column(dictElement)
              )
            case other => astForNode(other)
          }

          val tmpNode = identifierNode(node, tmpName)

          scope.popScope()
          localAstParentStack.pop()

          val placeHolderAst = if (elements.sizeIs > MaxInitializers) {
            val placeholder = literalNode(node, "<too-many-initializers>", Defines.Any)
            Ast(placeholder)
          } else {
            Ast()
          }

          val childrenAsts = propertiesAsts :+ placeHolderAst :+ Ast(tmpNode)
          blockAst(blockNode_, childrenAsts)
      }
    }
  }

  private def astForArrayExprSyntax(node: ArrayExprSyntax): Ast = {
    astForListLikeExpr(node, node.elements.children)
  }

  private def astForArrowExprSyntax(node: ArrowExprSyntax): Ast = notHandledYet(node)

  private def astForAsExprSyntax(node: AsExprSyntax): Ast = {
    val op      = Operators.cast
    val tpeNode = node.`type`
    val tpeCode = code(tpeNode)
    val tpe     = cleanType(tpeCode)
    registerType(tpe)
    val cpgCastExpression = callNode(node, code(node), op, op, DispatchTypes.STATIC_DISPATCH, None, Some(tpe))
    val expr              = astForNode(node.expression)
    val typeRefNode_      = typeRefNode(tpeNode, tpeCode, tpe)
    val arg               = Ast(typeRefNode_)
    callAst(cpgCastExpression, List(arg, expr))
  }

  private def astForAssignmentExprSyntax(node: AssignmentExprSyntax): Ast = notHandledYet(node)

  private def astForAwaitExprSyntax(node: AwaitExprSyntax): Ast = {
    val callNode_ = callNode(node, code(node), "<operator>.await", DispatchTypes.STATIC_DISPATCH)
    val argAsts   = List(astForNode(node.expression))
    callAst(callNode_, argAsts)
  }

  private def astForBinaryOperatorExprSyntax(node: BinaryOperatorExprSyntax): Ast = notHandledYet(node)

  private def astForBooleanLiteralExprSyntax(node: BooleanLiteralExprSyntax): Ast = {
    astForNode(node.literal)
  }

  private def astForBorrowExprSyntax(node: BorrowExprSyntax): Ast = {
    astForNode(node.expression)
  }

  private def astForCanImportExprSyntax(node: CanImportExprSyntax): Ast               = notHandledYet(node)
  private def astForCanImportVersionInfoSyntax(node: CanImportVersionInfoSyntax): Ast = notHandledYet(node)

  private def astForClosureExprSyntax(node: ClosureExprSyntax): Ast = {
    astForNode(node)
  }

  private def astForConsumeExprSyntax(node: ConsumeExprSyntax): Ast = {
    astForNode(node.expression)
  }

  private def astForCopyExprSyntax(node: CopyExprSyntax): Ast = {
    astForNode(node.expression)
  }

  private def astForDeclReferenceExprSyntax(node: DeclReferenceExprSyntax): Ast = {
    astForIdentifier(node)
  }

  private def astForDictionaryExprSyntax(node: DictionaryExprSyntax): Ast = {
    node.content match {
      case t: SwiftToken                  => astForListLikeExpr(node, Seq(t))
      case d: DictionaryElementListSyntax => astForListLikeExpr(node, d.children)
    }
  }

  private def astForDiscardAssignmentExprSyntax(node: DiscardAssignmentExprSyntax): Ast = {
    val name   = scopeLocalUniqueName("wildcard")
    val idNode = identifierNode(node, name)
    scope.addVariableReference(name, idNode, Defines.Any, EvaluationStrategies.BY_REFERENCE)
    Ast(idNode)
  }

  private def astForDoExprSyntax(node: DoExprSyntax): Ast = notHandledYet(node)

  private def astForEditorPlaceholderExprSyntax(node: EditorPlaceholderExprSyntax): Ast = {
    Ast(literalNode(node, code(node), Option(Defines.String)))
  }

  private def astForFloatLiteralExprSyntax(node: FloatLiteralExprSyntax): Ast = {
    astForNode(node.literal)
  }

  private def astForForceUnwrapExprSyntax(node: ForceUnwrapExprSyntax): Ast = {
    astForNode(node.expression)
  }

  private def createBuiltinStaticCall(callExpr: FunctionCallExprSyntax, callee: ExprSyntax, fullName: String): Ast = {
    val callName = callee match {
      case m: MemberAccessExprSyntax => code(m.declName)
      case _                         => code(callee)
    }
    val callNode =
      createStaticCallNode(code(callExpr), callName, fullName, line(callee), column(callee))
    val argAsts = callExpr.arguments.children.map(astForNode)
    callAst(callNode, argAsts)
  }

  private def handleCallNodeArgs(
    callExpr: FunctionCallExprSyntax,
    receiverAst: Ast,
    baseNode: NewNode,
    callName: String
  ): Ast = {

    val trailingClosureAsts            = callExpr.trailingClosure.toList.map(astForNode)
    val additionalTrailingClosuresAsts = callExpr.additionalTrailingClosures.children.map(c => astForNode(c.closure))

    val args = callExpr.arguments.children.map(astForNode) ++ trailingClosureAsts ++ additionalTrailingClosuresAsts

    val callExprCode = code(callExpr)
    val callCode = callExprCode match {
      case c if c.startsWith(".") && codeOf(baseNode) != "this" => s"${codeOf(baseNode)}$callExprCode"
      case c if c.contains("#if ") =>
        val recCode = codeOf(receiverAst.nodes.head)
        if (recCode.endsWith(callName)) {
          s"${codeOf(receiverAst.nodes.head)}(${code(callExpr.arguments)})"
        } else {
          s"${codeOf(receiverAst.nodes.head)}$callName(${code(callExpr.arguments)})"
        }
      case _ => callExprCode
    }
    val callNode_ = callNode(callExpr, callCode, callName, DispatchTypes.DYNAMIC_DISPATCH)
    callAst(callNode_, args, receiver = Option(receiverAst), base = Option(Ast(baseNode)))
  }

  private def astForFunctionCallExprSyntax(node: FunctionCallExprSyntax): Ast = {
    val callee     = node.calledExpression
    val calleeCode = code(callee)
    if (GlobalBuiltins.builtins.contains(calleeCode)) {
      createBuiltinStaticCall(node, callee, calleeCode)
    } else {
      val (receiverAst, baseNode, callName) = callee match {
        case m: MemberAccessExprSyntax =>
          val base   = m.base
          val member = m.declName
          base match {
            case None =>
              // referencing implicit this
              val receiverAst = astForNode(callee)
              val baseNode    = identifierNode(m, "this")
              scope.addVariableReference("this", baseNode, Defines.Any, EvaluationStrategies.BY_REFERENCE)
              (receiverAst, baseNode, code(member))
            case Some(d: DeclReferenceExprSyntax) if code(d) == "this" || code(d) == "self" =>
              val receiverAst = astForNode(callee)
              val baseNode    = identifierNode(d, code(d))
              scope.addVariableReference(code(d), baseNode, Defines.Any, EvaluationStrategies.BY_REFERENCE)
              (receiverAst, baseNode, code(member))
            case Some(d: DeclReferenceExprSyntax) =>
              val receiverAst = astForNode(callee)
              val baseNode    = identifierNode(d, code(d))
              scope.addVariableReference(code(d), baseNode, Defines.Any, EvaluationStrategies.BY_REFERENCE)
              (receiverAst, baseNode, code(member))
            case Some(otherBase) =>
              val tmpVarName  = scopeLocalUniqueName("tmp")
              val baseTmpNode = identifierNode(otherBase, tmpVarName)
              scope.addVariableReference(tmpVarName, baseTmpNode, Defines.Any, EvaluationStrategies.BY_REFERENCE)
              val baseAst   = astForNode(otherBase)
              val codeField = s"(${codeOf(baseTmpNode)} = ${codeOf(baseAst.nodes.head)})"
              val tmpAssignmentAst =
                createAssignmentCallAst(Ast(baseTmpNode), baseAst, codeField, line(otherBase), column(otherBase))
              val memberNode = createFieldIdentifierNode(code(member), line(member), column(member))
              val fieldAccessAst =
                createFieldAccessCallAst(tmpAssignmentAst, memberNode, line(callee), column(callee))
              val thisTmpNode = identifierNode(callee, tmpVarName)
              (fieldAccessAst, thisTmpNode, code(member))
          }
        case _ =>
          val receiverAst = astForNode(callee)
          val thisNode    = identifierNode(callee, "this")
          scope.addVariableReference(thisNode.name, thisNode, Defines.Any, EvaluationStrategies.BY_REFERENCE)
          (receiverAst, thisNode, calleeCode)
      }
      handleCallNodeArgs(node, receiverAst, baseNode, callName)
    }
  }

  private def astForGenericSpecializationExprSyntax(node: GenericSpecializationExprSyntax): Ast = {
    astForNode(node.expression)
  }

  private def astForIfExprSyntax(node: IfExprSyntax): Ast = {
    val code         = this.code(node)
    val ifNode       = controlStructureNode(node, ControlStructureTypes.IF, code)
    val conditionAst = astForNode(node.conditions)
    val thenAst      = astForNode(node.body)
    val elseAst = node.elseBody match {
      case Some(value) => astForNode(value)
      case None        => Ast()
    }
    controlStructureAst(ifNode, Option(conditionAst), Seq(thenAst, elseAst))
  }

  private def astForInOutExprSyntax(node: InOutExprSyntax): Ast = {
    val op        = Defines.PrefixOperatorMap(code(node.ampersand))
    val argAst    = astForNode(node.expression)
    val callNode_ = callNode(node, code(node), op, DispatchTypes.STATIC_DISPATCH)
    callAst(callNode_, List(argAst))
  }

  private def astForInfixOperatorExprSyntax(node: InfixOperatorExprSyntax): Ast = {
    val op        = Defines.InfixOperatorMap(code(node.operator))
    val lhsAst    = astForNode(node.leftOperand)
    val rhsAst    = astForNode(node.rightOperand)
    val callNode_ = callNode(node, code(node), op, DispatchTypes.STATIC_DISPATCH)
    val argAsts   = List(lhsAst, rhsAst)
    callAst(callNode_, argAsts)
  }

  private def astForIntegerLiteralExprSyntax(node: IntegerLiteralExprSyntax): Ast = {
    astForNode(node.literal)
  }

  private def astForIsExprSyntax(node: IsExprSyntax): Ast = {
    val lhsAst    = astForNode(node.expression)
    val rhsAst    = astForNode(node.`type`)
    val callNode_ = callNode(node, code(node), Operators.instanceOf, DispatchTypes.STATIC_DISPATCH)
    val argAsts   = List(lhsAst, rhsAst)
    callAst(callNode_, argAsts)
  }

  private def astForKeyPathExprSyntax(node: KeyPathExprSyntax): Ast = notHandledYet(node)

  private def astForMacroExpansionExprSyntax(node: MacroExpansionExprSyntax): Ast = {
    val name = code(node.macroName)

    val trailingClosureAsts            = node.trailingClosure.toList.map(astForNode)
    val additionalTrailingClosuresAsts = node.additionalTrailingClosures.children.map(c => astForNode(c.closure))

    val argAsts = astForNode(node.arguments) +: (trailingClosureAsts ++ additionalTrailingClosuresAsts)
    val callNode =
      NewCall()
        .name(name)
        .dispatchType(DispatchTypes.INLINED)
        .methodFullName(name)
        .code(code(node))
        .typeFullName(Defines.Any)
        .lineNumber(line(node))
        .columnNumber(column(node))
    callAst(callNode, argAsts)
  }

  private def astForMemberAccessExprSyntax(node: MemberAccessExprSyntax): Ast = {
    val base   = node.base
    val member = node.declName
    val baseAst = base match {
      case None =>
        // referencing implicit this
        val baseNode = identifierNode(node, "this")
        scope.addVariableReference("this", baseNode, Defines.Any, EvaluationStrategies.BY_REFERENCE)
        Ast(baseNode)
      case Some(d: DeclReferenceExprSyntax) if code(d) == "this" || code(d) == "self" =>
        val baseNode = identifierNode(d, code(d))
        scope.addVariableReference(code(d), baseNode, Defines.Any, EvaluationStrategies.BY_REFERENCE)
        Ast(baseNode)
      case Some(otherBase) =>
        astForNode(otherBase)
    }

    member.baseName match {
      case l @ integerLiteral(_) =>
        val memberNode = astForIntegerLiteralToken(l)
        createIndexAccessCallAst(baseAst, memberNode, line(node), column(node))
      case other =>
        val memberNode = createFieldIdentifierNode(code(other), line(other), column(other))
        createFieldAccessCallAst(baseAst, memberNode, line(node), column(node))
    }

  }

  private def astForMissingExprSyntax(@unused node: MissingExprSyntax): Ast = Ast()

  private def astForNilLiteralExprSyntax(node: NilLiteralExprSyntax): Ast = {
    Ast(literalNode(node, code(node), Option(Defines.Nil)))
  }

  private def astForOptionalChainingExprSyntax(node: OptionalChainingExprSyntax): Ast = {
    astForNode(node.expression)
  }

  private def astForPackElementExprSyntax(node: PackElementExprSyntax): Ast = {
    astForNode(node.pack)
  }

  private def astForPackExpansionExprSyntax(node: PackExpansionExprSyntax): Ast = {
    astForNode(node.repetitionPattern)
  }

  private def astForPatternExprSyntax(node: PatternExprSyntax): Ast = {
    astForNode(node.pattern)
  }

  private def astForPostfixIfConfigExprSyntax(node: PostfixIfConfigExprSyntax): Ast = {
    val children              = node.config.clauses.children
    val ifIfConfigClauses     = children.filter(c => code(c.poundKeyword) == "#if")
    val elseIfIfConfigClauses = children.filter(c => code(c.poundKeyword) == "#elseif")
    val elseIfConfigClauses   = children.filter(c => code(c.poundKeyword) == "#else")

    node.base match {
      case Some(base) =>
        val maybeFunctionCallExpr = ifIfConfigClauses match {
          case Nil => None
          case ifIfConfigClause :: Nil if ifConfigDeclConditionIsSatisfied(ifIfConfigClause) =>
            ifIfConfigClause.elements
          case _ :: Nil =>
            val firstElseIfSatisfied = elseIfIfConfigClauses.find(ifConfigDeclConditionIsSatisfied)
            firstElseIfSatisfied match {
              case Some(elseIfIfConfigClause) =>
                elseIfIfConfigClause.elements
              case None =>
                elseIfConfigClauses match {
                  case Nil                       => None
                  case elseIfConfigClause :: Nil => elseIfConfigClause.elements
                  case _                         => None
                }
            }
          case _ => None
        }
        maybeFunctionCallExpr match {
          case Some(functionCallExpr: FunctionCallExprSyntax) =>
            functionCallExpr.calledExpression match
              case MemberAccessExprSyntax(json) =>
                val memberChildren = json("children").arr
                memberChildren.addOne(base.json)
                astForNode(functionCallExpr)
              case _ =>
                notHandledYet(node)
          case _ => notHandledYet(node)
        }
      case None => astForNode(node.config)
    }

  }

  private def astForPostfixOperatorExprSyntax(node: PostfixOperatorExprSyntax): Ast = {
    val operatorMethod = Defines.PostfixOperatorMap(code(node.operator))
    val unaryCall      = callNode(node, code(node), operatorMethod, operatorMethod, DispatchTypes.STATIC_DISPATCH)
    val expressionAst  = astForNode(node.expression)
    callAst(unaryCall, List(expressionAst))
  }

  private def astForPrefixOperatorExprSyntax(node: PrefixOperatorExprSyntax): Ast = {
    val operatorMethod = Defines.PrefixOperatorMap(code(node.operator))
    val unaryCall      = callNode(node, code(node), operatorMethod, operatorMethod, DispatchTypes.STATIC_DISPATCH)
    val expressionAst  = astForNode(node.expression)
    callAst(unaryCall, List(expressionAst))
  }

  private def astForRegexLiteralExprSyntax(node: RegexLiteralExprSyntax): Ast = notHandledYet(node)

  private def astForSequenceExprSyntax(node: SequenceExprSyntax): Ast = {
    astForNode(node.elements)
  }

  private def astForSimpleStringLiteralExprSyntax(node: SimpleStringLiteralExprSyntax): Ast = {
    astForNode(node.segments)
  }

  private def astForStringLiteralExprSyntax(node: StringLiteralExprSyntax): Ast = {
    astForNode(node.segments)
  }

  private def astForSubscriptCallExprSyntax(node: SubscriptCallExprSyntax): Ast = {
    val baseAst   = astForNode(node.calledExpression)
    val memberAst = astForNode(node.arguments)

    val trailingClosureAsts            = node.trailingClosure.toList.map(astForNode)
    val additionalTrailingClosuresAsts = node.additionalTrailingClosures.children.map(c => astForNode(c.closure))

    val additionalArgsAsts = trailingClosureAsts ++ additionalTrailingClosuresAsts
    createIndexAccessCallAst(baseAst, memberAst, line(node), column(node), additionalArgsAsts)
  }

  private def astForSuperExprSyntax(node: SuperExprSyntax): Ast = {
    Ast(identifierNode(node, "super"))
  }

  protected def astsForSwitchCase(switchCase: SwitchCaseSyntax | IfConfigDeclSyntax): List[Ast] = {
    val labelAst = Ast(createJumpTarget(switchCase))
    val (testAsts, consequentAsts) = switchCase match {
      case s: SwitchCaseSyntax =>
        val (tAsts, flowAst) = s.label match {
          case i: SwitchCaseLabelSyntax =>
            val children         = i.caseItems.children
            val childrenTestAsts = children.map(c => astForNode(c.pattern))
            val childrenFlowAsts = children.collect {
              case child if child.whereClause.isDefined =>
                val whereClause = child.whereClause.get
                val ifNode =
                  controlStructureNode(whereClause.condition, ControlStructureTypes.IF, code(whereClause.condition))
                val whereAst = astForNode(whereClause)
                val whereClauseCallNode = callNode(
                  whereClause.condition,
                  s"!(${code(whereClause.condition)})",
                  Operators.logicalNot,
                  DispatchTypes.STATIC_DISPATCH
                )
                val argAsts = List(whereAst)
                val testAst = callAst(whereClauseCallNode, argAsts)
                val consequentAst =
                  Ast(controlStructureNode(whereClause.condition, ControlStructureTypes.CONTINUE, "continue"))
                setOrderExplicitly(testAst, 1)
                setOrderExplicitly(consequentAst, 2)
                Ast(ifNode)
                  .withChild(testAst)
                  .withConditionEdge(ifNode, testAst.nodes.head)
                  .withChild(consequentAst)
            }
            (childrenTestAsts, childrenFlowAsts)
          case other => (List(astForNode(other)), List.empty)
        }
        val needsSyntheticBreak = !s.statements.children.lastOption.exists(_.item.isInstanceOf[FallThroughStmtSyntax])
        val asts                = flowAst :+ astForNode(s.statements)
        val cAsts = if (needsSyntheticBreak) {
          asts :+ Ast(controlStructureNode(s, ControlStructureTypes.BREAK, "break"))
        } else {
          asts
        }
        (tAsts.toList, cAsts.toList)
      case i: IfConfigDeclSyntax =>
        (List.empty, List(astForIfConfigDeclSyntax(i)))
    }
    labelAst +: (testAsts ++ consequentAsts)
  }

  private def astForSwitchExprSyntax(node: SwitchExprSyntax): Ast = {
    val switchNode = controlStructureNode(node, ControlStructureTypes.SWITCH, code(node))

    // The semantics of switch statement children is partially defined by their order value.
    // The blockAst must have order == 2. Only to avoid collision we set switchExpressionAst to 1
    // because the semantics of it is already indicated via the condition edge.
    val switchExpressionAst = astForNode(node.subject)
    setOrderExplicitly(switchExpressionAst, 1)

    val blockNode_ = blockNode(node).order(2)
    scope.pushNewBlockScope(blockNode_)
    localAstParentStack.push(blockNode_)
    val casesAsts = node.cases.children.toList.flatMap(astsForSwitchCase)
    scope.popScope()
    localAstParentStack.pop()

    Ast(switchNode)
      .withChild(switchExpressionAst)
      .withConditionEdge(switchNode, switchExpressionAst.nodes.head)
      .withChild(blockAst(blockNode_, casesAsts))
  }

  private def astForTernaryExprSyntax(node: TernaryExprSyntax): Ast = {
    val name = Operators.conditional
    val call = callNode(node, code(node), name, DispatchTypes.STATIC_DISPATCH)

    val condAst = astForNode(node.condition)
    val posAst  = astForNode(node.thenExpression)
    val negAst  = astForNode(node.elseExpression)

    val children = List(condAst, posAst, negAst)
    callAst(call, children)
  }

  private def astForTryExprSyntax(node: TryExprSyntax): Ast = {
    val tryNode = controlStructureNode(node, ControlStructureTypes.TRY, code(node))
    val bodyAst = astForNode(node.expression)
    tryCatchAst(tryNode, bodyAst, Seq.empty, None)
  }

  private def astForTupleExprSyntax(node: TupleExprSyntax): Ast = {
    node.elements.children.toList match {
      case Nil         => astForListLikeExpr(node, Seq.empty)
      case head :: Nil => astForNode(head)
      case other       => astForListLikeExpr(node, other)
    }
  }

  private def astForTypeExprSyntax(node: TypeExprSyntax): Ast = {
    val nodeCode = code(node)
    registerType(nodeCode)
    Ast(identifierNode(node, nodeCode, nodeCode, Defines.Any, Seq(nodeCode)))
  }

  private def astForUnresolvedAsExprSyntax(node: UnresolvedAsExprSyntax): Ast           = notHandledYet(node)
  private def astForUnresolvedIsExprSyntax(node: UnresolvedIsExprSyntax): Ast           = notHandledYet(node)
  private def astForUnresolvedTernaryExprSyntax(node: UnresolvedTernaryExprSyntax): Ast = notHandledYet(node)

  protected def astForExprSyntax(exprSyntax: ExprSyntax): Ast = exprSyntax match {
    case node: ArrayExprSyntax                 => astForArrayExprSyntax(node)
    case node: ArrowExprSyntax                 => astForArrowExprSyntax(node)
    case node: AsExprSyntax                    => astForAsExprSyntax(node)
    case node: AssignmentExprSyntax            => astForAssignmentExprSyntax(node)
    case node: AwaitExprSyntax                 => astForAwaitExprSyntax(node)
    case node: BinaryOperatorExprSyntax        => astForBinaryOperatorExprSyntax(node)
    case node: BooleanLiteralExprSyntax        => astForBooleanLiteralExprSyntax(node)
    case node: BorrowExprSyntax                => astForBorrowExprSyntax(node)
    case node: CanImportExprSyntax             => astForCanImportExprSyntax(node)
    case node: CanImportVersionInfoSyntax      => astForCanImportVersionInfoSyntax(node)
    case node: ClosureExprSyntax               => astForClosureExprSyntax(node)
    case node: ConsumeExprSyntax               => astForConsumeExprSyntax(node)
    case node: CopyExprSyntax                  => astForCopyExprSyntax(node)
    case node: DeclReferenceExprSyntax         => astForDeclReferenceExprSyntax(node)
    case node: DictionaryExprSyntax            => astForDictionaryExprSyntax(node)
    case node: DiscardAssignmentExprSyntax     => astForDiscardAssignmentExprSyntax(node)
    case node: DoExprSyntax                    => astForDoExprSyntax(node)
    case node: EditorPlaceholderExprSyntax     => astForEditorPlaceholderExprSyntax(node)
    case node: FloatLiteralExprSyntax          => astForFloatLiteralExprSyntax(node)
    case node: ForceUnwrapExprSyntax           => astForForceUnwrapExprSyntax(node)
    case node: FunctionCallExprSyntax          => astForFunctionCallExprSyntax(node)
    case node: GenericSpecializationExprSyntax => astForGenericSpecializationExprSyntax(node)
    case node: IfExprSyntax                    => astForIfExprSyntax(node)
    case node: InOutExprSyntax                 => astForInOutExprSyntax(node)
    case node: InfixOperatorExprSyntax         => astForInfixOperatorExprSyntax(node)
    case node: IntegerLiteralExprSyntax        => astForIntegerLiteralExprSyntax(node)
    case node: IsExprSyntax                    => astForIsExprSyntax(node)
    case node: KeyPathExprSyntax               => astForKeyPathExprSyntax(node)
    case node: MacroExpansionExprSyntax        => astForMacroExpansionExprSyntax(node)
    case node: MemberAccessExprSyntax          => astForMemberAccessExprSyntax(node)
    case node: MissingExprSyntax               => astForMissingExprSyntax(node)
    case node: NilLiteralExprSyntax            => astForNilLiteralExprSyntax(node)
    case node: OptionalChainingExprSyntax      => astForOptionalChainingExprSyntax(node)
    case node: PackElementExprSyntax           => astForPackElementExprSyntax(node)
    case node: PackExpansionExprSyntax         => astForPackExpansionExprSyntax(node)
    case node: PatternExprSyntax               => astForPatternExprSyntax(node)
    case node: PostfixIfConfigExprSyntax       => astForPostfixIfConfigExprSyntax(node)
    case node: PostfixOperatorExprSyntax       => astForPostfixOperatorExprSyntax(node)
    case node: PrefixOperatorExprSyntax        => astForPrefixOperatorExprSyntax(node)
    case node: RegexLiteralExprSyntax          => astForRegexLiteralExprSyntax(node)
    case node: SequenceExprSyntax              => astForSequenceExprSyntax(node)
    case node: SimpleStringLiteralExprSyntax   => astForSimpleStringLiteralExprSyntax(node)
    case node: StringLiteralExprSyntax         => astForStringLiteralExprSyntax(node)
    case node: SubscriptCallExprSyntax         => astForSubscriptCallExprSyntax(node)
    case node: SuperExprSyntax                 => astForSuperExprSyntax(node)
    case node: SwitchExprSyntax                => astForSwitchExprSyntax(node)
    case node: TernaryExprSyntax               => astForTernaryExprSyntax(node)
    case node: TryExprSyntax                   => astForTryExprSyntax(node)
    case node: TupleExprSyntax                 => astForTupleExprSyntax(node)
    case node: TypeExprSyntax                  => astForTypeExprSyntax(node)
    case node: UnresolvedAsExprSyntax          => astForUnresolvedAsExprSyntax(node)
    case node: UnresolvedIsExprSyntax          => astForUnresolvedIsExprSyntax(node)
    case node: UnresolvedTernaryExprSyntax     => astForUnresolvedTernaryExprSyntax(node)
  }
}
