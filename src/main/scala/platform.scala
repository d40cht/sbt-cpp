package org.seacourt.build

import sbt._
import Keys._

import scala.collection.{mutable, immutable}


object PlatformChecks
{
    private def tryCompile( log : Logger, compiler : Compiler, minimalProgram : String, includePaths : Seq[File] = Seq() ) : Boolean =
    {
        IO.withTemporaryDirectory
        { td =>

            //val td = file( "gook" ).getAbsoluteFile
            IO.createDirectory( td )
            
            val testFile = td / "test.cpp"
            IO.write( testFile, minimalProgram )

            try
            {
                compiler.compileToObjectFile( log, td, includePaths, Seq(), testFile, Seq(), quiet=true )()
                
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
    
    
    def testForHeader( log : Logger, compiler : Compiler, headerName : String ) =
    {
        tryCompile( log, compiler, "#include \"%s\"\n".format( headerName ) )
    }
    
    
    def testForSymbolDeclaration( log : Logger, compiler : Compiler, symbolName : String, additionalHeaders : Seq[String], includePaths : Seq[File] = Seq() ) =
    {
        val headerIncludes = additionalHeaders.map( h => "#include \"%s\"".format(h) ).mkString("\n")
        val testProg = headerIncludes + """
            |void* foo()
            |{
            |    return (void*) &%s;
            |}
            """.stripMargin.format( symbolName, symbolName )

        tryCompile( log, compiler, testProg, includePaths=includePaths )
    }
    
    def testForTypeSize( log : Logger, compiler : Compiler, typeName : String, typeSize : Int, additionalHeaders : Seq[String] = Seq(), includePaths : Seq[File] = Seq() ) =
    {
        val headerIncludes = additionalHeaders.map( h => "#include \"%s\"".format(h) ).mkString("\n")
        val testProg = headerIncludes + "\nstruct TestFoo { unsigned int bf : (sizeof(%s) == %d); };".stripMargin.format( typeName, typeSize )
            
        tryCompile( log, compiler, testProg, includePaths=includePaths )
    }
    
    def testCCompiler( log : Logger, compiler : Compiler )
    {
        assert( tryCompile( log, compiler, """
            |int foo()
            |{
            |    return 0;
            |}""".stripMargin ), "Unable to build minimal c program" )
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
            |}""".stripMargin ), "Unable to build minimal c++ program" )
            
        log.success( "C++ compiler able to build a minimal program" )
    }
    
    /*def minimalTest( s : TaskStreams, compiler : Compiler )
    {
        assert( tryCompile( s, compiler, """
            |int foo()
            |{
            |    return 0;
            |}""".stripMargin ), "Unable to build minimal c program" )
            
        assert( tryCompile( s, compiler, """
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
            |}""".stripMargin ), "Unable to build minimal c++ program" )
            
        def requireHeader( headerName : String )
        {
            assert( testForHeader( s, compiler, headerName ), "Unable to find required header: " + headerName )
        }
        
        def requireSymbol( symbolName : String, headers : Seq[String] )
        {
            assert( testForSymbolDeclaration( s, compiler, symbolName, headers ), "Unable to find required symbol declaration: " + symbolName )
        }
        
        def requireTypeSize( typeName : String, typeSize : Int, headers : Seq[String] )
        {
            assert( testForTypeSize( s, compiler, typeName, typeSize, headers ), "Type %s is not of required size %d".format( typeName, typeSize ) )
        }
        
        requireHeader( "stdio.h" )
        requireHeader( "iostream" )
        
        //requireHeader( "zlib.h" )
        
        assert( !testForHeader( s, compiler, "boggletoop" ) )
        assert( !testForSymbolDeclaration( s, compiler, "toffeecake", Seq("stdio.h") ) )
        
        requireSymbol( "printf", Seq("stdio.h") )
        requireSymbol( "std::cout", Seq("iostream") )
        
        requireTypeSize( "int32_t", 4, Seq("stdint.h") )
        requireTypeSize( "int64_t", 8, Seq("stdint.h") )
        
        assert( !testForTypeSize( s, compiler, "int32_t", 3, Seq("stdint.h") ) )
        assert( !testForTypeSize( s, compiler, "int32_t", 5, Seq("stdint.h") ) )
    }*/
}

