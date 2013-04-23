package org.seacourt.build

import sbt._
import Keys._
import com.typesafe.config.ConfigFactory

/**
  * A default/example build setup with the following axes:
  *
  * 1. Release/Debug
  * 2. Gcc/Clang
  * 3. LinuxPC/BeagleBone
  *
  * Not all combinations of the above make sense, only the ones that do should
  * be added to the configurations Set
  */

object NativeDefaultBuild
{
    sealed trait DebugOptLevel
    case object Release extends DebugOptLevel
    case object Debug   extends DebugOptLevel
    
    sealed trait NativeCompiler  
    case object Gcc     extends NativeCompiler
    case object Clang   extends NativeCompiler
    case object VSCl    extends NativeCompiler
    
    sealed trait TargetPlatform
    case object LinuxPC     extends TargetPlatform
    case object WindowsPC   extends TargetPlatform
    case object BeagleBone  extends TargetPlatform
}

class NativeDefaultBuild extends NativeBuild
{
    import NativeDefaultBuild._
    import scala.collection.JavaConversions._
    
    case class BuildType( debugOptLevel : DebugOptLevel, compiler : NativeCompiler, targetPlatform : TargetPlatform ) extends BuildTypeTrait
    {
        def name        = debugOptLevel.toString + "_" + compiler.toString + "_" + targetPlatform.toString
        def pathDirs    = Seq( debugOptLevel.toString, compiler.toString, targetPlatform.toString )
    }
    
    lazy val gccDefault = new GccCompiler(
        toolPaths           = conf.getStringList("gcc.toolpaths").map( file ),
        defaultIncludePaths = conf.getStringList("gcc.includepaths").map( file ),
        defaultLibraryPaths = conf.getStringList("gcc.librarypaths").map( file ),
        compilerExe         = file( conf.getString("gcc.compiler") ),
        archiverExe         = file( conf.getString("gcc.archiver") ),
        linkerExe           = file( conf.getString("gcc.linker") ) )
        
    lazy val clangDefault = new GccCompiler(
        toolPaths           = conf.getStringList("clang.toolpaths").map( file ),
        defaultIncludePaths = conf.getStringList("clang.includepaths").map( file ),
        defaultLibraryPaths = conf.getStringList("clang.librarypaths").map( file ),
        compilerExe         = file( conf.getString("clang.compiler") ),
        archiverExe         = file( conf.getString("clang.archiver") ),
        linkerExe           = file( conf.getString("clang.linker") ) )


    lazy val vsDefault = new VSCompiler(
        toolPaths           = conf.getStringList("vscl.toolpaths").map( file ),
        defaultIncludePaths = conf.getStringList("vscl.includepaths").map( file ),
        defaultLibraryPaths = conf.getStringList("vscl.librarypaths").map( file ),
        compilerExe         = file( conf.getString("vscl.compiler") ),
        archiverExe         = file( conf.getString("vscl.archiver") ),
        linkerExe           = file( conf.getString("vscl.linker") ) )

    override lazy val configurations = Set[Environment](
        new Environment( new BuildType( Release, Gcc, LinuxPC ), gccDefault.copy( compileDefaultFlags=conf.getStringList("gcc.release.flags") ) ),
        new Environment( new BuildType( Debug, Gcc, LinuxPC ), gccDefault.copy( compileDefaultFlags=conf.getStringList("gcc.debug.flags") ) ),
        new Environment( new BuildType( Release, Clang, LinuxPC ), clangDefault.copy( compileDefaultFlags=conf.getStringList("clang.release.flags") ) ),
        new Environment( new BuildType( Debug, Clang, LinuxPC ), clangDefault.copy( compileDefaultFlags=conf.getStringList("clang.debug.flags") ) ),
        new Environment( new BuildType( Release, VSCl, WindowsPC ), vsDefault.copy( compileDefaultFlags=conf.getStringList("vscl.release.flags") ) ),
        new Environment( new BuildType( Debug, VSCl, WindowsPC ), vsDefault.copy( compileDefaultFlags=conf.getStringList("vscl.debug.flags") ) )
    )
}
