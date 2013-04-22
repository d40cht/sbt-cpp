import sbt._
import Keys._

import org.seacourt.build._
import org.seacourt.build.NativeBuild._
import org.seacourt.build.NativeDefaultBuild._


object TestCompiler
{
    private def tryCompile( s : TaskStreams, compiler : Compiler, minimalProgram : String, includePaths : Seq[File] = Seq() ) : Boolean =
    {
        IO.withTemporaryDirectory
        { td =>
            
            val testFile = td / "test.cpp"
            IO.write( testFile, minimalProgram )

            try
            {
                compiler.compileToObjectFile( s, td, includePaths, Seq(), testFile, Seq() )()
                
                true
            }
            catch
            {
                case e : java.lang.RuntimeException => false
            }
        }
    }
    
    
    def testForHeader( s : TaskStreams, compiler : Compiler, headerName : String ) =
    {
        tryCompile( s, compiler, "#include \"%s\"\n".format( headerName ) )
    }
    
    
    def testForSymbolDeclaration( s : TaskStreams, compiler : Compiler, symbolName : String, additionalHeaders : Seq[String], includePaths : Seq[File] = Seq() ) =
    {
        val headerIncludes = additionalHeaders.map( h => "#include \"%s\"".format(h) ).mkString("\n")
        val testProg = headerIncludes + """
            |void* foo()
            |{
            |    return (void*) &%s;
            |}
            """.stripMargin.format( symbolName, symbolName )

        tryCompile( s, compiler, testProg, includePaths=includePaths )
    }
    
    def testForTypeSize( s : TaskStreams, compiler : Compiler, typeName : String, typeSize : Int, additionalHeaders : Seq[String], includePaths : Seq[File] = Seq() ) =
    {
        val headerIncludes = additionalHeaders.map( h => "#include \"%s\"".format(h) ).mkString("\n")
        val testProg = headerIncludes + "\nstruct TestFoo { unsigned int bf : (sizeof(%s) == %d); };".stripMargin.format( typeName, typeSize )
            
        tryCompile( s, compiler, testProg, includePaths=includePaths )
    }
    
    /*def testForFunctionDefinition( s : TaskStreams, compiler : Compiler, functionName : String, additionalHeaders : Seq[File], additionalLibraries : Seq[File] ) =
    {
    }*/
    
    def minimalTest( s : TaskStreams, compiler : Compiler )
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
        
        requireHeader( "zlib.h" )
        
        assert( !testForHeader( s, compiler, "boggletoop" ) )
        assert( !testForSymbolDeclaration( s, compiler, "toffeecake", Seq("stdio.h") ) )
        
        requireSymbol( "printf", Seq("stdio.h") )
        requireSymbol( "std::cout", Seq("iostream") )
        
        requireTypeSize( "int32_t", 4, Seq("stdint.h") )
        requireTypeSize( "int64_t", 8, Seq("stdint.h") )
        
        assert( !testForTypeSize( s, compiler, "int32_t", 3, Seq("stdint.h") ) )
        assert( !testForTypeSize( s, compiler, "int32_t", 5, Seq("stdint.h") ) )
    }
}

/*object ScalaLib extends NativeDefaultBuild
{
    import NativeBuild.exportedLibs
    
    
}*/

object TestBuild extends NativeDefaultBuild
{
    lazy val checkLib = ProjectRef( file("../utility"), "check" )
    
    lazy val foo = NativeProject( "foo", file("./foo"), Seq(
        compile <<= (streams, buildEnvironment) map
        { (s, be) =>
        
            println( "Running minimal compiler test" )
            
            TestCompiler.minimalTest( s, be.compiler )
            
            sbt.inc.Analysis.Empty
        } ) )
        
    
    lazy val library1 = StaticLibrary( "library1", file( "./library1" ), Seq() )
        .nativeDependsOn( checkLib )
        
    lazy val library2 = SharedLibrary( "library2", file( "./library2" ),
        Seq(
            cppCompileFlags <++= (buildEnvironment) map
            { be =>
                
                be.conf.debugOptLevel match
                {
                    case Debug      => Seq("-D", "THING=1")
                    case Release    => Seq("-D", "THING=2")
                }
            },
            
            cppCompileFlags <++= (buildEnvironment) map
            { be =>
                
                be.conf.compiler match
                {
                    case Gcc        => Seq("-D", "COMPILER=\"GnueyGoodness\"")
                    case Clang      => Seq("-D", "COMPILER=\"AppleTart\"")
                    case VSCl       => Seq("-D", "COMPILER=\"MircoCroft\"")
                }
            },
            
            cppCompileFlags <++= (buildEnvironment) map
            { be =>
                
                be.conf.targetPlatform match
                {
                    case LinuxPC    => Seq("-D", "TARGET_PLATFORM=\"x86LinusLand\"")
                    case WindowsPC  => Seq("-D", "TARGET_PLATFORM=\"x86PointyClicky\"")
                    case BeagleBone => Seq("-D", "TARGET_PLATFORM=\"Armless\"")
                }
            }
        ) )
        .nativeDependsOn( checkLib, library1 )
        
    lazy val standardSettings = Defaults.defaultSettings
    
    /*lazy val scalaJNA = Project(
        id="scalaJNA",
        base=file("scalajna"),
        settings=standardSettings ++ Seq[Sett](
            scalaVersion        := "2.9.2",
            
            libraryDependencies += "org.scalatest" %% "scalatest" % "1.6.1",
            
            (test in Test)      <<= (test in Test).dependsOn( exportedLibs in TestBuild.library2 ),
            
            // Needed to set the build environment
            commands            += setBuildConfigCommand
        )
    )
    .dependsOn( TestBuild.library2 )*/
}


