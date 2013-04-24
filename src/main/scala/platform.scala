package org.seacourt.build

import sbt._
import Keys._

import scala.collection.{mutable, immutable}


object PlatformChecks
{
    def tryCompile( log : Logger, compiler : Compiler, minimalProgram : String, fileName : String, includePaths : Seq[File] = Seq() ) : Boolean =
    {
        IO.withTemporaryDirectory
        { td =>

            //val td = file( "gook" ).getAbsoluteFile
            IO.createDirectory( td )
            
            val testFile = td / fileName
            IO.write( testFile, minimalProgram )

            try
            {
                compiler.compileToObjectFile( log, td, compiler.defaultIncludePaths ++ includePaths, Seq(), testFile, Seq(), quiet=true )()
                
                true
            }
            catch
            {
                case e : java.lang.RuntimeException => 
                {
                    false
                }
            }
        }
    }
    
    
    def testForHeader( log : Logger, compiler : Compiler, fileName : String, headerName : String ) =
    {
        tryCompile( log, compiler, "#include \"%s\"\n".format( headerName ), fileName )
    }
    
    
    def testForSymbolDeclaration( log : Logger, compiler : Compiler, fileName : String, symbolName : String, additionalHeaders : Seq[String], includePaths : Seq[File] = Seq() ) =
    {
        val headerIncludes = additionalHeaders.map( h => "#include \"%s\"".format(h) ).mkString("\n")
        val testProg = headerIncludes + """
            |void* foo()
            |{
            |    return (void*) &%s;
            |}
            """.stripMargin.format( symbolName, symbolName )

        tryCompile( log, compiler, testProg, fileName, includePaths=includePaths )
    }
    
    def testForTypeSize( log : Logger, compiler : Compiler, fileName : String, typeName : String, typeSize : Int, additionalHeaders : Seq[String] = Seq(), includePaths : Seq[File] = Seq() ) =
    {
        val headerIncludes = additionalHeaders.map( h => "#include \"%s\"".format(h) ).mkString("\n")
        val testProg = headerIncludes + "\nstruct TestFoo { unsigned int bf : (sizeof(%s) == %d); };".stripMargin.format( typeName, typeSize )
            
        tryCompile( log, compiler, testProg, fileName, includePaths=includePaths )
    }
    
    def testCCompiler( log : Logger, compiler : Compiler )
    {
        assert( tryCompile( log, compiler, """
            |int foo()
            |{
            |    return 0;
            |}""".stripMargin, "test.c" ), "Unable to build minimal c program" )
            
        log.success( "C compiler able to build a minimal program" )
     }
     
     def testCXXCompiler( log : Logger, compiler : Compiler )
     {
        assert( tryCompile( log, compiler, """
            |class Bing
            |{
            |public:
            |    Bing() : a(12) {}
            |    int a;
            |};
            |int foo()
            |{
            |    Bing bing;
            |    return bing.a;
            |}""".stripMargin, "test.cpp" ), "Unable to build minimal c++ program" )
            
        log.success( "C++ compiler able to build a minimal program" )
    }
    
    def requireHeader( log : Logger, compiler : Compiler, fileName : String, headerName : String )
    {
        assert( PlatformChecks.testForHeader( log, compiler, fileName, headerName ), "Unable to find required header: " + headerName )
        log.success( "Required header found: " + headerName )
    }
    
    def requireSymbol( log : Logger, compiler : Compiler, fileName : String, symbolName : String, headers : Seq[String] )
    {
        assert( testForSymbolDeclaration( log, compiler, fileName, symbolName, headers ), "Unable to find required symbol declaration: " + symbolName )
        log.success( "Required symbol is available: " + symbolName )
    }
    
    def requireTypeSize( log : Logger, compiler : Compiler, fileName : String, typeName : String, typeSize : Int, headers : Seq[String] )
    {
        assert( testForTypeSize( log, compiler, fileName, typeName, typeSize, headers ), "Type %s is not of required size %d".format( typeName, typeSize ) )
        log.success( "Type %s is of required size %d".format( typeName, typeSize ) )
    }
}

