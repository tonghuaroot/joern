package io.joern.c2cpg.macros

import io.joern.c2cpg.Config
import io.joern.c2cpg.astcreation.Defines
import io.joern.c2cpg.testfixtures.C2CpgSuite
import io.joern.c2cpg.testfixtures.DataFlowCodeToCpgSuite
import io.joern.dataflowengineoss.language.*
import io.shiftleft.codepropertygraph.generated.DispatchTypes
import io.shiftleft.codepropertygraph.generated.Operators
import io.shiftleft.codepropertygraph.generated.nodes.Block
import io.shiftleft.codepropertygraph.generated.nodes.Call
import io.shiftleft.codepropertygraph.generated.nodes.Identifier
import io.shiftleft.semanticcpg.language.*

class MacroHandlingTests extends C2CpgSuite {

  "MacroHandlingTests1" should {
    val cpg = code("""
       |#define A_MACRO(x,c) (x = 10 + c)
       |int foo() {
       |  int *y;
       |  A_MACRO(*y, 2);
       |  return 10 * y;
       |}
    """.stripMargin).withConfig(Config().withDisableFileContent(false))

    "should correctly expand macro" in {
      val List(x) = cpg.method("foo").call.nameExact(Operators.assignment).l
      x.code shouldBe "*y = 10 + 2"
      x.dispatchType shouldBe DispatchTypes.STATIC_DISPATCH

      val List(macroCall) = cpg.method("foo").call.nameExact("A_MACRO").l
      macroCall.code shouldBe "A_MACRO(*y, 2)"

      val List(macroExpansion) = macroCall.macroExpansion.isCall.l
      macroExpansion shouldBe x

      val List(arg1) = macroCall.argument.isCall.l
      arg1.name shouldBe Operators.indirection
      arg1.code shouldBe "*y"
      arg1.order shouldBe 1
      arg1.argumentIndex shouldBe 1

      val List(identifier) = arg1.argument.isIdentifier.l
      identifier.name shouldBe "y"
      identifier.order shouldBe 1
      identifier.argumentIndex shouldBe 1

      val List(arg2) = macroCall.argument.isLiteral.l
      arg2.code shouldBe "2"
      arg2.order shouldBe 2
      arg2.argumentIndex shouldBe 2
    }

    "should create a METHOD for the expanded macro" in {
      val List(m) = cpg.method.name("A_MACRO").l
      m.fullName shouldBe "Test0.c:A_MACRO:ANY(2)"
      m.filename shouldBe "Test0.c"
      m.code shouldBe "#define A_MACRO(x,c) (x = 10 + c)"
      m.lineNumber shouldBe Option(2)
      m.lineNumberEnd shouldBe Option(2)
      m.columnNumber shouldBe Option(1)
      m.columnNumberEnd shouldBe Option(33)
      m.offset shouldBe Some(1)
      m.offsetEnd shouldBe Some(34)
      m.content shouldBe Some("#define A_MACRO(x,c) (x = 10 + c)")
      val List(param1, param2) = m.parameter.l.sortBy(_.order)
      param1.name shouldBe "p1"
      param2.name shouldBe "p2"
    }

  }

  "MacroHandlingTests2" should {
    val cpg = code("""
       |#define A_MACRO(x) (x = A_SECOND_MACRO(x))
       |#define A_SECOND_MACRO(x) (x+1)
       |int foo() {
       | int y;
       | A_MACRO(y);
       | return 10 * y;
       |}
    """.stripMargin)

    "should correctly expand macro inside macro" in {
      val List(x) = cpg.method("foo").call.nameExact(Operators.assignment).l
      x.code shouldBe "y = (y + 1)"

      val List(identifier) = x.argument.isIdentifier.l
      identifier.code shouldBe "y"
      identifier.argumentIndex shouldBe 1
      val List(call2) = x.argument.isCall.l
      call2.code shouldBe "y + 1"
      call2.argumentIndex shouldBe 2

      val List(arg1) = call2.argument.isIdentifier.l
      arg1.name shouldBe "y"
      arg1.order shouldBe 1
      arg1.argumentIndex shouldBe 1

      val List(arg2) = call2.argument.isLiteral.l
      arg2.code shouldBe "1"
      arg2.order shouldBe 2
      arg2.argumentIndex shouldBe 2
    }
  }

  "MacroHandlingTests3" should {
    val cpg = code("""
       |#define A_MACRO(x) (printf(x))
       |int foo() {
       |int y;
       |A_MACRO(y);
       |return 10 * y;
       |}
    """.stripMargin)

    "should correctly expand macro inside macro" in {
      val List(x) = cpg.method("foo").call.nameExact("printf").l
      x.code shouldBe "printf(y)"

      val List(arg) = x.argument.isIdentifier.l
      arg.name shouldBe "y"
      arg.argumentIndex shouldBe 1
    }
  }

  "MacroHandlingTests4" should {
    val cpg = code("""
       |#define A_MACRO(dst, code, size)\
     do \
    { \
        if( (i_read) >= (size) ) \
        { \
            dst = (code); \
            p_peek += (size); \
            i_read -= (size); \
        } \
        else \
        { \
            dst = 0; \
            i_read = 0; \
        } \
    } while(0)
       |
       |#define A_MACRO_2() (dst++)
       |
       |int foo() {
       |char * dst, ptr;
       |A_MACRO(dst, ptr, 1);
       |dst++;
       |A_MACRO_2();
       |return 10 * y;
       |}
    """.stripMargin)

    "should correctly include calls to macros" in {
      val List(call1) = cpg.method("foo").call.nameExact("A_MACRO").l
      call1.code shouldBe "A_MACRO(dst, ptr, 1)"
      call1.name shouldBe "A_MACRO"
      call1.methodFullName shouldBe "Test0.c:A_MACRO:ANY(3)"
      call1.lineNumber shouldBe Option(22)
      call1.columnNumber shouldBe Option(1)
      call1.typeFullName shouldBe "ANY"
      call1.dispatchType shouldBe DispatchTypes.INLINED
      call1.code shouldBe "A_MACRO(dst, ptr, 1)"

      val List(arg1, arg2, arg3) = call1.argument.l.sortBy(_.order)
      arg1.code shouldBe "dst"
      arg2.code shouldBe "ptr"
      arg3.code shouldBe "1"

      val List(call2) = cpg.method("foo").call.nameExact("A_MACRO_2").l
      call2.code shouldBe "A_MACRO_2()"
      call2.name shouldBe "A_MACRO_2"
      call2.methodFullName shouldBe "Test0.c:A_MACRO_2:ANY(0)"
      call2.lineNumber shouldBe Option(24)
      call2.columnNumber shouldBe Option(1)
      call2.typeFullName shouldBe "ANY"
      call2.argument.size shouldBe 0
      call2.dispatchType shouldBe DispatchTypes.INLINED
    }
  }

  "MacroHandlingTests5" should {
    val cpg = code("""
       |#define A_MACRO (1)
       |int foo() {
       |  return A_MACRO;
       |}
    """.stripMargin)

    "should correctly include call to macro that is only a constant in a return" in {
      val List(call) = cpg.method("foo").call.l
      call.name shouldBe "A_MACRO"
      call.code shouldBe "A_MACRO"
      call.argument.size shouldBe 0
      val List(macroExpansion) = call.macroExpansion.isLiteral.l
      macroExpansion.code shouldBe "1"
    }
  }

  "MacroHandlingTests6" should {
    val cpg = code("""
       |#define A_MACRO 0x0
       |int foo() {
       |  return A_MACRO;
       |}
    """.stripMargin)

    "should correctly include call to macro that is only a constant (no brackets) in a return" in {
      val List(call) = cpg.method("foo").call.l
      call.name shouldBe "A_MACRO"
      call.code shouldBe "A_MACRO"
      call.argument.size shouldBe 0
      val List(macroExpansion) = call.macroExpansion.isLiteral.l
      macroExpansion.code shouldBe "0x0"
    }

  }

  "MacroHandlingTests7" should {
    val cpg = code("""
        |#define atomic_load_explicit(PTR, MO)					\
        |  __extension__								\
        |  ({									\
        |    __auto_type __atomic_load_ptr = (PTR);				\
        |    __typeof__ (*__atomic_load_ptr) __atomic_load_tmp;			\
        |    __atomic_load (__atomic_load_ptr, &__atomic_load_tmp, (MO));	\
        |    __atomic_load_tmp;							\
        |  })
        |
        |#define atomic_load(PTR)  atomic_load_explicit (PTR, __ATOMIC_SEQ_CST)
        |
        |int foo(int *x) {
        |  if ( atomic_load( &preparser->deactivated ) )
        |    return;
        |  foo();
        |}
    """.stripMargin)

    "should not result in malformed CFGs when expanding a nested macro with block" in {
      cpg.all.collectAll[Block].l.count(b => b._cfgOut.size > 1) shouldBe 0
    }
  }

  "MacroHandlingTests8" should {
    val cpg = code("""
       |#define FLAG_A 1
       |
       |int func(int x) {
       |  if(x & FLAG_A) {
       |    return 0;
       |  } else if (FLAG_A & x) {
       |    return 1;
       |  }
       |}""".stripMargin)

    "should expand the macro on both sides of binary operators" in {
      cpg.call.name(Operators.and).code.l shouldBe List("x & FLAG_A", "FLAG_A & x")
      val List(andCall1, andCall2) = cpg.call.name(Operators.and).l
      val List(andCall1Arg1)       = andCall1.argument.isIdentifier.l
      andCall1Arg1.name shouldBe "x"
      val List(andCall1Arg2) = andCall1.argument.isCall.l
      andCall1Arg2.name shouldBe "FLAG_A"
      val List(andCall2Arg1) = andCall2.argument.isCall.l
      andCall2Arg1.name shouldBe "FLAG_A"
      val List(andCall2Arg2) = andCall2.argument.isIdentifier.l
      andCall2Arg2.name shouldBe "x"
    }
  }

  "MacroHandlingTests9" should {
    val cpg = code(
      """
        |// _Generic is a macro from C11 currently un-parsable by the used CDT parser version
        |#define type_num(X) _Generic((X), \
        |  long double: 1, \
        |  default: 0, \
        |  float: 2 \
        | )
        |
        |int test_generic(void) {
        |  float x = 8.0;
        |  const float y = 3.375;
        |  int z = type_num(x);
        |  return z;
        |}""".stripMargin,
      "file.cpp"
    )

    "should recover from un-parsable macros" in {
      val List(localZ) = cpg.local.nameExact("z").l
      localZ.code shouldBe "int z"
      localZ.typeFullName shouldBe "int"
      localZ.lineNumber shouldBe Some(12)
      localZ.columnNumber shouldBe Some(7)
      val List(zAssignmentCall) = cpg.call.codeExact("z = type_num(x)").l
      val zIdentifier           = zAssignmentCall.argument(1).asInstanceOf[Identifier]
      zIdentifier.code shouldBe "z"
      zIdentifier.lineNumber shouldBe Some(12)
      zIdentifier.columnNumber shouldBe Some(7)
      val typeNumCall = zAssignmentCall.argument(2).asInstanceOf[Call]
      typeNumCall.code shouldBe "type_num(x)"
      typeNumCall.name shouldBe "type_num"
      typeNumCall.lineNumber shouldBe Some(12)
      typeNumCall.columnNumber shouldBe Some(11)
    }
  }

  "MacroHandlingTests10" should {

    "have ast parents" in {
      val cpg = code("""
          |#define FFSWAP(type,a,b) do{type SWAP_tmp=b; b=a; a=SWAP_tmp;}while(0)
          |struct elem_to_channel {
          |    uint64_t av_position;
          |    uint8_t syn_ele;
          |    uint8_t elem_id;
          |    uint8_t aac_position;
          |};
          |int main () {
          |  struct elem_to_channel e2c_vec[4 * 1] = { { 0 } };
          |  int i = 1;
          |  FFSWAP(struct elem_to_channel, e2c_vec[i - 1], e2c_vec[i]);
          |}
          |""".stripMargin)
      cpg.local.count(l => l._astIn.isEmpty) shouldBe 0
      cpg.local.count(l => l._astIn.size > 1) shouldBe 0
      cpg.local.count(l => l._astIn.size == 1) shouldBe 3
    }

    "only have locals with exactly one ast parent" in {
      val cpg = code(
        """
          |#define deleteReset(ptr) do { delete ptr; ptr = nullptr; } while(0)
          |void func(void) {
          |  int *foo = new int;
          |  int *bar = new int;
          |  int *baz = new int;
          |  deleteReset(foo);
          |  deleteReset(bar);
          |  deleteReset(baz);
          |}
          |""".stripMargin,
        "foo.cc"
      )
      val List(foo) = cpg.local.nameExact("foo").l
      foo._astIn.size shouldBe 1
      val List(bar) = cpg.local.nameExact("bar").l
      bar._astIn.size shouldBe 1
      val List(baz) = cpg.local.nameExact("baz").l
      baz._astIn.size shouldBe 1
    }

    "only have local for declaration and not from assignment from broken macro" in {
      val cpg = code("""
          |#define FOO() (long)va_arg(ap, int)
          |void func(void) {
          |  int foo;
          |  foo = FOO();
          |  foo = FOO();
          |}
          |""".stripMargin)
      cpg.local.nameExact("foo").size shouldBe 1
    }
  }

  "MacroHandlingTests11" should {
    "only have exactly one method stubbed" in {
      val cpg = code("""
          |#define FOO() (long)va_arg(ap, int)
          |void func(void) {
          |  int foo;
          |  foo = FOO();
          |  foo = FOO();
          |}
          |""".stripMargin)
      cpg.method.nameExact("FOO").size shouldBe 1
    }
  }
}

class CfgMacroTests extends DataFlowCodeToCpgSuite {
  private val cpg = code("""
       |#define MP4_GET4BYTES( dst ) MP4_GETX_PRIVATE( dst, GetDWBE(p_peek), 4 )
       |#define MP4_GETX_PRIVATE(dst, code, size) \
    do \
    { \
        if( (i_read) >= (size) ) \
        { \
            dst = (code); \
            p_peek += (size); \
            i_read -= (size); \
        } \
        else \
        { \
            dst = 0; \
            i_read = 0; \
        } \
    } while(0)

    |int foo() {
    |unsigned int x;
    |MP4_GET4BYTES(x);
    |sink(x);
    |}""".stripMargin)

  "should create correct CFG for macro expansion and find data flow" in {
    val List(callToMacro) = cpg.method("foo").call.dispatchType(DispatchTypes.INLINED).l
    callToMacro.argument.code.l shouldBe List("x")
    callToMacro.cfgNext.code.toSetMutable shouldBe Set("x", s"${Defines.UnknownTag} i_read")

    val source = cpg.method("foo").call.name("MP4_GET4BYTES").argument(1).l
    val sink   = cpg.method("foo").call.name("sink").argument(1).l
    val flows  = sink.reachableByFlows(source)
    flows.map(flowToResultPairs).toSetMutable shouldBe
      Set(List(("MP4_GET4BYTES(x)", 21), ("sink(x)", 22)))
  }
}
