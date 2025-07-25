package io.joern.kotlin2cpg.querying

import io.joern.kotlin2cpg.Config
import io.joern.kotlin2cpg.testfixtures.KotlinCode2CpgFixture
import io.shiftleft.codepropertygraph.generated.nodes.{Block, Call, Return}
import io.shiftleft.semanticcpg.language.*

class MethodTests extends KotlinCode2CpgFixture(withOssDataflow = false) {
  "CPG for code with UTF8 symbols" should {
    val cpg = code("""
        |fun double(x: Int): Int {
        |  // ✅ This is a comment with UTF8.
        |  return x * 2
        |}
        |
        |fun main(args : Array<String>) {
        |  println("The double of 2 is: " + double(2))
        |}
        |""".stripMargin)
      .withConfig(Config().withDisableFileContent(false))

    "should have the correct offsets set for the double method" in {
      cpg.method.name("double").sourceCode.l shouldBe List("""fun double(x: Int): Int {
          |  // ✅ This is a comment with UTF8.
          |  return x * 2
          |}""".stripMargin)
    }

    "should have the correct offsets set for the main method" in {
      cpg.method.name("main").sourceCode.l shouldBe List("""fun main(args : Array<String>) {
          |  println("The double of 2 is: " + double(2))
          |}""".stripMargin)
    }
  }

  "CPG for code with simple method defined at package-level" should {
    val cpg = code("""
       |fun double(x: Int): Int {
       |  return x * 2
       |}
       |
       |fun main(args : Array<String>) {
       |  println("The double of 2 is: " + double(2))
       |}
       |""".stripMargin)
      .withConfig(Config().withDisableFileContent(false))

    "should contain exactly three non-external methods" in {
      cpg.method.isExternal(false).size shouldBe 3
    }

    "should have the correct offsets set for the double method" in {
      cpg.method.name("double").sourceCode.l shouldBe List("""fun double(x: Int): Int {
          |  return x * 2
          |}""".stripMargin)
    }

    "should have the correct offsets set for the main method" in {
      cpg.method.name("main").sourceCode.l shouldBe List("""fun main(args : Array<String>) {
          |  println("The double of 2 is: " + double(2))
          |}""".stripMargin)
    }

    "should contain method nodes with the correct fields" in {
      val List(x) = cpg.method.name("double").isExternal(false).l
      x.fullName shouldBe "double:int(int)"
      x.code shouldBe "double"
      x.signature shouldBe "int(int)"
      x.isExternal shouldBe false
      x.lineNumber shouldBe Some(2)
      x.columnNumber shouldBe Some(4)
      x.filename.endsWith(".kt") shouldBe true

      val List(y) = cpg.method.name("main").isExternal(false).l
      y.fullName shouldBe "main:void(java.lang.String[])"
      y.code shouldBe "main"
      y.signature shouldBe "void(java.lang.String[])"
      y.isExternal shouldBe false
      y.lineNumber shouldBe Some(6)
      x.columnNumber shouldBe Some(4)
      y.filename.endsWith(".kt") shouldBe true
    }

    "should contain MODIFIER nodes attached to the METHOD nodes" in {
      val List(mod1) = cpg.method.nameExact("double").modifier.l
      mod1.modifierType shouldBe "PUBLIC"

      val List(mod2) = cpg.method.nameExact("main").modifier.l
      mod2.modifierType shouldBe "PUBLIC"
    }

    "should allow traversing to parameters" in {
      cpg.method.name("double").isExternal(false).parameter.name.toSet shouldBe Set("x")
      cpg.method.name("main").isExternal(false).parameter.name.toSet shouldBe Set("args")
    }

    "should allow traversing to methodReturn" in {
      cpg.method.name("double").isExternal(false).methodReturn.typeFullName.l shouldBe List("int")
      cpg.method.name("main").isExternal(false).methodReturn.typeFullName.l shouldBe List("void")
    }

    "should allow traversing to file" in {
      cpg.method.name("double").isExternal(false).file.name.l should not be empty
      cpg.method.name("main").isExternal(false).file.name.l should not be empty
    }

    "should allow traversing to block" in {
      cpg.method.name("double").isExternal(false).block.l should not be empty
      cpg.method.name("main").isExternal(false).block.l should not be empty
    }
  }

  "CPG for code with simple class declaration" should {
    val cpg = code("""
        |package com.test.pkg
        |
        |class Foo {
        |  fun bar(x: Int): Int {
        |    return x * 2
        |  }
        |}
        |""".stripMargin)
      .withConfig(Config().withDisableFileContent(false))

    "should contain a METHOD node for `bar` with the props set" in {
      val List(m) = cpg.method.name("bar").l
      m.name shouldBe "bar"
      m.fullName shouldBe "com.test.pkg.Foo.bar:int(int)"
      m.code shouldBe "bar"
      m.signature shouldBe "int(int)"
      m.isExternal shouldBe false
      m.lineNumber shouldBe Some(5)
      m.columnNumber shouldBe Some(6)
      m.lineNumberEnd shouldBe Some(7)
      m.columnNumberEnd shouldBe Some(2)
      m.order shouldBe 1
      m.filename.endsWith(".kt") shouldBe true
    }

    "should have the correct offsets set for the bar method" in {
      cpg.method.name("bar").sourceCode.l shouldBe List("""fun bar(x: Int): Int {
          |    return x * 2
          |  }""".stripMargin)
    }

    "should allow traversing to parameters" in {
      cpg.method.name("bar").parameter.name.toSet shouldBe Set("this", "x")
    }

    "should allow traversing to methodReturn" in {
      cpg.method.name("bar").methodReturn.l.size shouldBe 1
    }

    "should allow traversing to file" in {
      cpg.method.name("bar").file.name.l should not be empty
    }

    "should allow traversing to block" in {
      cpg.method.name("bar").block.l should not be empty
    }
  }

  "CPG for code with method without a body-block" should {
    val cpg = code("fun printX(x: String) = println(x)")

    "should contain a METHOD node with one expression in its corresponding BLOCK" in {
      cpg.method.nameExact("printX").block.expressionDown.size shouldBe 1
    }
  }

  "CPG for code with method with single-expression body" should {
    val cpg = code("""
        |package main
        |class AClass(var x: String)
        |fun f1(p: String): AClass = AClass(p ?: "message")
        ||""".stripMargin)
      .withConfig(Config().withDisableFileContent(false))

    "should contain a RETURN node as the child of the METHOD's BLOCK" in {
      val List(m)         = cpg.method.nameExact("f1").l
      val List(r: Return) = m.block.astChildren.l: @unchecked
      val List(_: Block)  = r.astChildren.l: @unchecked
    }

    "should have the correct offsets set for the f1 method" in {
      cpg.method.name("f1").sourceCode.l shouldBe List("""fun f1(p: String): AClass = AClass(p ?: "message")""")
    }
  }

  "CPG for code with call with argument with type with upper bound" should {
    val cpg = code("""
      |package mypkg
      |open class Base
      |fun <S:Base>doSomething(one: S) {
      |    println(one)
      |}
      |""".stripMargin)
      .withConfig(Config().withDisableFileContent(false))

    "should contain a METHOD node with correct FULL_NAME set" in {
      val List(m) = cpg.method.nameExact("doSomething").l
      m.fullName shouldBe "mypkg.doSomething:void(mypkg.Base)"
    }

    "have the correct offsets set for the doSomething method" in {
      cpg.method.name("doSomething").sourceCode.l shouldBe List("""fun <S:Base>doSomething(one: S) {
          |    println(one)
          |}""".stripMargin)
    }

    "have the correct offsets set for the <global> method" in {
      cpg.method.nameExact("<global>").sourceCode.l shouldBe List("""
          |package mypkg
          |open class Base
          |fun <S:Base>doSomething(one: S) {
          |    println(one)
          |}
          |""".stripMargin)
    }
  }

  "a higher-function defined from a closure" should {
    val cpg = code("""
        |class Foo {
        |    fun Collection<ByteArray>.sorted(): List<ByteArray> = sortedWith { a, b ->
        |        operator fun ByteArray.compareTo(other: ByteArray): Int {
        |            var result: Int? = null
        |            val minSize = kotlin.math.min(this.size, other.size)
        |            for (index in 0 until minSize) {
        |                val thisByte = this[index]
        |                val otherByte = other[index]
        |                val comparedResult = thisByte.compareTo(otherByte)
        |                if (comparedResult != 0 && result == null) {
        |                    result = comparedResult
        |                }
        |            }
        |
        |            return result ?: this.size.compareTo(other.size)
        |        }
        |
        |        return a.compareTo(b)
        |    }
        |}
        |""".stripMargin)
      .withConfig(Config().withDisableFileContent(false))

    "pass the lambda to a `sortedWith` call which is then under the method `sorted`" in {
      inside(cpg.methodRefWithName(".*<lambda>.*").inCall.l) {
        case sortedWith :: Nil =>
          sortedWith.name shouldBe "sortedWith"
          sortedWith.method.name shouldBe "sorted"
        case xs => fail(s"Expected a single call with the method reference argument. Instead got [$xs]")
      }
    }

    "have the correct offsets set for the sorted method" in {
      cpg.method.name("sorted").sourceCode.l
    }
  }

  "test correct translation of parameter kotlin type to java type" in {
    val cpg = code("""
        |fun method(x: kotlin.CharArray) {
        |}
        |""".stripMargin)

    inside(cpg.method.name("method").l) { case List(method) =>
      method.fullName shouldBe "method:void(char[])"
    }
  }
}
