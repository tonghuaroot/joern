package io.joern.c2cpg.passes.ast

import io.joern.c2cpg.astcreation.Defines
import io.joern.c2cpg.testfixtures.AstC2CpgSuite
import io.shiftleft.codepropertygraph.generated.Cpg
import io.shiftleft.codepropertygraph.generated.ControlStructureTypes
import io.shiftleft.codepropertygraph.generated.DispatchTypes
import io.shiftleft.codepropertygraph.generated.EdgeTypes
import io.shiftleft.codepropertygraph.generated.NodeTypes
import io.shiftleft.codepropertygraph.generated.Operators
import io.shiftleft.codepropertygraph.generated.nodes.*
import io.shiftleft.semanticcpg.language.*
import io.shiftleft.semanticcpg.language.operatorextension.OpNodes
import io.shiftleft.semanticcpg.language.types.structure.NamespaceTraversal

class AstCreationPassTests extends AstC2CpgSuite {

  "Method AST layout" should {

    "be correct for method signature" in {
      val cpg = code("""
       |char *foo() {};
       |char *hello();
       |""".stripMargin)
      inside(cpg.method("foo").l) { case List(foo) =>
        foo.signature shouldBe "char*()"
      }
      inside(cpg.method("hello").l) { case List(hello) =>
        hello.signature shouldBe "char*()"
      }
    }

    "be correct for method signature with variadic parameter in plain C (ellipsis)" in {
      val cpg = code("""
          |int foo(const char *a, ...){ return 0; }
          |int bar(const char *a...){ return 0; }
          |""".stripMargin)
      val List(foo) = cpg.method("foo").l
      foo.fullName shouldBe "foo"
      foo.signature shouldBe "int(char*,...)"
      val List(a1, ellipsis1) = foo.parameter.l
      a1.name shouldBe "a"
      a1.code shouldBe "const char *a"
      a1.typeFullName shouldBe "char*"
      a1.index shouldBe 1
      a1.isVariadic shouldBe false
      ellipsis1.name shouldBe "<param>2"
      ellipsis1.code shouldBe "<param>2..."
      ellipsis1.typeFullName shouldBe "char*"
      ellipsis1.index shouldBe 2
      ellipsis1.isVariadic shouldBe true

      val List(bar) = cpg.method("bar").l
      bar.fullName shouldBe "bar"
      bar.signature shouldBe "int(char*,...)"
      val List(a2, ellipsis2) = bar.parameter.l
      a2.name shouldBe "a"
      a2.code shouldBe "const char *a"
      a2.typeFullName shouldBe "char*"
      a2.index shouldBe 1
      a2.isVariadic shouldBe false
      ellipsis2.name shouldBe "<param>2"
      ellipsis2.code shouldBe "<param>2..."
      ellipsis2.typeFullName shouldBe "char*"
      ellipsis2.index shouldBe 2
      ellipsis2.isVariadic shouldBe true
    }

    "be correct for method signature with variadic parameter in C++ (ellipsis)" in {
      val cpg = code(
        """
          |int foo(const char *a, ...){ return 0; }
          |int bar(const char *a...){ return 0; }
          |
          |void main() {
          |  foo("a", "b", "c");
          |}
          |""".stripMargin,
        "foo.cpp"
      )
      val List(fooCall) = cpg.call.nameExact("foo").l
      fooCall.methodFullName shouldBe "foo:int(char*,...)"
      fooCall.signature shouldBe "int(char*,...)"

      val List(foo) = cpg.method("foo").l
      foo.fullName shouldBe "foo:int(char*,...)"
      foo.signature shouldBe "int(char*,...)"
      val List(a1, ellipsis1) = foo.parameter.l
      a1.name shouldBe "a"
      a1.code shouldBe "const char *a"
      a1.typeFullName shouldBe "char*"
      a1.index shouldBe 1
      a1.isVariadic shouldBe false
      ellipsis1.name shouldBe "<param>2"
      ellipsis1.code shouldBe "<param>2..."
      ellipsis1.typeFullName shouldBe "char*"
      ellipsis1.index shouldBe 2
      ellipsis1.isVariadic shouldBe true

      val List(bar) = cpg.method("bar").l
      bar.fullName shouldBe "bar:int(char*,...)"
      bar.signature shouldBe "int(char*,...)"
      val List(a2, ellipsis2) = foo.parameter.l
      a2.name shouldBe "a"
      a2.code shouldBe "const char *a"
      a2.typeFullName shouldBe "char*"
      a2.index shouldBe 1
      a2.isVariadic shouldBe false
      ellipsis2.name shouldBe "<param>2"
      ellipsis2.code shouldBe "<param>2..."
      ellipsis2.typeFullName shouldBe "char*"
      ellipsis2.index shouldBe 2
      ellipsis2.isVariadic shouldBe true
    }

    "be correct for full names and signatures for method problem bindings" in {
      val cpg = code(
        """
          |char tpe<wchar_t>::foo(char_type a, char b) const {
          |  return static_cast<char>(a);
          |}
          |const wchar_t* tpe<wchar_t>::foo(const char_type* a, const char_type* b, char c, char* d) const {
          |  return a;
          |}
          |""".stripMargin,
        "foo.cpp"
      )
      // tpe<wchar_t> can't be resolved for both methods resulting in problem bindings.
      // We can however manually reconstruct the signature from the params and return type without
      // relying on the resolved function binding signature.
      val List(foo1, foo2) = cpg.method.nameExact("foo").l
      foo1.fullName shouldBe "tpe.foo:char(char_type,char)<const>"
      foo1.signature shouldBe "char(char_type,char)<const>"
      foo2.fullName shouldBe "tpe.foo:wchar_t*(char_type*,char_type*,char,char*)<const>"
      foo2.signature shouldBe "wchar_t*(char_type*,char_type*,char,char*)<const>"
    }

    "be correct for packed args" in {
      val cpg = code("void foo(int x, int*... args) {};", "test.cpp")
      inside(cpg.method("foo").l) { case List(m) =>
        m.signature shouldBe "void(int,int*)"
        inside(m.parameter.l) { case List(x, args) =>
          x.name shouldBe "x"
          x.code shouldBe "int x"
          x.typeFullName shouldBe "int"
          x.isVariadic shouldBe false
          x.index shouldBe 1
          args.name shouldBe "args"
          args.code shouldBe "int*... args"
          args.typeFullName shouldBe "int*"
          args.isVariadic shouldBe true
          args.index shouldBe 2
        }
      }
    }

    "be correct for varargs" in {
      val cpg = code("void foo(int x, int args...) {};", "test.cpp")
      inside(cpg.method("foo").l) { case List(m) =>
        m.fullName shouldBe "foo:void(int,int,...)"
        inside(m.parameter.l) { case List(x, args, param3) =>
          x.name shouldBe "x"
          x.code shouldBe "int x"
          x.typeFullName shouldBe "int"
          x.isVariadic shouldBe false
          x.index shouldBe 1
          args.name shouldBe "args"
          args.code shouldBe "int args"
          args.typeFullName shouldBe "int"
          args.isVariadic shouldBe false
          args.index shouldBe 2
          param3.name shouldBe "<param>3"
          param3.code shouldBe "<param>3..."
          param3.typeFullName shouldBe "int"
          param3.isVariadic shouldBe true
          param3.index shouldBe 3
        }
      }
    }

    "be correct for pack expansion with template" in {
      val cpg = code(
        """
          |template<typename... Args>
          |void foo(char* a, Args... args) {}
          |
          |void main() {
          |  foo("Hello", "World", "!");
          |}
          |""".stripMargin,
        "test.cpp"
      )
      val List(fooMethod) = cpg.method.nameExact("foo").l
      val List(fooCall)   = cpg.call.nameExact("foo").l

      fooMethod.fullName shouldBe "foo:void(char*,Args)"
      fooCall.methodFullName shouldBe "foo:void(char*,Args)"

      fooMethod.fullName shouldBe fooCall.methodFullName
    }

    "be correct for pack expansion with dereferences with template" in {
      val cpg = code(
        """
          |template<typename... Args>
          |void foo(char* a, Args&&... args) {}
          |
          |void main() {
          |  foo("Hello", "World", "!");
          |}
          |""".stripMargin,
        "test.cpp"
      )
      val List(fooMethod) = cpg.method.nameExact("foo").l
      val List(fooCall)   = cpg.call.nameExact("foo").l

      fooMethod.fullName shouldBe "foo:void(char*,Args&&)"
      fooCall.methodFullName shouldBe "foo:void(char*,Args&&)"

      fooMethod.fullName shouldBe fooCall.methodFullName
    }

    "be correct for knr function declarations" in {
      val cpg = code("""
        |int handler(x, y)
        | int *x;
        | int *y;
        | {};
        |""".stripMargin)
      inside(cpg.method("handler").l) { case List(m) =>
        inside(m.parameter.l) { case List(x, y) =>
          x.name shouldBe "x"
          x.code shouldBe "int *x;"
          x.typeFullName shouldBe "int*"
          x.order shouldBe 1
          y.name shouldBe "y"
          y.code shouldBe "int *y;"
          y.typeFullName shouldBe "int*"
          y.order shouldBe 2
        }
      }
    }

    "be correct for empty method" in {
      val cpg = code("void method(int x) { }")
      inside(cpg.method.nameExact("method").astChildren.l) {
        case List(param: MethodParameterIn, _: Block, ret: MethodReturn) =>
          ret.typeFullName shouldBe "void"
          param.typeFullName shouldBe "int"
          param.name shouldBe "x"
      }
    }

    "be correct parameter in nodes as pointer" in {
      val cpg = code("""
        |void method(a_struct_type *a_struct) {
        |  void *x = NULL;
        |  a_struct->foo = x;
        |  free(x);
        |}
        |""".stripMargin)
      inside(cpg.method.nameExact("method").parameter.l) { case List(param: MethodParameterIn) =>
        param.typeFullName shouldBe "a_struct_type*"
        param.name shouldBe "a_struct"
        param.code shouldBe "a_struct_type *a_struct"
      }
    }

    "be correct parameter in nodes as pointer with struct" in {
      val cpg = code("""
       |void method(struct date *date) {
       |  void *x = NULL;
       |  a_struct->foo = x;
       |  free(x);
       |}
       |""".stripMargin)
      inside(cpg.method.nameExact("method").parameter.l) { case List(param: MethodParameterIn) =>
        param.code shouldBe "struct date *date"
        param.typeFullName shouldBe "date*"
        param.name shouldBe "date"
      }
    }

    "be correct parameter in nodes as array" in {
      val cpg = code("""
       |void method(int x[]) {
       |  void *x = NULL;
       |  a_struct->foo = x;
       |  free(x);
       |}
       |""".stripMargin)
      inside(cpg.method.nameExact("method").parameter.l) { case List(param: MethodParameterIn) =>
        param.typeFullName shouldBe "int[]"
        param.name shouldBe "x"
      }
    }

    "be correct parameter in nodes as array ptr" in {
      val cpg = code("""
       |void method(int []) {
       |  void *x = NULL;
       |  a_struct->foo = x;
       |  free(x);
       |}
       |""".stripMargin)
      inside(cpg.method.nameExact("method").parameter.l) { case List(param: MethodParameterIn) =>
        param.typeFullName shouldBe "int[]"
        param.name shouldBe ""
      }
    }

    "be correct parameter in nodes as struct array" in {
      val cpg = code("""
       |void method(a_struct_type a_struct[]) {
       |  void *x = NULL;
       |  a_struct->foo = x;
       |  free(x);
       |}
       |""".stripMargin)
      inside(cpg.method.nameExact("method").parameter.l) { case List(param: MethodParameterIn) =>
        param.typeFullName shouldBe "a_struct_type[]"
        param.name shouldBe "a_struct"
      }
    }

    "be correct parameter in nodes as struct array with ptr" in {
      val cpg = code("""
      |void method(a_struct_type *a_struct[]) {
      |  void *x = NULL;
      |  a_struct->foo = x;
      |  free(x);
      |}
      |""".stripMargin)
      inside(cpg.method.nameExact("method").parameter.l) { case List(param: MethodParameterIn) =>
        param.typeFullName shouldBe "a_struct_type[]*"
        param.name shouldBe "a_struct"
      }
    }

    "be correct for decl assignment" in {
      val cpg = code("""
        |void method() {
        |  int local = 1;
        |}
        |""".stripMargin)
      inside(cpg.method.nameExact("method").block.astChildren.l) { case List(local: Local, call: Call) =>
        local.name shouldBe "local"
        local.typeFullName shouldBe "int"
        local.order shouldBe 1
        call.name shouldBe Operators.assignment
        call.order shouldBe 2
        inside(call.astChildren.l) { case List(identifier: Identifier, literal: Literal) =>
          identifier.name shouldBe "local"
          identifier.typeFullName shouldBe "int"
          identifier.order shouldBe 1
          identifier.argumentIndex shouldBe 1
          literal.code shouldBe "1"
          literal.typeFullName shouldBe "int"
          literal.order shouldBe 2
          literal.argumentIndex shouldBe 2
        }
      }
    }

    "be correct for locals from decl from header file" in {
      val cpg = code(
        """
          |#include "a.h"
          |
          |int main() {
          |  printf("%d\n", global);
          |  return 0;
          |}
          |""".stripMargin,
        "main.cc"
      ).moreCode(
        """
          |int global;
          |""".stripMargin,
        "a.h"
      )
      val List(localFromHeader, localFromMain) = cpg.local.sortBy(_.lineNumber.get).l
      localFromHeader.name shouldBe "global"
      localFromHeader.code shouldBe "int global"
      localFromMain.name shouldBe "global"
      localFromMain.code shouldBe "<global> global"
    }

    "be correct for locals from decl from missing header file" in {
      val cpg = code(
        """
          |#include "a.h"
          |
          |int localId = 0;
          |
          |int main() {
          |  printf("%d\n", localId);
          |  printf("%d\n", global);
          |  printf("%d\n", unknown);
          |  return 0;
          |}
          |""".stripMargin,
        "main.cc"
      ).moreCode(
        """
          |int global;
          |""".stripMargin,
        "a.h"
      )
      val List(localFromHeader, localId, globalLocalId, localFromMain, unknown) = cpg.local.sortBy(_.lineNumber.get).l
      localFromHeader.name shouldBe "global"
      localFromHeader.code shouldBe "int global"
      localFromHeader.typeFullName shouldBe "int"

      localId.name shouldBe "localId"
      localId.code shouldBe "int localId"
      localId.typeFullName shouldBe "int"

      globalLocalId.name shouldBe "localId"
      globalLocalId.code shouldBe "<global> localId"
      globalLocalId.typeFullName shouldBe "int"

      localFromMain.name shouldBe "global"
      localFromMain.code shouldBe "<global> global"
      localFromMain.typeFullName shouldBe "int"

      unknown.name shouldBe "unknown"
      unknown.code shouldBe "<unknown> unknown"
    }

    "be correct for locals from decl in global namespace from header file" in {
      val cpg = code(
        """
          |#include "a.hpp"
          |
          |using namespace Foo;
          |
          |int main() {
          |    printf("%d\n", global);
          |    return 0;
          |}
          |""".stripMargin,
        "main.cpp"
      ).moreCode(
        """
          |namespace Foo {
          |  int global;
          |}
          |""".stripMargin,
        "a.hpp"
      )
      val List(localFromHeader, localFromMain) = cpg.local.sortBy(_.lineNumber.get).l
      localFromHeader.name shouldBe "global"
      localFromHeader.code shouldBe "int global"
      localFromMain.name shouldBe "global"
      localFromMain.code shouldBe "<global> global"
    }

    "be correct for locals from decl in global nested namespace from header file" in {
      val cpg = code(
        """
          |#include "a.hpp"
          |
          |using namespace Foo;
          |using namespace Bar;
          |
          |int main() {
          |    printf("%d\n", global);
          |    return 0;
          |}
          |""".stripMargin,
        "main.cpp"
      ).moreCode(
        """
          |namespace Foo {
          |  namespace Bar {
          |    int global;
          |  }
          |}
          |""".stripMargin,
        "a.hpp"
      )
      val List(localFromHeader, localFromMain) = cpg.local.sortBy(_.lineNumber.get).l
      localFromHeader.name shouldBe "global"
      localFromHeader.code shouldBe "int global"
      localFromMain.name shouldBe "global"
      localFromMain.code shouldBe "<global> global"
    }

    "be correct for decl assignment with parentheses" in {
      val cpg = code(
        """
          |void method() {
          |  int *val (new int[3]);
          |}
          |""".stripMargin,
        "test.cpp"
      )
      inside(cpg.method.nameExact("method").block.astChildren.isCall.l) { case List(assignment: Call) =>
        val List(identifier) = assignment.argument.isIdentifier.l
        identifier.argumentIndex shouldBe 1
        identifier.name shouldBe "val"
        val List(call) = assignment.argument.isCall.l
        call.code shouldBe "(new int[3])"
        call.argumentIndex shouldBe 2
      }
    }

    "be correct for decl assignment with typedecl" in {
      val cpg = code(
        """
       |void method() {
       |  int local = 1;
       |  constexpr bool is_std_array_v = decltype(local)::value;
       |}
       |""".stripMargin,
        "test.cpp"
      )
      inside(cpg.method.nameExact("method").block.astChildren.l) { case List(_, call1: Call, _, call2: Call) =>
        call1.name shouldBe Operators.assignment
        inside(call2.astChildren.l) { case List(identifier: Identifier, call: Call) =>
          identifier.name shouldBe "is_std_array_v"
          identifier.typeFullName shouldBe "bool"
          identifier.order shouldBe 1
          identifier.argumentIndex shouldBe 1
          call.code shouldBe "decltype(local)::value"
          call.order shouldBe 2
          call.methodFullName shouldBe Operators.fieldAccess
          call.argument(2).code shouldBe "value"
          inside(call.argument(1)) { case fa: Call =>
            fa.code shouldBe "decltype(local)"
            fa.methodFullName shouldBe "<operator>.typeOf"
            fa.argument(1).code shouldBe "local"
          }
        }
      }
    }

    "be correct for decl assignment with identifier on the right" in {
      val cpg = code("""
          |void method(int x) {
          |  int local = x;
          |}""".stripMargin)
      cpg.local.nameExact("local").order.l shouldBe List(1)
      inside(cpg.method("method").block.astChildren.assignment.source.l) { case List(identifier: Identifier) =>
        identifier.code shouldBe "x"
        identifier.typeFullName shouldBe "int"
        identifier.order shouldBe 2
        identifier.argumentIndex shouldBe 2
      }
    }

    "be correct for decl assignment with references" in {
      val cpg = code(
        """
          |int addrOfLocalRef(struct x **foo) {
          |  struct x &bar = **foo;
          |  *foo = &bar;
          |}""".stripMargin,
        "foo.cc"
      )
      val List(barLocal) = cpg.method.nameExact("addrOfLocalRef").local.l
      barLocal.name shouldBe "bar"
      barLocal.code shouldBe "struct x &bar"
    }

    "be correct for decl assignment of multiple locals" in {
      val cpg = code("""
          |void method(int x, int y) {
          |  int local = x, local2 = y;
          |}""".stripMargin)
      // Note that `cpg.method.local` does not work
      // because it depends on CONTAINS edges which
      // are created by a backend pass in semanticcpg
      // construction.
      inside(cpg.local.l.sortBy(_.order)) { case List(local1, local2) =>
        local1.name shouldBe "local"
        local1.typeFullName shouldBe "int"
        local1.order shouldBe 1
        local2.name shouldBe "local2"
        local2.typeFullName shouldBe "int"
        local2.order shouldBe 2
      }

      inside(cpg.assignment.l.sortBy(_.order)) { case List(a1, a2) =>
        a1.order shouldBe 3
        a2.order shouldBe 4
        List(a1.target.code, a1.source.code) shouldBe List("local", "x")
        List(a2.target.code, a2.source.code) shouldBe List("local2", "y")
      }
    }

    "be correct for nested expression" in {
      val cpg = code("""
        |void method() {
        |  int x;
        |  int y;
        |  int z;
        |
        |  x = y + z;
        |}
      """.stripMargin)
      val localX = cpg.local.order(1)
      localX.name.l shouldBe List("x")
      val localY = cpg.local.order(2)
      localY.name.l shouldBe List("y")
      val localZ = cpg.local.order(3)
      localZ.name.l shouldBe List("z")

      inside(cpg.method.nameExact("method").ast.isCall.nameExact(Operators.assignment).cast[OpNodes.Assignment].l) {
        case List(assignment) =>
          assignment.target.code shouldBe "x"
          assignment.source.start.isCall.name.l shouldBe List(Operators.addition)
          inside(assignment.source.astChildren.l) { case List(id1: Identifier, id2: Identifier) =>
            id1.order shouldBe 1
            id1.code shouldBe "y"
            id2.order shouldBe 2
            id2.code shouldBe "z"
          }
      }
    }

    "be correct for nested block" in {
      val cpg = code("""
        |void method() {
        |  int x;
        |  {
        |    int y;
        |  }
        |}
      """.stripMargin)
      inside(cpg.method.nameExact("method").block.astChildren.l) { case List(local: Local, innerBlock: Block) =>
        local.name shouldBe "x"
        local.order shouldBe 1
        inside(innerBlock.astChildren.l) { case List(localInBlock: Local) =>
          localInBlock.name shouldBe "y"
          localInBlock.order shouldBe 1
        }
      }
    }

    "be correct for while-loop" in {
      val cpg = code("""
        |void method(int x) {
        |  while (x < 1) {
        |    x += 1;
        |  }
        |}
      """.stripMargin)
      inside(cpg.method.nameExact("method").block.astChildren.isControlStructure.l) {
        case List(controlStruct: ControlStructure) =>
          controlStruct.code shouldBe "while (x < 1)"
          controlStruct.controlStructureType shouldBe ControlStructureTypes.WHILE
          inside(controlStruct.condition.l) { case List(cndNode) =>
            cndNode.code shouldBe "x < 1"
          }
          controlStruct.whenTrue.assignment.code.l shouldBe List("x += 1")
          controlStruct.lineNumber shouldBe Option(3)
          controlStruct.columnNumber shouldBe Option(3)
      }
    }

    "be correct for if" in {
      val cpg = code("""
        |void method(int x) {
        |  int y;
        |  if (x > 0) { y = 0; }
        |}
      """.stripMargin)
      inside(cpg.method.nameExact("method").controlStructure.l) { case List(controlStruct: ControlStructure) =>
        controlStruct.code shouldBe "if (x > 0) { y = 0; }"
        controlStruct.controlStructureType shouldBe ControlStructureTypes.IF
        inside(controlStruct.condition.l) { case List(cndNode) =>
          cndNode.code shouldBe "x > 0"

        }
        controlStruct.whenTrue.assignment.code.l shouldBe List("y = 0")
      }
    }

    "be correct for if-else" in {
      val cpg = code("""
        |void method(int x) {
        |  int y;
        |  if (x > 0) { y = 0; } else { y = 1; }
        |}
      """.stripMargin)
      inside(cpg.method.nameExact("method").controlStructure.l) { case List(ifStmt, elseStmt) =>
        ifStmt.controlStructureType shouldBe ControlStructureTypes.IF
        ifStmt.code shouldBe "if (x > 0) { y = 0; } else { y = 1; }"
        elseStmt.controlStructureType shouldBe ControlStructureTypes.ELSE
        elseStmt.code shouldBe "else"

        inside(ifStmt.condition.l) { case List(cndNode) =>
          cndNode.code shouldBe "x > 0"
        }

        ifStmt.whenTrue.assignment
          .map(x => (x.target.code, x.source.code))
          .headOption shouldBe Option(("y", "0"))
        ifStmt.whenFalse.assignment
          .map(x => (x.target.code, x.source.code))
          .headOption shouldBe Option(("y", "1"))
      }
    }

    "be correct for conditional expression in call" in {
      val cpg = code("""
         | void method() {
         |   int x = (true ? vlc_dccp_CreateFD : vlc_datagram_CreateFD)(fd);
         | }
      """.stripMargin)
      inside(cpg.method.nameExact("method").ast.isCall.nameExact(Operators.conditional).l) { case List(call) =>
        call.code shouldBe "true ? vlc_dccp_CreateFD : vlc_datagram_CreateFD"
      }
    }

    "be correct for conditional expression" in {
      val cpg = code("""
        | void method() {
        |   int x = (foo == 1) ? bar : 0;
        | }
      """.stripMargin)
      // Just like we cannot use `cpg.method.local`,
      // `cpg.method.call` will not work at this stage
      // either because there are no CONTAINS edges

      inside(cpg.method.nameExact("method").ast.isCall.nameExact(Operators.conditional).l) { case List(call) =>
        call.code shouldBe "(foo == 1) ? bar : 0"
        inside(call.argument.l) { case List(condition, trueBranch, falseBranch) =>
          condition.argumentIndex shouldBe 1
          condition.code shouldBe "foo == 1"
          trueBranch.argumentIndex shouldBe 2
          trueBranch.code shouldBe s"${Defines.UnknownTag} bar"
          falseBranch.argumentIndex shouldBe 3
          falseBranch.code shouldBe "0"
        }
      }
    }

    "be correct for ranged for-loop with structured binding with array type" in {
      val cpg = code(
        """
        |void method() {
        |  int foo[2] = {1, 2};
        |  for(const auto& [a, b] : foo) {};
        |}
        |""".stripMargin,
        "test.cpp"
      )
      inside(cpg.method.nameExact("method").controlStructure.l) { case List(forStmt) =>
        forStmt.controlStructureType shouldBe ControlStructureTypes.FOR
        forStmt.astChildren.isBlock.astChildren.isCall.code.l shouldBe List(
          "<tmp>0 = foo",
          "a = <tmp>0[0]",
          "b = <tmp>0[1]"
        )
      }
      cpg.local.map { l => (l.name, l.typeFullName) }.toMap shouldBe Map(
        "foo"    -> "int[2]",
        "<tmp>0" -> "int[2]",
        "a"      -> "ANY",
        "b"      -> "ANY"
      )
      pendingUntilFixed {
        cpg.local.map { l => (l.name, l.typeFullName) }.toMap shouldBe Map(
          "foo"    -> "int[2]",
          "<tmp>0" -> "int[2]",
          "a"      -> "int*",
          "b"      -> "int*"
        )
      }
    }

    "be correct for ranged for-loop with structured binding with reference type" in {
      val cpg = code(
        """
          |void method() {
          |  auto foo = bar();
          |  for(const auto& [a, b] : foo) {};
          |}
          |""".stripMargin,
        "test.cpp"
      )
      inside(cpg.method.nameExact("method").controlStructure.l) { case List(forStmt) =>
        forStmt.controlStructureType shouldBe ControlStructureTypes.FOR
        forStmt.astChildren.isBlock.astChildren.isCall.code.l shouldBe List(
          "<tmp>0 = foo",
          "a = <tmp>0.a",
          "b = <tmp>0.b"
        )
      }
      val List(fieldAccessCall) = cpg.call.codeExact("<tmp>0.a").l
      fieldAccessCall.name shouldBe Operators.fieldAccess
      fieldAccessCall.argument(1).isIdentifier shouldBe true
      fieldAccessCall.argument(2).isFieldIdentifier shouldBe true
    }

    "be correct for for-loop with multiple initializations" in {
      val cpg = code("""
        |void method(int x, int y) {
        |  for ( x = 0, y = 0; x < 1; x += 1) {
        |    int z = 0;
        |  }
        |}
      """.stripMargin)
      inside(cpg.method.nameExact("method").controlStructure.l) { case List(forStmt) =>
        forStmt.controlStructureType shouldBe ControlStructureTypes.FOR
        childContainsAssignments(forStmt, 1, List("x = 0", "y = 0"))

        inside(forStmt.astChildren.order(2).l) { case List(condition: Expression) =>
          condition.code shouldBe "x < 1"
        }

        forStmt.condition.l shouldBe forStmt.astChildren.order(2).l
        childContainsAssignments(forStmt, 3, List("x += 1"))
        childContainsAssignments(forStmt, 4, List("z = 0"))
      }
    }

    def childContainsAssignments(node: AstNode, i: Int, list: List[String]) = {
      inside(node.astChildren.order(i).l) { case List(child) =>
        child.assignment.code.l shouldBe list
      }
    }

    "be correct for unary expression '++'" in {
      val cpg = code("""
        |void method(int x) {
        |  ++x;
        |}
      """.stripMargin)
      cpg.method
        .nameExact("method")
        .ast
        .isCall
        .nameExact(Operators.preIncrement)
        .argument(1)
        .code
        .l shouldBe List("x")
    }

    "be correct for expression list" in {
      val cpg = code("""
        |void method(int x) {
        |  return (__sync_synchronize(), foo(x));
        |}
      """.stripMargin)
      val List(exprListBlock) = cpg.method.nameExact("method").ast.isReturn.astChildren.isBlock.l
      exprListBlock.astChildren.isCall.code.l shouldBe List("__sync_synchronize()", "foo(x)")
    }

    "not create an expression list for comma operator" in {
      val cpg = code("""
        |int something(void);
        |void a() {
        |  int b;
        |  int c;
        |  for (; b = something(), b > c;) {}
        |}
      """.stripMargin)
      val List(forLoop)        = cpg.controlStructure.l
      val List(conditionBlock) = forLoop.condition.collectAll[Block].l
      conditionBlock.order shouldBe 2
      val List(assignmentCall, greaterCall) = conditionBlock.astChildren.collectAll[Call].l
      assignmentCall.order shouldBe 1
      assignmentCall.code shouldBe "b = something()"
      greaterCall.order shouldBe 2
      greaterCall.code shouldBe "b > c"
    }

    "be correct for call expression" in {
      val cpg = code("""
        |void method(int x) {
        |  foo(x);
        |}
      """.stripMargin)
      cpg.method
        .nameExact("method")
        .ast
        .isCall
        .nameExact("foo")
        .argument(1)
        .code
        .l shouldBe List("x")
    }

    "be correct for call expression returning pointer" in {
      val cpg = code("""
        |int * foo(int arg);
        |int * method(int x) {
        |  foo(x);
        |}
      """.stripMargin)
      inside(cpg.method.nameExact("method").ast.isCall.l) { case List(call: Call) =>
        call.code shouldBe "foo(x)"
        call.dispatchType shouldBe DispatchTypes.STATIC_DISPATCH
        val rec = call.receiver.l
        rec.length shouldBe 0
        call.argument(1).code shouldBe "x"
      }
    }

    "be correct for field access" in {
      val cpg = code("""
        |void method(struct someUndefinedStruct x) {
        |  x.a;
        |}
      """.stripMargin)
      inside(cpg.method.nameExact("method").ast.isCall.nameExact(Operators.fieldAccess).l) { case List(call) =>
        val arg1 = call.argument(1)
        val arg2 = call.argument(2)
        arg1.isIdentifier shouldBe true
        arg1.argumentIndex shouldBe 1
        arg1.asInstanceOf[Identifier].name shouldBe "x"
        arg2.isFieldIdentifier shouldBe true
        arg2.argumentIndex shouldBe 2
        arg2.asInstanceOf[FieldIdentifier].code shouldBe "a"
        arg2.asInstanceOf[FieldIdentifier].canonicalName shouldBe "a"
      }
    }

    "be correct for indirect field access" in {
      val cpg = code("""
        |void method(struct someUndefinedStruct *x) {
        |  x->a;
        |}
      """.stripMargin)
      inside(cpg.method.nameExact("method").ast.isCall.nameExact(Operators.indirectFieldAccess).l) { case List(call) =>
        val arg1 = call.argument(1)
        val arg2 = call.argument(2)
        arg1.isIdentifier shouldBe true
        arg1.argumentIndex shouldBe 1
        arg1.asInstanceOf[Identifier].name shouldBe "x"
        arg2.isFieldIdentifier shouldBe true
        arg2.argumentIndex shouldBe 2
        arg2.asInstanceOf[FieldIdentifier].code shouldBe "a"
        arg2.asInstanceOf[FieldIdentifier].canonicalName shouldBe "a"
      }
    }

    "be correct for indirect field access in call" in {
      val cpg = code("""
          |void method(struct someUndefinedStruct *x) {
          |  return (x->a)(1, 2);
          |}
      """.stripMargin)
      inside(cpg.method.nameExact("method").ast.isCall.nameExact(Operators.indirectFieldAccess).l) { case List(call) =>
        val arg1 = call.argument(1)
        val arg2 = call.argument(2)
        arg1.isIdentifier shouldBe true
        arg1.argumentIndex shouldBe 1
        arg1.asInstanceOf[Identifier].name shouldBe "x"
        arg2.isFieldIdentifier shouldBe true
        arg2.argumentIndex shouldBe 2
        arg2.asInstanceOf[FieldIdentifier].code shouldBe "a"
        arg2.asInstanceOf[FieldIdentifier].canonicalName shouldBe "a"
      }
    }

    "be correct for indirection on call" in {
      val cpg = code("""
       |typedef long unsigned int (*hStrLenFunc)(const char *str);
       |int main() {
       |  hStrLenFunc strLenFunc = &strlen;
       |  return (*strLenFunc)("123");
       |}
      """.stripMargin)
      inside(cpg.method.nameExact("main").ast.isCall.codeExact("(*strLenFunc)(\"123\")").l) { case List(call) =>
        call.name shouldBe Defines.OperatorPointerCall
        call.methodFullName shouldBe Defines.OperatorPointerCall
      }
    }

    "be correct for sizeof operator on identifier with brackets" in {
      val cpg = code("""
        |void method() {
        |  int a;
        |  sizeof(a);
        |}
      """.stripMargin)
      cpg.method
        .nameExact("method")
        .ast
        .isCall
        .nameExact(Operators.sizeOf)
        .argument(1)
        .isIdentifier
        .nameExact("a")
        .argumentIndex(1)
        .size shouldBe 1
    }

    "be correct for sizeof operator on identifier without brackets" in {
      val cpg = code("""
        |void method() {
        |  int a;
        |  sizeof a ;
        |}
      """.stripMargin)
      cpg.method
        .nameExact("method")
        .ast
        .isCall
        .nameExact(Operators.sizeOf)
        .argument(1)
        .isIdentifier
        .nameExact("a")
        .argumentIndex(1)
        .size shouldBe 1
    }

    "be correct for sizeof operator on type" in {
      val cpg = code(
        """
        |void method() {
        |  sizeof(int);
        |}""".stripMargin,
        "file.cpp"
      )
      cpg.method
        .nameExact("method")
        .ast
        .isCall
        .nameExact(Operators.sizeOf)
        .argument(1)
        .isIdentifier
        .nameExact("int")
        .argumentIndex(1)
        .size shouldBe 1
    }
  }

  "Structural AST layout" should {

    "be correct for empty method" in {
      val cpg = code("""
       | void method() {
       | };
      """.stripMargin)
      cpg.method.nameExact("method").size shouldBe 1
    }

    "be correct for empty named struct" in {
      val cpg = code("""
       | struct foo {
       | };
      """.stripMargin)
      cpg.typeDecl.nameExact("foo").size shouldBe 1
    }

    "be correct for struct decl" in {
      val cpg = code("""
       | struct foo;
      """.stripMargin)
      cpg.typeDecl.nameExact("foo").size shouldBe 1
    }

    "be correct for named struct with single field" in {
      val cpg = code("""
       | struct foo {
       |   int x;
       | };
      """.stripMargin)
      cpg.typeDecl
        .nameExact("foo")
        .member
        .code("x")
        .nameExact("x")
        .typeFullName("int")
        .size shouldBe 1
    }

    "be correct for named struct with multiple fields" in {
      val cpg = code("""
        | struct foo {
        |   int x;
        |   int y;
        |   int z;
        | };
      """.stripMargin)
      cpg.typeDecl.nameExact("foo").member.code.toSetMutable shouldBe Set("x", "y", "z")
    }

    "be correct for named struct with nested struct" in {
      val cpg = code("""
        | struct foo {
        |   int x;
        |   struct bar {
        |     int y;
        |     struct foo2 {
        |       int z;
        |     };
        |   };
        | };
      """.stripMargin)
      inside(cpg.typeDecl.nameExact("foo").l) { case List(fooStruct: TypeDecl) =>
        fooStruct.member.nameExact("x").size shouldBe 1
        inside(fooStruct.astChildren.isTypeDecl.l) { case List(barStruct: TypeDecl) =>
          barStruct.member.nameExact("y").size shouldBe 1
          inside(barStruct.astChildren.isTypeDecl.l) { case List(foo2Struct: TypeDecl) =>
            foo2Struct.member.nameExact("z").size shouldBe 1
          }
        }
      }
    }

    "be correct for typedef struct" in {
      val cpg = code("""
        |typedef struct foo {
        |} abc;
      """.stripMargin)
      cpg.typeDecl.nameExact("foo").aliasTypeFullName("abc").size shouldBe 1
    }

    "be correct for anonymous typedef struct" in {
      val cpg     = code("typedef struct { int m; } t;", "t.cpp")
      val List(t) = cpg.typeDecl.nameExact("t").l
      cpg.typeDecl.nameExact("ANY").size shouldBe 0
      t.aliasTypeFullName.size shouldBe 0 // no alias for named anonymous typedefs
    }

    "be correct for struct with local" in {
      val cpg = code("""
        |struct A {
        |  int x;
        |} a;
        |struct B b;
      """.stripMargin)
      inside(cpg.typeDecl("A").member.l) { case List(x) =>
        x.name shouldBe "x"
        x.typeFullName shouldBe "int"
      }
      cpg.typeDecl.nameExact("B").size shouldBe 1
      inside(cpg.local.l) { case List(localA, localB) =>
        localA.name shouldBe "a"
        localA.typeFullName shouldBe "A"
        localA.code shouldBe "struct A { int x; } a"
        localB.name shouldBe "b"
        localB.typeFullName shouldBe "B"
        localB.code shouldBe "struct B b"
      }
    }

    "be correct for global struct" in {
      val cpg = code("""
        |struct filesystem {
        |	void (*open)(int a);
        |};
        |
        |void my_open(int a) {
        |	int b;
        |	b = a;
        |	return;
        |}
        |
        |static const struct filesystem my_fs = {
        |	.open = &my_open,
        |};
        |
        |int main(int argc, char *argv[]) {
        |	static int i;
        |	static const struct filesystem my_other_fs = {
        |		 .open = &my_open,
        |	};
        |	struct filesystem real_fs;
        |	real_fs.open = &my_open;
        |	i = 0;
        |}
      """.stripMargin)
      val List(localMyOtherFs) = cpg.method("main").local.nameExact("my_other_fs").l
      localMyOtherFs.order shouldBe 2
      localMyOtherFs.referencingIdentifiers.name.l shouldBe List("my_other_fs")
      val List(localMyFs) = cpg.local.nameExact("my_fs").l
      localMyFs.order shouldBe 4
      localMyFs.referencingIdentifiers.name.l shouldBe List("my_fs")
      cpg.typeDecl.nameNot(NamespaceTraversal.globalNamespaceName).fullName.l.distinct shouldBe List(
        "filesystem",
        "my_open",
        "main"
      )
    }

    "be correct for typedef enum" in {
      val cpg = code("""
        |typedef enum foo {
        |} abc;
      """.stripMargin)
      cpg.typeDecl.nameExact("foo").aliasTypeFullName("abc").size shouldBe 1
    }

    "be correct for classes with friends" in {
      val cpg = code(
        """
        |class Bar {};
        |class Foo {
        |  friend Bar;
        |};
      """.stripMargin,
        "test.cpp"
      )
      inside(cpg.typeDecl("Foo").astChildren.isTypeDecl.l) { case List(bar) =>
        bar.name shouldBe "Bar"
        bar.fullName shouldBe "Foo.Bar"
        bar.aliasTypeFullName shouldBe Option("Bar")
      }
    }

    "be correct for single inheritance" in {
      val cpg = code(
        """
        |class Base {public: int i;};
        |class Derived : public Base{
        |public:
        | char x;
        | int method(){return i;};
        |};
      """.stripMargin,
        "file.cpp"
      )
      cpg.typeDecl
        .nameExact("Derived")
        .count(_.inheritsFromTypeFullName == List("Base")) shouldBe 1
    }

    "be correct for type initializer expression" in {
      val cpg = code(
        """
        |int x = (int){ 1 };
      """.stripMargin,
        "file.cpp"
      )
      inside(cpg.call.nameExact(Operators.cast).l) { case List(call: Call) =>
        call.argument(2).code shouldBe "{ 1 }"
        val List(typeRef) = call.argument(1).isTypeRef.l
        typeRef.typeFullName shouldBe "int"
        typeRef.code shouldBe "int"
      }
    }

    "be correct for static assert" in {
      val cpg = code(
        """
        |void foo(){
        | int a = 0;
        | static_assert ( a == 0 , "not 0!");
        |}
      """.stripMargin,
        "file.cpp"
      )
      inside(cpg.call.codeExact("static_assert ( a == 0 , \"not 0!\");").l) { case List(call: Call) =>
        call.name shouldBe "<operator>.staticAssert"
        call.argument(1).code shouldBe "a == 0"
        call.argument(2).code shouldBe "\"not 0!\""
      }
    }

    "be correct for try catch" in {
      val cpg = code(
        """
        |void bar();
        |int foo(){
        | try { bar(); } 
        | catch(Foo x) { return 0; };
        |}
      """.stripMargin,
        "file.cpp"
      )
      inside(cpg.controlStructure.isTry.l) { case List(t) =>
        val List(tryBlock) = t.astChildren.isBlock.l
        tryBlock.order shouldBe 1
        tryBlock.astChildren.isCall.order(1).code.l shouldBe List("bar()")
        val List(catchX) = t.astChildren.isControlStructure.isCatch.l
        catchX.order shouldBe 2
        catchX.ast.isReturn.code.l shouldBe List("return 0;")
        catchX.ast.isLocal.code.l shouldBe List("Foo x")
      }
    }

    "be correct for try with multiple catches" in {
      val cpg: Cpg = code(
        """
          |int main() {
          |  try {
          |    a;
          |  } catch (short x) {
          |    b;
          |  } catch (int y) {
          |    c;
          |  } catch (long z) {
          |    d;
          |  }
          |}
          |""".stripMargin,
        "file.cpp"
      )
      inside(cpg.controlStructure.isTry.l) { case List(t) =>
        val List(tryBlock) = t.astChildren.isBlock.l
        tryBlock.order shouldBe 1
        tryBlock.astChildren.isIdentifier.order(1).code.l shouldBe List(s"${Defines.UnknownTag} a")
        val List(catchX, catchY, catchZ) = t.astChildren.isControlStructure.isCatch.l
        catchX.order shouldBe 2
        catchX.ast.isIdentifier.code.l shouldBe List(s"${Defines.UnknownTag} b")
        catchX.ast.isLocal.code.l shouldBe List("short x")
        catchY.order shouldBe 3
        catchY.ast.isIdentifier.code.l shouldBe List(s"${Defines.UnknownTag} c")
        catchY.ast.isLocal.code.l shouldBe List("int y")
        catchZ.order shouldBe 4
        catchZ.ast.isIdentifier.code.l shouldBe List(s"${Defines.UnknownTag} d")
        catchZ.ast.isLocal.code.l shouldBe List("long z")
      }
    }

    "be correct for try with multiple catches and broken catch clause" in {
      val cpg: Cpg = code(
        """
          |int main() {
          |  try {}
          |  catch (int a) {}
          |  catch (...) {}
          |}
          |""".stripMargin,
        "file.cpp"
      )
      inside(cpg.controlStructure.isTry.l) { case List(t) =>
        val List(tryBlock) = t.astChildren.isBlock.l
        tryBlock.order shouldBe 1
        tryBlock.astChildren shouldBe empty
        val List(catchA, catchB) = t.astChildren.isControlStructure.isCatch.l
        catchA.order shouldBe 2
        catchA.ast.isBlock.astChildren shouldBe empty
        catchA.ast.isLocal.name.l shouldBe List("a")
        catchB.order shouldBe 3
        catchB.ast.isBlock.astChildren shouldBe empty
        catchB.ast.isLocal shouldBe empty
      }
    }

    "be correct for constructor initializer" in {
      val cpg = code(
        """
        |class Foo {
        |public:
        |  Foo(int i){};
        |  class Bar {
        |    public:
        |      Bar(float j){};
        |  };
        |};
        |Foo f1(0);
        |Foo::Bar f2(0.0f);
      """.stripMargin,
        "file.cpp"
      )
      val List(fooTypeDecl, barTypeDecl) =
        cpg.typeDecl.nameNot(NamespaceTraversal.globalNamespaceName).sortBy(_.lineNumber).l
      fooTypeDecl.name shouldBe "Foo"
      barTypeDecl.name shouldBe "Bar"

      barTypeDecl.astParent shouldBe fooTypeDecl

      val List(fooConstructor) = fooTypeDecl.method.isConstructor.l
      fooConstructor.fullName shouldBe "Foo.Foo:void(int)"

      val List(barConstructor) = barTypeDecl.method.isConstructor.l
      barConstructor.fullName shouldBe "Foo.Bar.Bar:void(float)"

      cpg.call.codeExact("f1 = Foo.Foo(0)").name.l shouldBe List(Operators.assignment)
      cpg.call.codeExact("f2 = Foo.Bar.Bar(0.0f)").name.l shouldBe List(Operators.assignment)

      cpg.call.codeExact("Foo.Foo(0)").methodFullName.l shouldBe List("Foo.Foo:void(int)")
      cpg.call.codeExact("Foo.Bar.Bar(0.0f)").methodFullName.l shouldBe List("Foo.Bar.Bar:void(float)")
    }

    "be correct for template class" in {
      val cpg = code(
        """
        | template<class T>
        | class Y
        | {
        |   void mf() { }
        | };
        | template class Y<char*>;
        | template void Y<double>::mf();
      """.stripMargin,
        "file.cpp"
      )
      cpg.typeDecl
        .nameExact("Y")
        .l
        .size shouldBe 1
    }

    "be correct for template function" in {
      val cpg = code(
        """
        | template<typename T>
        | void f(T s)
        | { }
        |
        | template void f<double>(double); // instantiates f<double>(double)
        | template void f<>(char); // instantiates f<char>(char), template argument deduced
        | template void f(int); // instantiates f<int>(int), template argument deduced
      """.stripMargin,
        "file.cpp"
      )
      cpg.method
        .nameExact("f")
        .l
        .size shouldBe 1
    }

    "be correct for constructor expression" in {
      val cpg = code(
        """
        |class Foo {
        |public:
        | Foo(int i) {  };
        |};
        |Foo x = Foo{0};
      """.stripMargin,
        "file.cpp"
      )
      val List(fooTypeDecl) =
        cpg.typeDecl.nameNot(NamespaceTraversal.globalNamespaceName).sortBy(_.lineNumber).l
      fooTypeDecl.name shouldBe "Foo"
      val List(fooConstructor) = fooTypeDecl.method.isConstructor.l
      fooConstructor.fullName shouldBe "Foo.Foo:void(int)"
      cpg.call.codeExact("x = Foo{0}").name.l shouldBe List(Operators.assignment)
      cpg.call.codeExact("Foo{0}").methodFullName.l shouldBe List("Foo.Foo:void(int)")
      cpg.call.codeExact("Foo{0}").argument.code.l shouldBe List("&<tmp>0", "0")
    }

    "be correct for method calls" in {
      val cpg = code("""
        |void foo(int x) {
        |  bar(x);
        |}
        |""".stripMargin)
      cpg.method
        .nameExact("foo")
        .ast
        .isCall
        .nameExact("bar")
        .argument
        .code("x")
        .size shouldBe 1
    }

    "be correct for method returns" in {
      val cpg = code("""
        |int d(int x) {
        |  return x * 2;
        |}
        |""".stripMargin)
      // TODO no step class defined for `Return` nodes
      cpg.method.nameExact("d").ast.isReturn.astChildren.order(1).isCall.code.l shouldBe List("x * 2")
      cpg.method
        .nameExact("d")
        .ast
        .isReturn
        .out(EdgeTypes.ARGUMENT)
        .head
        .asInstanceOf[Call]
        .code shouldBe "x * 2"
    }

    "be correct for binary method calls" in {
      val cpg = code("""
        |int d(int x) {
        |  return x * 2;
        |}
        |""".stripMargin)
      cpg.call.nameExact(Operators.multiplication).code.l shouldBe List("x * 2")
    }

    "be correct for unary method calls" in {
      val cpg = code("""
        |bool invert(bool b) {
        |  return !b;
        |}
        |""".stripMargin)
      cpg.call.nameExact(Operators.logicalNot).argument(1).code.l shouldBe List("b")
    }

    "be correct for unary expr" in {
      val cpg = code("""
        |int strnlen (const char *str, int max)
        |    {
        |      const char *end = memchr(str, 0, max);
        |      return end ? (int)(end - str) : max;
        |    }
        |""".stripMargin)
      inside(cpg.call.nameExact(Operators.cast).astChildren.l) { case List(tpe: TypeRef, call: Call) =>
        call.code shouldBe "end - str"
        call.argumentIndex shouldBe 2
        tpe.code shouldBe "int"
        tpe.typeFullName shouldBe "int"
        tpe.argumentIndex shouldBe 1
      }
    }

    "be correct for post increment method calls" in {
      val cpg = code("""
        |int foo(int x) {
        |  int sub = x--;
        |  int pos = x++;
        |  return pos;
        |}
        |""".stripMargin)
      cpg.call.nameExact(Operators.postIncrement).argument(1).code("x").size shouldBe 1
      cpg.call.nameExact(Operators.postDecrement).argument(1).code("x").size shouldBe 1
    }

    "be correct for conditional expressions containing calls" in {
      val cpg = code("""
        |int abs(int x) {
        |  return x > 0 ? x : -x;
        |}
        |""".stripMargin)
      cpg.call.nameExact(Operators.conditional).argument.code.l shouldBe List("x > 0", "x", "-x")
    }

    "be correct for sizeof expressions" in {
      val cpg = code("""
        |size_t int_size() {
        |  return sizeof(int);
        |}
        |""".stripMargin)
      inside(cpg.call.nameExact(Operators.sizeOf).argument(1).l) { case List(i: Identifier) =>
        i.code shouldBe "int"
        i.name shouldBe "int"
      }
    }

    "be correct for label" in {
      val cpg = code("void foo() { label:; }")
      cpg.jumpTarget.code("label:;").size shouldBe 1
    }

    "be correct for array indexing" in {
      val cpg = code("""
        |int head(int x[]) {
        |  return x[0];
        |}
        |""".stripMargin)
      cpg.call.nameExact(Operators.indirectIndexAccess).argument.code.l shouldBe List("x", "0")
    }

    "be correct for type casts" in {
      val cpg = code(
        """
        |namespace A {
        |  class Foo {}
        |}
        |namespace B {
        |  class Bar {}
        |}
        |
        |using namespace A;
        |using namespace B;
        |
        |Bar cast(Foo f) {
        |  return (Bar) f;
        |}
        |""".stripMargin,
        "file.cpp"
      )
      val List(cast)    = cpg.call.nameExact(Operators.cast).l
      val List(typeRef) = cast.argument(1).isTypeRef.l
      typeRef.code shouldBe "Bar"
      typeRef.typeFullName shouldBe "B.Bar"
      val List(id) = cast.argument(2).start.isIdentifier.l
      id.name shouldBe "f"
      id.typeFullName shouldBe "A.Foo"
    }

    "be correct for 'new' array" in {
      val cpg = code(
        """
        |int * alloc(int n) {
        |   int * arr = new int[n];
        |   return arr;
        |}
        |""".stripMargin,
        "file.cpp"
      )
      val List(newCall)         = cpg.call.methodFullNameExact(Defines.OperatorNew).l
      val List(constructorCall) = newCall.argument.isCall.l
      constructorCall.code shouldBe "new int[n]"
      constructorCall.methodFullName shouldBe Operators.alloc
      constructorCall.typeFullName shouldBe Defines.Any
      constructorCall.argument.code.l shouldBe List("int", "n")
    }

    "be correct for 'placement new'" in {
      val cpg = code(
        """
        |void a() {
        |  char buf[80];
        |  new (buf) Foo("hi");
        |}
        |""".stripMargin,
        "file.cpp"
      )
      val List(newCall)          = cpg.call.methodFullNameExact(Defines.OperatorNew).l
      val List(constructorBlock) = newCall.argument.isBlock.l
      constructorBlock.argumentIndex shouldBe 1
      val List(allocAssignment, constructorCall) = constructorBlock.astChildren.isCall.l
      allocAssignment.code shouldBe "<tmp>0 = <operator>.alloc"
      constructorCall.code shouldBe """new (buf) Foo("hi")"""
      constructorCall.methodFullName shouldBe "Foo.Foo:void(char[3])"
      constructorCall.signature shouldBe "void(char[3])"
      constructorCall.typeFullName shouldBe Defines.Void
      constructorCall.argument.code.l shouldBe List("&<tmp>0", """"hi"""")
      val List(buf) = newCall.argument.isIdentifier.l
      buf.argumentIndex shouldBe 2
      buf.typeFullName shouldBe "char[80]"
    }

    "be correct for externally defined constructor" in {
      val cpg = code(
        """
          |class Foo {
          |  public:
          |    Foo(int i) {};
          |};
          |
          |Foo::Foo(int i, int j) {};
          |Bar::Bar(float x) {};
          |
          |void method() {
          |   Foo f1(0);
          |   Foo f2(0, 1);
          |   Bar b1(0.0f);
          |
          |   Foo f3 = new Foo(0);
          |   Foo f4 = new Foo(0, 1);
          |   Bar b2 = new Bar(0.0f);
          |}
          |""".stripMargin,
        "file.cpp"
      )
      val List(fileGlobalMethod) = cpg.method.fullNameExact("file.cpp:<global>").l
      val List(fooTypeDecl)      = cpg.typeDecl.fullNameExact("Foo").l
      val List(c1, c2, c3)       = cpg.method.isConstructor.l
      c1.fullName shouldBe "Foo.Foo:void(int)"
      c2.fullName shouldBe "Foo.Foo:void(int,int)"
      c3.fullName shouldBe "Bar.Bar:void(float)"
      c1.astIn.l shouldBe List(fooTypeDecl)
      c2.astIn.l shouldBe List(fooTypeDecl)
      c3.astIn.l shouldBe List(fileGlobalMethod)

      val constructorCallElements = cpg.method.nameExact("method").block.astChildren.isCall.isAssignment.argument(2).l
      val List(f1Block, f2Block, b1Block) = constructorCallElements.isBlock.l
      val List(f3Call, f4Call, b2Call)    = constructorCallElements.isCall.l

      f3Call.methodFullName shouldBe Defines.OperatorNew
      f4Call.methodFullName shouldBe Defines.OperatorNew
      b2Call.methodFullName shouldBe Defines.OperatorNew

      val List(f1ConstructorCall) = f1Block.astChildren.isCall.nameNot(Operators.assignment).l
      f1ConstructorCall.methodFullName shouldBe "Foo.Foo:void(int)"
      val List(f2ConstructorCall) = f2Block.astChildren.isCall.nameNot(Operators.assignment).l
      f2ConstructorCall.methodFullName shouldBe "Foo.Foo:void(int,int)"
      val List(b1ConstructorCall) = b1Block.astChildren.isCall.nameNot(Operators.assignment).l
      b1ConstructorCall.methodFullName shouldBe "Bar.Bar:void(float)"

      val List(f3ConstructorCall) = f3Call.argument.isBlock.astChildren.isCall.nameNot(Operators.assignment).l
      f3ConstructorCall.methodFullName shouldBe "Foo.Foo:void(int)"
      val List(f4ConstructorCall) = f4Call.argument.isBlock.astChildren.isCall.nameNot(Operators.assignment).l
      f4ConstructorCall.methodFullName shouldBe "Foo.Foo:void(int,int)"
      val List(b2ConstructorCall) = b2Call.argument.isBlock.astChildren.isCall.nameNot(Operators.assignment).l
      b2ConstructorCall.methodFullName shouldBe "Bar.Bar:void(float)"
    }

    "be correct for externally defined constructor with class in different file" in {
      val cpg = code(
        """
          |Bar::Bar(float x) {};
          |
          |void method() {
          |   Bar b1(0.0f);
          |   Bar b2 = new Bar(0.0f);
          |}
          |""".stripMargin,
        "file.cpp"
      ).moreCode("class Bar {};", "Bar.cpp")
      val List(barTypeDecl) = cpg.typeDecl.fullNameExact("Bar").l
      val List(c)           = cpg.method.isConstructor.l
      c.fullName shouldBe "Bar.Bar:void(float)"
      c.astIn.l shouldBe List(barTypeDecl)

      val constructorElements = cpg.method.nameExact("method").block.astChildren.isCall.argument(2).l

      val List(b1ConstructorCall) = constructorElements.isBlock.astChildren.isCall.nameNot(Operators.assignment).l
      b1ConstructorCall.methodFullName shouldBe "Bar.Bar:void(float)"

      val List(b2ConstructorCall) = constructorElements.isCall
        .nameExact(Defines.OperatorNew)
        .astChildren
        .isBlock
        .astChildren
        .isCall
        .nameNot(Operators.assignment)
        .l
      b2ConstructorCall.methodFullName shouldBe "Bar.Bar:void(float)"
    }

    // for: https://github.com/ShiftLeftSecurity/codepropertygraph/issues/1526
    "be correct for array size" in {
      val cpg = code("""
        |int main() {
        |  char bufA[256];
        |  char bufB[1+2];
        |}
        |""".stripMargin)
      inside(cpg.call.nameExact(Operators.assignment).l) { case List(bufCallAAssign: Call, bufCallBAssign: Call) =>
        val List(bufAId, bufCallA) = bufCallAAssign.argument.l
        bufAId.code shouldBe "bufA"
        val List(bufBId, bufCallB) = bufCallBAssign.argument.l
        bufBId.code shouldBe "bufB"

        inside(cpg.call.nameExact(Operators.alloc).l) { case List(bufCallAAlloc: Call, bufCallBAlloc: Call) =>
          bufCallAAlloc shouldBe bufCallA
          bufCallBAlloc shouldBe bufCallB

          bufCallAAlloc.code shouldBe "bufA[256]"
          bufCallAAlloc.typeFullName shouldBe "char[256]"
          val List(argA) = bufCallAAlloc.argument.isLiteral.l
          argA.code shouldBe "256"

          bufCallBAlloc.code shouldBe "bufB[1+2]"
          bufCallBAlloc.typeFullName shouldBe "char[1+2]"
          val List(argB) = bufCallBAlloc.argument.isCall.l
          argB.name shouldBe Operators.addition
          argB.code shouldBe "1+2"
          val List(one, two) = argB.argument.isLiteral.l
          one.code shouldBe "1"
          two.code shouldBe "2"
        }
      }

      inside(cpg.local.l) { case List(bufA: Local, bufB: Local) =>
        bufA.typeFullName shouldBe "char[256]"
        bufA.name shouldBe "bufA"
        bufA.code shouldBe "char bufA[256]"

        bufB.typeFullName shouldBe "char[1+2]"
        bufB.name shouldBe "bufB"
        bufB.code shouldBe "char bufB[1+2]"
      }
    }

    "be correct for empty array init" in {
      val cpg = code("""
        |void other(void) {
        |  int i = 0;
        |  char str[] = "abc";
        |  printf("%d %s", i, str);
        |}
        |""".stripMargin)
      val List(str1, str2) = cpg.identifier.nameExact("str").l
      str1.typeFullName shouldBe "char[]"
      str2.typeFullName shouldBe "char[]"
      cpg.call.nameExact(Operators.alloc) shouldBe empty
    }

    "be correct for array init" in {
      val cpg = code("""
        |int x[] = {0, 1, 2, 3};
        |""".stripMargin)
      inside(cpg.assignment.astChildren.l) { case List(ident: Identifier, call: Call) =>
        ident.typeFullName shouldBe "int[]"
        ident.order shouldBe 1
        call.code shouldBe "{0, 1, 2, 3}"
        call.order shouldBe 2
        call.name shouldBe Operators.arrayInitializer
        call.methodFullName shouldBe Operators.arrayInitializer
        val children = call.astChildren.l
        val args     = call.argument.l
        inside(children) { case List(literalA: Literal, literalB: Literal, literalC: Literal, literalD: Literal) =>
          literalA.order shouldBe 1
          literalA.code shouldBe "0"
          literalB.order shouldBe 2
          literalB.code shouldBe "1"
          literalC.order shouldBe 3
          literalC.code shouldBe "2"
          literalD.order shouldBe 4
          literalD.code shouldBe "3"
        }
        children shouldBe args
      }
    }

    "be correct for static array init" in {
      val cpg = code("""
        |static int x[] = {0, 1, 2, 3};
        |""".stripMargin)
      inside(cpg.assignment.astChildren.l) { case List(ident: Identifier, call: Call) =>
        ident.typeFullName shouldBe "int[]"
        ident.order shouldBe 1
        call.code shouldBe "{0, 1, 2, 3}"
        call.order shouldBe 2
        call.name shouldBe Operators.arrayInitializer
        call.methodFullName shouldBe Operators.arrayInitializer
        val children = call.astChildren.l
        val args     = call.argument.l
        inside(children) { case List(literalA: Literal, literalB: Literal, literalC: Literal, literalD: Literal) =>
          literalA.order shouldBe 1
          literalA.code shouldBe "0"
          literalB.order shouldBe 2
          literalB.code shouldBe "1"
          literalC.order shouldBe 3
          literalC.code shouldBe "2"
          literalD.order shouldBe 4
          literalD.code shouldBe "3"
        }
        children shouldBe args
      }
    }

    "be correct for const array init" in {
      val cpg = code("""
        |const int x[] = {0, 1, 2, 3};
        |""".stripMargin)
      inside(cpg.assignment.astChildren.l) { case List(ident: Identifier, call: Call) =>
        ident.typeFullName shouldBe "int[]"
        ident.order shouldBe 1
        call.code shouldBe "{0, 1, 2, 3}"
        call.order shouldBe 2
        call.name shouldBe Operators.arrayInitializer
        call.methodFullName shouldBe Operators.arrayInitializer
        val children = call.astChildren.l
        val args     = call.argument.l
        inside(children) { case List(literalA: Literal, literalB: Literal, literalC: Literal, literalD: Literal) =>
          literalA.order shouldBe 1
          literalA.code shouldBe "0"
          literalB.order shouldBe 2
          literalB.code shouldBe "1"
          literalC.order shouldBe 3
          literalC.code shouldBe "2"
          literalD.order shouldBe 4
          literalD.code shouldBe "3"
        }
        children shouldBe args
      }
    }

    "be correct for static const array init" in {
      val cpg = code("""
        |static const int x[] = {0, 1, 2, 3};
        |""".stripMargin)
      inside(cpg.assignment.astChildren.l) { case List(ident: Identifier, call: Call) =>
        ident.typeFullName shouldBe "int[]"
        ident.order shouldBe 1
        call.code shouldBe "{0, 1, 2, 3}"
        call.order shouldBe 2
        call.name shouldBe Operators.arrayInitializer
        call.methodFullName shouldBe Operators.arrayInitializer
        val children = call.astChildren.l
        val args     = call.argument.l
        inside(children) { case List(literalA: Literal, literalB: Literal, literalC: Literal, literalD: Literal) =>
          literalA.order shouldBe 1
          literalA.code shouldBe "0"
          literalB.order shouldBe 2
          literalB.code shouldBe "1"
          literalC.order shouldBe 3
          literalC.code shouldBe "2"
          literalD.order shouldBe 4
          literalD.code shouldBe "3"
        }
        children shouldBe args
      }
    }

    "be correct for array init with method refs" in {
      val cpg = code("""
        |static void methodA() { return; };
        |static int methodB() { return 0; };
        |static const struct foo bar = {
        | .a = methodA,
        | .b = methodB,
        |};""".stripMargin)
      val List(methodA, methodB) = cpg.method.nameNot("<global>").l
      inside(cpg.call.nameExact(Operators.arrayInitializer).assignment.l) { case List(callA: Call, callB: Call) =>
        val argsAIdent = callA.argument(1).asInstanceOf[Identifier]
        val argARef    = callA.argument(2).asInstanceOf[MethodRef]
        argsAIdent.order shouldBe 1
        argsAIdent.name shouldBe "a"
        argsAIdent.code shouldBe "a"
        argARef.order shouldBe 2
        argARef.methodFullName shouldBe methodA.fullName
        argARef.typeFullName shouldBe methodA.methodReturn.typeFullName
        val argsBIdent = callB.argument(1).asInstanceOf[Identifier]
        val argBRef    = callB.argument(2).asInstanceOf[MethodRef]
        argsBIdent.order shouldBe 1
        argsBIdent.code shouldBe "b"
        argsBIdent.name shouldBe "b"
        argBRef.order shouldBe 2
        argBRef.methodFullName shouldBe methodB.fullName
        argBRef.typeFullName shouldBe methodB.methodReturn.typeFullName
      }
    }

    "be correct for method refs from function pointers" in {
      val cpg = code("""
          |uid_t getuid(void);
          |void someFunction() {}
          |void checkFunctionPointerComparison() {
          |  if (getuid == 0 || someFunction == 0) {}
          |}
          |""".stripMargin)
      val List(methodA) = cpg.method.fullNameExact("getuid").l
      val List(methodB) = cpg.method.fullNameExact("someFunction").l
      cpg.method.fullNameExact("checkFunctionPointerComparison").size shouldBe 1
      inside(cpg.call.nameExact(Operators.equals).l) { case List(callA: Call, callB: Call) =>
        val getuidRef = callA.argument(1).asInstanceOf[MethodRef]
        getuidRef.methodFullName shouldBe methodA.fullName
        getuidRef.typeFullName shouldBe methodA.methodReturn.typeFullName
        val someFunctionRef = callB.argument(1).asInstanceOf[MethodRef]
        someFunctionRef.methodFullName shouldBe methodB.fullName
        someFunctionRef.typeFullName shouldBe methodB.methodReturn.typeFullName
      }
    }

    "be correct for locals for array init" in {
      val cpg = code("""
        |bool x[2] = { TRUE, FALSE };
        |""".stripMargin)
      inside(cpg.local.nameExact("x").l) { case List(x) =>
        x.typeFullName shouldBe "bool[2]"
      }
    }

    "be correct for array init without actual assignment" in {
      val cpg = code(
        """
        |int foo{1};
        |int bar[]{0, 1, 2};
        |""".stripMargin,
        "test.cpp"
      )
      val List(localFoo, localBar) = cpg.local.l
      localFoo.name shouldBe "foo"
      localFoo.order shouldBe 1
      localBar.name shouldBe "bar"
      localBar.order shouldBe 3

      val List(assignment1, assignment2) = cpg.assignment.l
      assignment1.order shouldBe 2
      assignment1.code shouldBe "foo{1}"
      assignment1.name shouldBe Operators.assignment
      assignment1.methodFullName shouldBe Operators.assignment
      assignment2.order shouldBe 4
      assignment2.code shouldBe "bar[]{0, 1, 2}"
      assignment2.name shouldBe Operators.assignment
      assignment2.methodFullName shouldBe Operators.assignment

      inside(cpg.assignment.astChildren.l) {
        case List(identFoo: Identifier, identBar: Identifier, callFoo: Call, barCall: Call) =>
          identFoo.typeFullName shouldBe "int"
          identFoo.order shouldBe 1
          callFoo.code shouldBe "{1}"
          callFoo.order shouldBe 2
          callFoo.name shouldBe Operators.arrayInitializer
          callFoo.methodFullName shouldBe Operators.arrayInitializer
          val childrenFoo = callFoo.astChildren.l
          val argsFoo     = callFoo.argument.l
          inside(childrenFoo) { case List(literal: Literal) =>
            literal.order shouldBe 1
            literal.code shouldBe "1"
          }
          childrenFoo shouldBe argsFoo

          identBar.typeFullName shouldBe "int[3]"
          identBar.order shouldBe 1
          barCall.code shouldBe "{0, 1, 2}"
          barCall.order shouldBe 2
          barCall.name shouldBe Operators.arrayInitializer
          barCall.methodFullName shouldBe Operators.arrayInitializer
          val childrenBar = barCall.astChildren.l
          val argsBar     = barCall.argument.l
          inside(childrenBar) { case List(literalA: Literal, literalB: Literal, literalC: Literal) =>
            literalA.order shouldBe 1
            literalA.code shouldBe "0"
            literalB.order shouldBe 2
            literalB.code shouldBe "1"
            literalC.order shouldBe 3
            literalC.code shouldBe "2"
          }
          childrenBar shouldBe argsBar
      }
    }

    "be correct for 'new' object" in {
      val cpg = code(
        """
        |class Foo {
        |  public:
        |    Foo(int i, int j) {};
        |}
        |Foo* alloc(int n) {
        |   Foo* foo = new Foo(n, 42);
        |   return foo;
        |}
        |""".stripMargin,
        "file.cpp"
      )
      val List(constructorCall) =
        cpg.call.methodFullName(Defines.OperatorNew).argument.isBlock.astChildren.isCall.codeExact("new Foo(n, 42)").l
      constructorCall.methodFullName shouldBe "Foo.Foo:void(int,int)"
      constructorCall.signature shouldBe "void(int,int)"
      constructorCall.typeFullName shouldBe Defines.Void
      constructorCall.argument.code.l shouldBe List("&<tmp>0", "n", "42")
      cpg.typeDecl.nameExact("Foo").astChildren.isMethod.isConstructor.fullName.l shouldBe List("Foo.Foo:void(int,int)")
    }

    "be correct for simple 'delete'" in {
      val cpg = code(
        """
        |int delete_number(int* n) {
        |  delete n;
        |}
        |""".stripMargin,
        "file.cpp"
      )
      cpg.call.nameExact(Operators.delete).code("delete n").argument.code("n").size shouldBe 1
    }

    "be correct for array 'delete'" in {
      val cpg = code(
        """
        |void delete_number(int n[]) {
        |  delete[] n;
        |}
        |""".stripMargin,
        "file.cpp"
      )
      cpg.call.nameExact(Operators.delete).codeExact("delete[] n").argument.code("n").size shouldBe 1
    }

    "be correct for const_cast" in {
      val cpg = code(
        """
        |void foo(float n) {
        |  int y = const_cast<int>(n);
        |  return;
        |}
        |""".stripMargin,
        "file.cpp"
      )
      val List(cast) = cpg.call.nameExact(Operators.cast).l
      cast.code shouldBe "const_cast<int>(n)"
      val List(typeRef) = cast.argument(1).isTypeRef.l
      typeRef.code shouldBe "int"
      typeRef.typeFullName shouldBe "int"
      val List(id) = cast.argument(2).start.isIdentifier.l
      id.name shouldBe "n"
      id.typeFullName shouldBe "float"
    }

    "be correct for static_cast" in {
      val cpg = code(
        """
        |void foo(float n) {
        |  int y = static_cast<int>(n);
        |  return;
        |}
        |""".stripMargin,
        "file.cpp"
      )
      val List(cast) = cpg.call.nameExact(Operators.cast).l
      cast.code shouldBe "static_cast<int>(n)"
      val List(typeRef) = cast.argument(1).isTypeRef.l
      typeRef.code shouldBe "int"
      typeRef.typeFullName shouldBe "int"
      val List(id) = cast.argument(2).start.isIdentifier.l
      id.name shouldBe "n"
      id.typeFullName shouldBe "float"
    }

    "be correct for dynamic_cast" in {
      val cpg = code(
        """
        |void foo(float n) {
        |  int y = dynamic_cast<int>(n);
        |  return;
        |}
        |""".stripMargin,
        "file.cpp"
      )
      val List(cast) = cpg.call.nameExact(Operators.cast).l
      cast.code shouldBe "dynamic_cast<int>(n)"
      val List(typeRef) = cast.argument(1).isTypeRef.l
      typeRef.code shouldBe "int"
      typeRef.typeFullName shouldBe "int"
      val List(id) = cast.argument(2).start.isIdentifier.l
      id.name shouldBe "n"
      id.typeFullName shouldBe "float"
    }

    "be correct for reinterpret_cast" in {
      val cpg = code(
        """
        |void foo(float n) {
        |  int y = reinterpret_cast<int>(n);
        |  return;
        |}
        |""".stripMargin,
        "file.cpp"
      )
      val List(cast) = cpg.call.nameExact(Operators.cast).l
      cast.code shouldBe "reinterpret_cast<int>(n)"
      val List(typeRef) = cast.argument(1).isTypeRef.l
      typeRef.code shouldBe "int"
      typeRef.typeFullName shouldBe "int"
      val List(id) = cast.argument(2).start.isIdentifier.l
      id.name shouldBe "n"
      id.typeFullName shouldBe "float"
    }

    "be correct for designated initializers in plain C" in {
      val cpg = code("""
        |void foo() {
        |  int a[3] = { [1] = 5, [2] = 10, [3 ... 9] = 15 };
        |};
      """.stripMargin)
      inside(cpg.assignment.l(1).astChildren.l) { case List(ident: Identifier, call: Call) =>
        ident.typeFullName shouldBe "int[3]"
        ident.order shouldBe 1
        call.code shouldBe "{ [1] = 5, [2] = 10, [3 ... 9] = 15 }"
        call.order shouldBe 2
        call.name shouldBe Operators.arrayInitializer
        call.methodFullName shouldBe Operators.arrayInitializer
        val children = call.astMinusRoot.isCall.nameExact(Operators.assignment).l
        val args     = call.argument.astChildren.l
        inside(children) { case List(call1, call2, call3) =>
          call1.code shouldBe "[1] = 5"
          call1.name shouldBe Operators.assignment
          call1.astMinusRoot.code.l shouldBe List("1", "5")
          call1.argument.code.l shouldBe List("1", "5")
          call2.code shouldBe "[2] = 10"
          call2.name shouldBe Operators.assignment
          call2.astMinusRoot.code.l shouldBe List("2", "10")
          call2.argument.code.l shouldBe List("2", "10")
          call3.code shouldBe "[3 ... 9] = 15"
          call3.name shouldBe Operators.assignment
          val List(desCall) = call3.argument(1).start.collectAll[Call].l
          val List(value)   = call3.argument(2).start.collectAll[Literal].l
          value.code shouldBe "15"
          desCall.name shouldBe Operators.arrayInitializer
          desCall.code shouldBe "[3 ... 9]"
          desCall.argument.code.l shouldBe List("3", "9")
        }
        children shouldBe args
      }
    }

    "be correct for designated initializers in C++" in {
      val cpg = code(
        """
        |void foo() {
        |  int a[3] = { [1] = 5, [2] = 10, [3 ... 9] = 15 };
        |};
      """.stripMargin,
        "test.cpp"
      )
      inside(cpg.assignment.l(1).astChildren.l) { case List(ident: Identifier, call: Call) =>
        ident.typeFullName shouldBe "int[3]"
        ident.order shouldBe 1
        call.code shouldBe "{ [1] = 5, [2] = 10, [3 ... 9] = 15 }"
        call.order shouldBe 2
        call.name shouldBe Operators.arrayInitializer
        call.methodFullName shouldBe Operators.arrayInitializer
        val children = call.astMinusRoot.isCall.nameExact(Operators.assignment).l
        val args     = call.argument.astChildren.l
        inside(children) { case List(call1, call2, call3) =>
          call1.code shouldBe "[1] = 5"
          call1.name shouldBe Operators.assignment
          call1.astMinusRoot.code.l shouldBe List("1", "5")
          call1.argument.code.l shouldBe List("1", "5")
          call2.code shouldBe "[2] = 10"
          call2.name shouldBe Operators.assignment
          call2.astMinusRoot.code.l shouldBe List("2", "10")
          call2.argument.code.l shouldBe List("2", "10")
          call3.code shouldBe "[3 ... 9] = 15"
          call3.name shouldBe Operators.assignment
          val List(desCall) = call3.argument(1).start.collectAll[Call].l
          val List(value)   = call3.argument(2).start.collectAll[Literal].l
          value.code shouldBe "15"
          desCall.name shouldBe Operators.arrayInitializer
          desCall.code shouldBe "[3 ... 9]"
          desCall.argument.code.l shouldBe List("3", "9")
        }
        children shouldBe args
      }
    }

    "be correct for struct designated initializers in plain C" in {
      val cpg = code("""
        |void foo() {
        |  struct foo b = { .a = 1, .b = 2 };
        |};
      """.stripMargin)
      inside(cpg.assignment.head.astChildren.l) { case List(ident: Identifier, call: Call) =>
        ident.typeFullName shouldBe "foo"
        ident.order shouldBe 1
        call.code shouldBe "{ .a = 1, .b = 2 }"
        call.order shouldBe 2
        call.name shouldBe Operators.arrayInitializer
        call.methodFullName shouldBe Operators.arrayInitializer
        val children = call.astMinusRoot.isCall.l
        val args     = call.argument.astChildren.l
        inside(children) { case List(call1, call2) =>
          call1.code shouldBe ".a = 1"
          call1.name shouldBe Operators.assignment
          call1.astMinusRoot.code.l shouldBe List("a", "1")
          call1.argument.code.l shouldBe List("a", "1")
          call2.code shouldBe ".b = 2"
          call2.name shouldBe Operators.assignment
          call2.astMinusRoot.code.l shouldBe List("b", "2")
          call2.argument.code.l shouldBe List("b", "2")
        }
        children shouldBe args
      }
    }

    "be correct for designated struct initializers in C++" in {
      val cpg = code(
        """
        |class Point3D {
        |  public:
        |    int x;
        |    int y;
        |    int z;
        |};
        |
        |void foo() {
        |  Point3D point3D { .x = 1, .y = 2, .z = 3 };
        |};
      """.stripMargin,
        "test.cpp"
      )
      cpg.assignment.code.sorted.l shouldBe List("point3D.x = 1", "point3D.y = 2", "point3D.z = 3")
    }

    "be correct for call with pack expansion" in {
      val cpg = code(
        """
        |void foo(int x, int*... args) {
        |  foo(x, args...);
        |};
      """.stripMargin,
        "test.cpp"
      )
      inside(cpg.call.l) { case List(fooCall: Call) =>
        fooCall.code shouldBe "foo(x, args...)"
        inside(fooCall.argument.l) { case List(x, args) =>
          x.order shouldBe 1
          x.code shouldBe "x"
          args.order shouldBe 2
          args.code shouldBe "args"
        }
      }
    }

    "be correct for embedded ASM code" in {
      val cpg = code("""
        |asm(
        | "  push %ebp       \n"
        | "  movl %esp, %ebp \n"
        | "  push %ebx       \n"
        |);
      """.stripMargin)
      inside(cpg.method.ast.filter(_.label == NodeTypes.UNKNOWN).l) { case List(asm: Unknown) =>
        asm.code should startWith("asm(")
      }
    }

    "be correct for embedded ASM calls" in {
      val cpg = code("""
        |void foo() {
        |  asm("paddh %0, %1, %2\n\t"
        |	  : "=f" (x)
        |	  : "f" (y), "f" (z)
        |	);
        |}
      """.stripMargin)
      inside(cpg.method("foo").ast.filter(_.label == NodeTypes.UNKNOWN).l) { case List(asm: Unknown) =>
        asm.code should startWith("asm(")
      }
    }

    "be correct for compound statement expressions" in {
      val cpg = code("""
        |int x = ({int y = 1; y;}) + ({int z = 2; z;});
        |""".stripMargin)
      inside(cpg.call(Operators.addition).l) { case List(add) =>
        inside(add.argument.l) { case List(y, z) =>
          y.argumentIndex shouldBe 1
          y.order shouldBe 1
          inside(y.astChildren.l) { case List(_, c: Call, i: Identifier) =>
            c.code shouldBe "y = 1"
            i.code shouldBe "y"
          }
          z.argumentIndex shouldBe 2
          z.order shouldBe 2
          inside(z.astChildren.l) { case List(_, c: Call, i: Identifier) =>
            c.code shouldBe "z = 2"
            i.code shouldBe "z"
          }
        }
      }
    }

    "have correct line number for method content" in {
      val cpg = code("""
        |
        |
        |
        |
        | void method(int x) {
        |
        |   x = 1;
        | }
      """.stripMargin)
      cpg.method.nameExact("method").lineNumber.l shouldBe List(6)
      cpg.method.nameExact("method").block.assignment.lineNumber.l shouldBe List(8)
    }

    // for https://github.com/ShiftLeftSecurity/codepropertygraph/issues/1321
    "have correct line numbers example 1" in {
      val cpg = code("""
        |int main() {
        |int a = 0;
        |statementthatdoesnothing();
        |int b = 0;
        |int c = 0;
        |}
      """.stripMargin)
      inside(cpg.identifier.l) { case List(idA, idB, idC) =>
        idA.lineNumber shouldBe Option(3)
        idA.columnNumber shouldBe Option(5)
        idB.lineNumber shouldBe Option(5)
        idB.columnNumber shouldBe Option(5)
        idC.lineNumber shouldBe Option(6)
        idC.columnNumber shouldBe Option(5)
      }
    }

    // for https://github.com/ShiftLeftSecurity/codepropertygraph/issues/1321
    "have correct line/column numbers on all platforms" in {
      val windowsNewline = "\r\n"
      val windowsFixture: Cpg = code(
        s"void offset() {${windowsNewline}char * data = NULL;${windowsNewline}memset(data, 'A', 100-1); /* fill with 'A's */${windowsNewline}data = dataBuffer;$windowsNewline}"
      )
      val macNewline = "\r"
      val macFixture: Cpg = code(
        s"void offset() {${macNewline}char * data = NULL;${macNewline}memset(data, 'A', 100-1); /* fill with 'A's */${macNewline}data = dataBuffer;$macNewline}"
      )
      val linuxNewline = "\n"
      val linuxFixture: Cpg = code(
        s"void offset() {${linuxNewline}char * data = NULL;${linuxNewline}memset(data, 'A', 100-1); /* fill with 'A's */${linuxNewline}data = dataBuffer;$linuxNewline}"
      )

      val windowsLineNumbers = windowsFixture.identifier.lineNumber.l
      val macLineNumbers     = macFixture.identifier.lineNumber.l
      val linuxLineNumbers   = linuxFixture.identifier.lineNumber.l

      windowsLineNumbers should not be empty
      macLineNumbers should not be empty
      linuxLineNumbers should not be empty

      windowsLineNumbers shouldBe macLineNumbers
      windowsLineNumbers shouldBe linuxLineNumbers
      macLineNumbers shouldBe linuxLineNumbers

      val windowsColumnNumbers = windowsFixture.identifier.columnNumber.l
      val macColumnNumbers     = macFixture.identifier.columnNumber.l
      val linuxColumnNumbers   = linuxFixture.identifier.columnNumber.l

      windowsColumnNumbers should not be empty
      macColumnNumbers should not be empty
      linuxColumnNumbers should not be empty

      windowsColumnNumbers shouldBe macColumnNumbers
      windowsColumnNumbers shouldBe linuxColumnNumbers
      macColumnNumbers shouldBe linuxColumnNumbers

      windowsFixture.close()
      macFixture.close()
      linuxFixture.close()
    }

  }

  "AST with types" should {

    "be correct for function edge case" in {
      val cpg          = code("class Foo { char (*(*x())[5])() }", "test.cpp")
      val List(method) = cpg.method.nameNot("<global>").l
      method.name shouldBe "x"
      method.fullName shouldBe "Foo.x:char(*(*)[5])()()"
      method.code shouldBe "char (*(*x())[5])()"
      method.signature shouldBe "char(*(*)[5])()()"
    }

    "be consistent with pointer types" in {
      val cpg = code("""
        |struct x { char * z; };
        |char *a(char *y) {
        |  char *x;
        |}
        |""".stripMargin)
      cpg.member.nameExact("z").typeFullName.head shouldBe "char*"
      cpg.parameter.nameExact("y").typeFullName.head shouldBe "char*"
      cpg.local.nameExact("x").typeFullName.head shouldBe "char*"
      cpg.method.nameExact("a").methodReturn.typeFullName.head shouldBe "char*"
    }

    "be consistent with array types" in {
      val cpg = code("""
        |struct x { char z[1]; };
        |void a(char y[1]) {
        |  char x[1];
        |}
        |""".stripMargin)
      cpg.member.nameExact("z").typeFullName.head shouldBe "char[1]"
      cpg.parameter.nameExact("y").typeFullName.head shouldBe "char[1]"
      cpg.local.nameExact("x").typeFullName.head shouldBe "char[1]"
    }

    "be consistent with long number types" in {
      val cpg = code("""
        |#define BUFSIZE 0x111111111111111
        |void copy(char *string) {
        |	char buf[BUFSIZE];
        |	stpncpy(buf, string, BUFSIZE);
        |}
        |""".stripMargin)
      val List(bufLocal) = cpg.local.nameExact("buf").l
      bufLocal.typeFullName shouldBe "char[0x111111111111111]"
      bufLocal.code shouldBe "char buf[BUFSIZE]"
      val List(bufAllocCall) = cpg.call.nameExact(Operators.alloc).l
      bufAllocCall.code shouldBe "buf[BUFSIZE]"
      bufAllocCall.argument.ast.isLiteral.code.l shouldBe List("0x111111111111111")
    }
  }
}
