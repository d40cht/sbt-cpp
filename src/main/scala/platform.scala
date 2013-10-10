package org.seacourt.build

import sbt._
import Keys._

import scala.collection.{ mutable, immutable }

object PlatformChecks {
  sealed trait CompileType
  object CCTest extends CompileType
  object CXXTest extends CompileType

  def testExtractHeaders(
    log: Logger,
    compiler: Compiler,
    compileType: CompileType,
    minimalProgram: String,
    expectHeaders: Set[String],
    includePaths: Seq[File] = Seq()): Boolean =
    IO.withTemporaryDirectory { td =>
      IO.createDirectory(td)

      val fileName = compileType match {
        case CCTest => "test.c"
        case CXXTest => "test.cpp"
      }
      val testFile = td / fileName
      IO.write(testFile, minimalProgram)

      try {
        val outFile = compiler.findHeaderDependencies(
          log,
          td,
          compiler.defaultIncludePaths ++ includePaths,
          Seq(),
          testFile,
          Seq(),
          quiet = true)()
        val headerLines = IO.readLines(outFile).map(l => file(l).getName).toSet

        val success = expectHeaders.foreach(eh => assert(
          headerLines contains eh,
          "Expected header not found when testing dependency extraction: " + eh))

        log.success("Compiler able to extract header dependencies")

        true
      } catch {
        case e: java.lang.RuntimeException => false
      }
    }

  private def tryCompileAndLinkImpl(
    log: Logger,
    compiler: Compiler,
    compileType: CompileType,
    andLink: Boolean,
    minimalProgram: String,
    includePaths: Seq[File],
    linkPaths: Seq[File],
    linkLibraries: Seq[String]): Boolean =
    IO.withTemporaryDirectory { td =>
      IO.createDirectory(td)

      val fileName = compileType match {
        case CCTest => "test.c"
        case CXXTest => "test.cpp"
      }
      val testFile = td / fileName
      val outputName = testFile.getName + ".o"
      IO.write(testFile, minimalProgram)

      try {
        val objFile = compileType match {
          case CCTest => compiler.ccCompileToObj(
            log,
            td,
            compiler.defaultIncludePaths ++ includePaths,
            Seq(),
            testFile,
            compiler.ccDefaultFlags,
            quiet = true)()
          case CXXTest => compiler.cxxCompileToObj(
            log,
            td,
            compiler.defaultIncludePaths ++ includePaths,
            Seq(),
            testFile,
            compiler.cxxDefaultFlags,
            quiet = true)()
        }

        if (andLink) {
          compiler.buildExecutable(
            log,
            td,
            outputName,
            executableLinkFlags = compiler.executableLinkDefaultFlags,
            linkPaths = compiler.defaultLibraryPaths ++ linkPaths,
            linkLibraries = linkLibraries,
            inputFiles = Seq(objFile),
            quiet = true)()
        }

        true
      } catch {
        case e: java.lang.RuntimeException =>
          {
            log.debug("Caught exception: " + e)
            false
          }
      }
    }

  def tryCompile(
    log: Logger,
    compiler: Compiler,
    compileType: CompileType,
    minimalProgram: String,
    includePaths: Seq[File] = Seq()) = tryCompileAndLinkImpl(
    log,
    compiler,
    compileType,
    false,
    minimalProgram,
    includePaths,
    Seq(),
    Seq())

  def tryCompileAndLink(
    log: Logger,
    compiler: Compiler,
    compileType: CompileType,
    minimalProgram: String,
    includePaths: Seq[File] = Seq(),
    linkPaths: Seq[File] = Seq(),
    linkLibraries: Seq[String] = Seq()) = tryCompileAndLinkImpl(
    log,
    compiler,
    compileType,
    true,
    minimalProgram,
    includePaths,
    linkPaths,
    linkLibraries)

  def testForHeader(
    log: Logger,
    compiler: Compiler,
    compileType: CompileType,
    headerName: String,
    includePaths: Seq[File] = Seq()) = tryCompile(
    log,
    compiler,
    compileType,
    "#include \"%s\"\n".format(headerName),
    includePaths = includePaths)

  def testForSymbolDeclaration(
    log: Logger,
    compiler: Compiler,
    compileType: CompileType,
    symbolName: String,
    additionalHeaders: Seq[String],
    includePaths: Seq[File] = Seq(),
    linkPaths: Seq[File] = Seq(),
    linkLibraries: Seq[String] = Seq()) = {
    val headerIncludes =
      additionalHeaders.map(h => "#include \"%s\"".format(h)).mkString("\n")
    val testProg = headerIncludes + """
            |int main( int argc, char** argv )
            |{
            |    (void) argc; (void) argv;
            |    void* foo = (void*) &%s;
            |    return foo != 0;
            |}
            """.stripMargin.format(symbolName)

    tryCompileAndLink(
      log,
      compiler,
      compileType,
      testProg,
      includePaths,
      linkPaths,
      linkLibraries)
  }

  def testForTypeSize(
    log: Logger,
    compiler: Compiler,
    compileType: CompileType,
    typeName: String,
    typeSize: Int,
    additionalHeaders: Seq[String] = Seq(),
    includePaths: Seq[File] = Seq()) = {
    val headerIncludes =
      additionalHeaders.map(h => "#include \"%s\"".format(h)).mkString("\n")
    val testProg = {
      val template =
        "\nstruct TestFoo { unsigned int bf : (sizeof(%s) == %d); };"
      val code = template.stripMargin.format(typeName, typeSize)
      headerIncludes + code
    }

    tryCompile(
      log,
      compiler,
      compileType,
      testProg,
      includePaths = includePaths)
  }

  def testCCompiler(log: Logger, compiler: Compiler) {
    assert(tryCompileAndLink(log, compiler, CCTest, """
            |int main( int argc, char** argv )
            |{
            |    (void) argc; (void) argv;
            |    return 0;
            |}""".stripMargin), "Unable to build minimal c program")

    log.success("C compiler able to compile and link a minimal program")
  }

  def testCXXCompiler(log: Logger, compiler: Compiler) {
    assert(tryCompileAndLink(log, compiler, CXXTest, """
            |class Bing
            |{
            |public:
            |    Bing() : a(12) {}
            |    int a;
            |};
            |int main( int argc, char** argv )
            |{
            |    (void) argc; (void) argv;
            |    Bing bing;
            |    return bing.a;
            |}""".stripMargin), "Unable to build minimal c++ program")

    log.success("C++ compiler able to compile and link a minimal program")
  }

  def testHeaderParse(log: Logger, compiler: Compiler) = testExtractHeaders(
    log,
    compiler,
    CCTest,
    "#include <stdint.h>",
    Set("stdint.h"))

  def requireHeader(
    log: Logger,
    compiler: Compiler,
    compileType: CompileType,
    headerName: String,
    includePaths: Seq[File] = Seq()) {
    assert(PlatformChecks.testForHeader(
      log,
      compiler,
      compileType,
      headerName,
      includePaths),
      "Unable to find required header: " + headerName)
    log.success("Required header found: " + headerName)
  }

  def requireSymbol(
    log: Logger,
    compiler: Compiler,
    compileType: CompileType,
    symbolName: String,
    headers: Seq[String],
    linkPaths: Seq[File] = Seq(),
    linkLibraries: Seq[String] = Seq()) {
    assert(
      testForSymbolDeclaration(log, compiler, compileType, symbolName, headers),
      "Unable to find required symbol declaration: " + symbolName)
    log.success("Required symbol is available: " + symbolName)
  }

  def requireTypeSize(
    log: Logger,
    compiler: Compiler,
    compileType: CompileType,
    typeName: String,
    typeSize: Int,
    headers: Seq[String]) {
    assert(
      testForTypeSize(log, compiler, compileType, typeName, typeSize, headers),
      "Type %s is not of required size %d".format(typeName, typeSize))
    log.success("Type %s is of required size %d".format(typeName, typeSize))
  }
}

