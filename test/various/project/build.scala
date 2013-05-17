import sbt._
import Keys._

import org.seacourt.build._
import org.seacourt.build.NativeBuild._
import org.seacourt.build.NativeDefaultBuild._

import scala.collection.{mutable, immutable}


object TestBuild extends NativeDefaultBuild
{
    import PlatformChecks._
    import NativeProject._
    
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
    
    lazy val config = NativeProject( "config", file("."),
        baseSettings ++ Seq(
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
    
    lazy val cproject = NativeProject( "cproject", file( "cproject" ), staticLibrarySettings )
    
    lazy val library1 = NativeProject( "library1", file( "library1" ), staticLibrarySettings )
        .nativeDependsOn( checkLib )
        
    lazy val library2 = NativeProject( "library2", file( "library2" ),
        staticLibrarySettings ++ Seq(
            cxxCompileFlags in Compile <++= (buildConfiguration) map
            { be =>
                
                be.conf.debugOptLevel match
                {
                    case Debug      => Seq("-DTHING=1")
                    case Release    => Seq("-DTHING=2")
                }
            },
            
            cxxCompileFlags in Compile <++= (buildConfiguration) map
            { be =>
                
                be.conf.compiler match
                {
                    case Gcc        => Seq("-DCOMPILER=GnueyGoodness")
                    case Clang      => Seq("-DCOMPILER=AppleTart")
                    case VSCl       => Seq("-DCOMPILER=MircoCroft")
                }
            },
            
            cxxCompileFlags in Compile <++= (buildConfiguration) map
            { be =>
                
                be.conf.targetPlatform match
                {
                    case LinuxPC    => Seq("-DTARGET_PLATFORM=x86LinusLand")
                    case WindowsPC  => Seq("-DTARGET_PLATFORM=x86PointyClicky")
                }
            }
        ) )
        .nativeDependsOn( checkLib, library1, config )
        
    /*lazy val boostPython = NativeProject( "boostPython", file("boostpython"),
        sharedLibrarySettings ++ Seq(
            includeDirectories in Compile  += file("/usr/include/python2.7"),
            nativeLibraries in Compile     ++= Seq( "boost_python" ),
            linkFlags in Compile           += "-export-dynamic"
        ) )
        
    lazy val boostPythonTest = NativeProject( "boostPythonTest", file("boostpythontest"),
        baseSettings ++ Seq(
            test <<= (exportedLibDirectories in boostPython, projectDirectory in Compile) map
            { (eld, pd) =>
                
                val testEnvs = Seq( "PYTHONPATH" -> eld.mkString(":") )
                
                Process( Seq("/usr/bin/python", (pd / "test.py").toString), pd, testEnvs : _* ) !!
            }
        ) ).nativeDependsOn( boostPython )*/
        
    lazy val sharedLibrary1 = NativeProject( "libsharedlibrary1", file("sharedlibrary1"),
        sharedLibrarySettings ++ Seq(
            linkFlags in Compile        <++= buildConfiguration map
            { _.conf.compiler match
                {
                    case Gcc | Clang => Seq("-export-dynamic")
                    case VSCl        => Seq()
                }
            },
            nativeLibraries in Test     ++= Seq( "boost_unit_test_framework" ),
            cxxCompileFlags in Test     ++= Seq("-DBOOST_TEST_DYN_LINK", "-DBOOST_TEST_MAIN" )
    ) ) 
    
    lazy val nativeexe = NativeProject( "nativeexe", file("nativeexe"),
        nativeExeSettings
    ).nativeDependsOn( library2, library1, checkLib )

    lazy val scalaJNA = Project(
        id="scalaJNA",
        base=file("scalajna"),
        settings=Defaults.defaultSettings ++ Seq[Sett](
            scalaVersion        := "2.9.2",
            
            libraryDependencies += "org.scalatest" %% "scalatest" % "1.6.1",
            libraryDependencies += "net.java.dev.jna" % "jna" % "3.5.2",
            fork in Test        := true,
            exportJars          := true,
            // When the next version of JNA is released (>3.5.2) it should pick this .so up
            // from the jar automatically.
            mappings in (Compile, packageBin) <++= (exportedLibs in sharedLibrary1) map
            { exportedLibs =>
            
                exportedLibs.map { el => el -> "linux-amd64/%s".format( el.getName ) }
            },
            javaOptions in Test <+= (exportedLibDirectories in sharedLibrary1) map { elds => "-Djna.library.path=" + elds.mkString(":") }
        )
    )
    .dependsOn( TestBuild.sharedLibrary1 )
}


