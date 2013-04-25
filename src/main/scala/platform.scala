package org.seacourt.build

import sbt._
import Keys._

import scala.collection.{mutable, immutable}


object PlatformChecks
{
    def testExtractHeaders( log : Logger, compiler : Compiler, minimalProgram : String, fileName : String, expectHeaders : Set[String], includePaths : Seq[File] = Seq() ) : Boolean =
    {
        IO.withTemporaryDirectory
        { td =>

            IO.createDirectory( td )
            
            val testFile = td / fileName
            IO.write( testFile, minimalProgram )

            try
            {
                val outFile = compiler.findHeaderDependencies( log, td, compiler.defaultIncludePaths ++ includePaths, Seq(), testFile, Seq(), quiet=true )()
                val headerLines = IO.readLines( outFile ).map( l => file(l).getName ).toSet
                
                val success = expectHeaders.foreach( eh => assert( headerLines contains eh, "Expected header not found when testing dependency extraction: " + eh ) )
                
                log.success( "Compiler able to extract header dependencies" )
                
                true
            }
            catch
            {
                case e : java.lang.RuntimeException => false
            }
        }
    }
    
    private def tryCompileAndLinkImpl( andLink : Boolean, log : Logger, compiler : Compiler, minimalProgram : String, fileName : String, includePaths : Seq[File], linkPaths : Seq[File], linkLibraries : Seq[String] ) : Boolean =
    {
        IO.withTemporaryDirectory
        { td =>

            IO.createDirectory( td )
            
            val testFile = td / fileName
            val outputName = testFile.getName + ".o"
            IO.write( testFile, minimalProgram )

            try
            {
                val objFile = compiler.compileToObjectFile( log, td, includePaths, Seq(), testFile, Seq(), quiet=true )()
                
                if ( andLink )
                {
                    compiler.buildExecutable( log, td, outputName,
                        linkFlags=Seq(),
                        linkPaths=linkPaths,
                        linkLibraries=linkLibraries,
                        inputFiles=Seq(objFile), quiet=true )()
                }
                
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
    
    def tryCompile( log : Logger, compiler : Compiler, minimalProgram : String, fileName : String, includePaths : Seq[File] = Seq() ) =
        tryCompileAndLinkImpl( false, log, compiler, minimalProgram, fileName, includePaths, Seq(), Seq() )
        
    def tryCompileAndLink( log : Logger, compiler : Compiler, minimalProgram : String, fileName : String, includePaths : Seq[File] = Seq(), linkPaths : Seq[File] = Seq(), linkLibraries : Seq[String] = Seq() ) =
        tryCompileAndLinkImpl( true, log, compiler, minimalProgram, fileName, includePaths, linkPaths, linkLibraries )
    
    
    def testForHeader( log : Logger, compiler : Compiler, fileName : String, headerName : String ) =
    {
        tryCompile( log, compiler, "#include \"%s\"\n".format( headerName ), fileName )
    }
    
    
    def testForSymbolDeclaration( log : Logger, compiler : Compiler, fileName : String, symbolName : String, additionalHeaders : Seq[String], includePaths : Seq[File] = Seq(), linkPaths : Seq[File] = Seq(), linkLibraries : Seq[String] = Seq() ) =
    {
        val headerIncludes = additionalHeaders.map( h => "#include \"%s\"".format(h) ).mkString("\n")
        val testProg = headerIncludes + """
            |int main( int argc, char** argv )
            |{
            |    void* foo = (void*) &%s;
            |    return foo != 0;
            |}
            """.stripMargin.format( symbolName, symbolName )

        tryCompileAndLink( log, compiler, testProg, fileName, includePaths, linkPaths, linkLibraries )
    }
    
    def testForTypeSize( log : Logger, compiler : Compiler, fileName : String, typeName : String, typeSize : Int, additionalHeaders : Seq[String] = Seq(), includePaths : Seq[File] = Seq() ) =
    {
        val headerIncludes = additionalHeaders.map( h => "#include \"%s\"".format(h) ).mkString("\n")
        val testProg = headerIncludes + "\nstruct TestFoo { unsigned int bf : (sizeof(%s) == %d); };".stripMargin.format( typeName, typeSize )
            
        tryCompile( log, compiler, testProg, fileName, includePaths=includePaths )
    }
    
    def testCCompiler( log : Logger, compiler : Compiler )
    {
        assert( tryCompileAndLink( log, compiler, """
            |int main( int argc, char** argv )
            |{
            |    return 0;
            |}""".stripMargin, "test.c" ), "Unable to build minimal c program" )
            
        log.success( "C compiler able to compile and link a minimal program" )
     }
     
     def testCXXCompiler( log : Logger, compiler : Compiler )
     {
        assert( tryCompileAndLink( log, compiler, """
            |class Bing
            |{
            |public:
            |    Bing() : a(12) {}
            |    int a;
            |};
            |int main( int argc, char** argv )
            |{
            |    Bing bing;
            |    return bing.a;
            |}""".stripMargin, "test.cpp" ), "Unable to build minimal c++ program" )
            
        log.success( "C++ compiler able to compile and link a minimal program" )
    }
    
    def testHeaderParse( log : Logger, compiler : Compiler ) = testExtractHeaders( log, compiler, "#include <stdint.h>", "test.c", Set("stdint.h") )
    
    def requireHeader( log : Logger, compiler : Compiler, fileName : String, headerName : String )
    {
        assert( PlatformChecks.testForHeader( log, compiler, fileName, headerName ), "Unable to find required header: " + headerName )
        log.success( "Required header found: " + headerName )
    }
    
    def requireSymbol( log : Logger, compiler : Compiler, fileName : String, symbolName : String, headers : Seq[String], linkPaths : Seq[File] = Seq(), linkLibraries : Seq[String] = Seq() )
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

