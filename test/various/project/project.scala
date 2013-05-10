import sbt._
import Keys._

import org.seacourt.build._
import org.seacourt.build.NativeBuild._
import org.seacourt.build.NativeDefaultBuild._

import scala.collection.{mutable, immutable}

/*object ShellPrompt extends Plugin {
  override def settings = Seq(
    shellPrompt := { state =>
      "sbt (%s)> ".format(Project.extract(state).currentProject.id) }
  )
}*/


object TestBuild extends NativeDefaultBuild
{
    import PlatformChecks._
    
    val fooBar = TaskKey[Seq[File]]("all-source-files", "All source files I can find" )
    
    
    override def checkConfiguration( log : Logger, config : BuildConfiguration ) =
    {
        // Require a working c and cxx compiler
        testCCompiler( log, config.compiler )
        testCXXCompiler( log, config.compiler )
        testHeaderParse( log, config.compiler )

        /*assert( tryCompileAndLink( log, env.compiler, """
            |#include <zlib.h>
            |
            |int main(int argc, char** argv)
            |{
            |    void* ptr = (void*) &gzread;
            |}""".stripMargin, CXXTest, linkLibraries=Seq("z") ) )*/
        
        // Check for a few expected headers and type sizes
        requireHeader( log, config.compiler, CCTest, "stdio.h" )
        requireHeader( log, config.compiler, CXXTest, "iostream" )
        assert( !testForHeader( log, config.compiler, CCTest, "iostream" ) )
        
        requireSymbol( log, config.compiler, CCTest, "printf", Seq("stdio.h") )
        requireSymbol( log, config.compiler, CXXTest, "std::cout", Seq("iostream") )
        
        requireTypeSize( log, config.compiler, CCTest, "int32_t", 4, Seq("stdint.h") )
        requireTypeSize( log, config.compiler, CCTest, "int64_t", 8, Seq("stdint.h") )
        
        // Check that a few things that shouldn't exist don't exist
        assert( !testForHeader( log, config.compiler, CCTest, "boggletoop" ) )
        assert( !testForSymbolDeclaration( log, config.compiler, CCTest, "toffeecake", Seq("stdio.h") ) )
        
        assert( !testForTypeSize( log, config.compiler, CXXTest, "int32_t", 3, Seq("stdint.h") ) )
        assert( !testForTypeSize( log, config.compiler, CXXTest, "int32_t", 5, Seq("stdint.h") ) )
        
        requireHeader( log, config.compiler, CXXTest, "boost/python.hpp", includePaths=Seq(file("/usr/include/python2.7")) )
    }
    
    lazy val config = NativeProject( "config", file("."), Seq(
        exportedIncludeDirectories <+= (streams, compiler, projectBuildDirectory) map { (s, c, pbd) =>
            
            val platformHeaderDir = pbd / "interface"
            val platformConfigFile = platformHeaderDir / "platformconfig.hpp"
            
            HeaderConfigFile( s.log, c, platformConfigFile )
            { hcf =>
            
                hcf.addDefinition( "HAS_ZLIB_H",        PlatformChecks.testForHeader( s.log, c, CCTest, "zlib.h" ).toString )
                hcf.addDefinition( "HAS_MALLOC_H",      PlatformChecks.testForHeader( s.log, c, CCTest, "malloc.h" ).toString )
                hcf.addDefinition( "INT_8_BITS",        PlatformChecks.testForTypeSize( s.log, c, CCTest, "int", 1 ).toString )
                hcf.addDefinition( "INT_32_BITS",       PlatformChecks.testForTypeSize( s.log, c, CCTest, "int", 4 ).toString )
                hcf.addDefinition( "LONG_LONG_64_BITS", PlatformChecks.testForTypeSize( s.log, c, CCTest, "long long", 8 ).toString )
            }
        
            platformHeaderDir
        }
    ) )
        
    lazy val checkLib = ProjectRef( file("../utility"), "check" )
    
    lazy val foo = NativeProject( "foo", file("foo"), Seq(
        compile <<= (streams, buildConfiguration) map
        { (s, be) =>
            
            sbt.inc.Analysis.Empty
        } ) )
        
    lazy val cproject = StaticLibrary( "cproject", file( "cproject" ), Seq() )
    
    lazy val library1 = StaticLibrary( "library1", file( "library1" ), Seq() )
        .nativeDependsOn( checkLib )
        
    lazy val library2 = StaticLibrary( "library2", file( "library2" ),
        Seq(
            cxxCompileFlags <++= (buildConfiguration) map
            { be =>
                
                be.conf.debugOptLevel match
                {
                    case Debug      => Seq("-DTHING=1")
                    case Release    => Seq("-DTHING=2")
                }
            },
            
            cxxCompileFlags <++= (buildConfiguration) map
            { be =>
                
                be.conf.compiler match
                {
                    case Gcc        => Seq("-DCOMPILER=GnueyGoodness")
                    case Clang      => Seq("-DCOMPILER=AppleTart")
                    case VSCl       => Seq("-DCOMPILER=MircoCroft")
                }
            },
            
            cxxCompileFlags <++= (buildConfiguration) map
            { be =>
                
                be.conf.targetPlatform match
                {
                    case LinuxPC    => Seq("-DTARGET_PLATFORM=x86LinusLand")
                    case WindowsPC  => Seq("-DTARGET_PLATFORM=x86PointyClicky")
                }
            }
        ) )
        .nativeDependsOn( checkLib, library1, config )
        
    lazy val boostPython = SharedLibrary( "boostPython", file("boostpython"),
        Seq(
            includeDirectories  += file("/usr/include/python2.7"),
            nativeLibraries     ++= Seq( "boost_python" ),
            linkFlags           += "-export-dynamic"
        ) )
        
    lazy val boostPythonTest = NativeProject( "boostPythonTest", file("boostpythontest"),
        Seq(
            test <<= (exportedLibDirectories in boostPython, projectDirectory) map
            { (eld, pd) =>
                
                val testEnvs = Seq( "PYTHONPATH" -> eld.mkString(":") )
                
                Process( Seq("/usr/bin/python", (pd / "test.py").toString), pd, testEnvs : _* ) !!
            }
        ) ).nativeDependsOn( boostPython )
        
        
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


