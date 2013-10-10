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

import com.typesafe.config.{ Config }
import scala.collection.JavaConversions._

object NativeDefaultBuild {
  trait DebugOptLevel
  case object Release extends DebugOptLevel
  case object Debug extends DebugOptLevel

  trait NativeCompiler
  case object Gcc extends NativeCompiler
  case object Clang extends NativeCompiler
  case object VSCl extends NativeCompiler

  trait TargetPlatform
  case object LinuxPC extends TargetPlatform
  case object WindowsPC extends TargetPlatform
  //case object LinuxBeagleBone extends TargetPlatform
}

class NativeDefaultBuild(override val buildName: String) extends NativeBuild {
  import NativeDefaultBuild._
  import scala.collection.JavaConversions._

  case class BuildType(
    compiler: NativeCompiler,
    targetPlatform: TargetPlatform,
    debugOptLevel: DebugOptLevel) extends BuildTypeTrait
  {
    def pathDirs = Seq(compiler.toString, targetPlatform.toString, debugOptLevel.toString)
    def name = pathDirs.mkString("_")
  }

  def makeConfig(buildType: BuildType, mc: BuildType => Compiler) = new BuildConfiguration(buildType, mc(buildType))

  override lazy val configurations = Set[BuildConfiguration](
    makeConfig( new BuildType(Gcc, LinuxPC, Release), bt => new GccLikeCompiler(conf, bt)),
    makeConfig( new BuildType(Gcc, LinuxPC, Debug), bt => new GccLikeCompiler(conf, bt)),

    makeConfig( new BuildType(Clang, LinuxPC, Release), bt => new GccLikeCompiler(conf, bt)),
    makeConfig( new BuildType(Clang, LinuxPC, Debug), bt => new GccLikeCompiler(conf, bt)),

    makeConfig( new BuildType(VSCl, WindowsPC, Release), bt => new VSCompiler(conf, bt)),
    makeConfig( new BuildType(VSCl, WindowsPC, Debug), bt => new VSCompiler(conf, bt)))
}


