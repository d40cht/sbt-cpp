package org.seacourt.build

import sbt._
import Keys._

import scala.collection.{ mutable, immutable }

/**
 * Class to handle calling a function only if its input files are newer
 * than the file it will create
 */

class FunctionWithResultPath(val resultPath: File, val fn: () => Unit)
{
  import java.security.MessageDigest

  private def md5(s: String) = MessageDigest.getInstance("MD5").digest(s.getBytes).map("%02X" format _).mkString

  def apply() = { fn(); resultPath }
  def runIfNotCached(stateCacheDir: File, inputDeps: Seq[File]) =
  {
    val resultPathHash = md5(resultPath.toString)
    val lazyBuild = FileFunction.cached( stateCacheDir / resultPathHash, FilesInfo.lastModified, FilesInfo.exists)
    { _ =>
    
      IO.delete( resultPath )
      fn()
      Set(resultPath)
    }
      
    lazyBuild(inputDeps.toSet)

    resultPath
  }
}

object FunctionWithResultPath
{
  def apply(resultPath: File)(fn: File => Unit): FunctionWithResultPath =
  {
    new FunctionWithResultPath( resultPath, () => { fn(resultPath) })
  }
}


class HeaderConfigFile(private val fileName: File) {
  private val defs = mutable.ArrayBuffer[(String, String)]()

  def addDefinition(name: String) = defs.append((name, ""))
  def addDefinition(name: String, value: String) = defs.append((name, value))

  def write() = {
    IO.write(fileName, defs.map {
      case (k, v) => "#define %s %s".format(k, v)
    }.mkString("\n"))
  }
}

object HeaderConfigFile {
  def apply(log: Logger, compiler: Compiler, fileName: File)( fn: HeaderConfigFile => Unit): File =
  {
      FunctionWithResultPath(fileName)
      { _ =>
        val hcf = new HeaderConfigFile(fileName)

        fn(hcf)

        hcf.write()
      }()
    }
}

