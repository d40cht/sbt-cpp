package org.seacourt.build

import sbt._
import Keys._

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
    
    sealed trait TargetPlatform
    case object LinuxPC     extends TargetPlatform
    case object BeagleBone  extends TargetPlatform
}

class NativeDefaultBuild extends NativeBuild
{
    import NativeDefaultBuild._ 
    
    case class BuildType( debugOptLevel : DebugOptLevel, compiler : NativeCompiler, targetPlatform : TargetPlatform ) extends BuildTypeTrait
    {
        def name        = debugOptLevel.toString + "_" + compiler.toString + "_" + targetPlatform.toString
        def pathDirs    = Seq( debugOptLevel.toString, compiler.toString, targetPlatform.toString )
    }
    
    lazy val baseGcc = new GccCompiler( file("/usr/bin/g++-4.7"), file("/usr/bin/ar"), file("/usr/bin/g++-4.7") )
    
    override lazy val configurations = Set[Environment](
        new Environment( new BuildType( Release, Gcc, LinuxPC ), baseGcc.copy( compileFlags="-std=c++11 -O2 -Wall -Wextra -DLINUX -DRELEASE" ) ),
        new Environment( new BuildType( Debug, Gcc, LinuxPC ), baseGcc.copy( compileFlags="-std=c++11 -g -Wall -Wextra -DLINUX -DDEBUG" ) )
    )
}
